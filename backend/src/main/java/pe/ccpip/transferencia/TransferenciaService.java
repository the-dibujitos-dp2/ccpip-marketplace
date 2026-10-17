package pe.ccpip.transferencia;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.transferencia.TransferenciaModelos.DatosItem;
import pe.ccpip.transferencia.TransferenciaModelos.DatosTransferencia;
import pe.ccpip.transferencia.TransferenciaModelos.Estado;
import pe.ccpip.transferencia.TransferenciaModelos.Item;
import pe.ccpip.transferencia.TransferenciaModelos.Lugar;
import pe.ccpip.transferencia.TransferenciaModelos.Paso;
import pe.ccpip.transferencia.TransferenciaModelos.Sentido;
import pe.ccpip.transferencia.TransferenciaModelos.Tipo;
import pe.ccpip.transferencia.TransferenciaModelos.Transferencia;
import pe.ccpip.transferencia.TransferenciaModelos.Transferencias;
import pe.ccpip.transferencia.TransferenciaModelos.TransferenciasPunto;
import pe.ccpip.transferencia.TransferenciaModelos.Ubicaciones;
import pe.ccpip.transferencia.TransferenciaModelos.Vista;

/**
 * Transferencias de existencias entre ubicaciones (RNF-47): CCPIP-73, 277, 164, 362, 278 y 146.
 *
 * <ul>
 *   <li>Las unidades no se duplican ni se pierden: saldo de origen + separadas + en transito +
 *       destino se conserva. Solo cambia lote.cantidad_actual, y siempre con su movimiento y su
 *       stock en la misma transaccion (RNF-15, ADR-10).</li>
 *   <li>Preparar descuenta el lote de origen (TRANSFERENCIA_SALIDA). Despachar no vuelve a
 *       descontar: las unidades ya estan comprometidas con la transferencia. Recibir crea el lote
 *       del destino (TRANSFERENCIA_ENTRADA). El transito no es un lote de un punto de acopio, asi
 *       que el catalogo no lo cuenta (CCPIP-162).</li>
 *   <li>Concurrencia: la transferencia y sus lotes se bloquean con FOR UPDATE (lotes en orden de id)
 *       antes de revisar saldos; dos preparaciones o recepciones simultaneas se ordenan y la segunda
 *       ve el estado nuevo y se rechaza con 409.</li>
 *   <li>Aislamiento (RNF-05): el productor solo ve y opera las suyas; el administrador, las que
 *       entran o salen de los puntos que tiene en usuario_ubicacion.</li>
 *   <li>C8: el aprobador de las transferencias entre puntos no esta definido. Quedan SOLICITADA,
 *       visibles para el punto, y no avanzan.</li>
 * </ul>
 */
@Service
public class TransferenciaService {

    static final ZoneId LIMA = ZoneId.of("America/Lima");

    private static final String PUNTO = "PUNTO_ACOPIO";
    private static final String ALMACEN = "ALMACEN_PRODUCTOR";

    private static final String SELECT = """
            SELECT t.id, t.tipo, t.estado, t.observacion, t.creada_en, t.actualizada_en, pr.comunidad AS productor,
                   o.id AS o_id, o.nombre AS o_nombre, o.tipo AS o_tipo, o.departamento AS o_depto,
                   o.provincia AS o_prov, o.distrito AS o_dist,
                   d.id AS d_id, d.nombre AS d_nombre, d.tipo AS d_tipo, d.departamento AS d_depto,
                   d.provincia AS d_prov, d.distrito AS d_dist
            FROM transferencia t
            JOIN productor pr ON pr.id = t.productor_id
            JOIN ubicacion o ON o.id = t.origen_id
            JOIN ubicacion d ON d.id = t.destino_id
            """;

    private static final String SUS_PUNTOS = "(SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = :usuario)";

    /** Lo que el punto puede ver: lo que entra o sale de sus puntos, salvo lo que el productor aun no compromete. */
    private static final String VISIBLE_PUNTO = " WHERE t.estado <> 'INICIADA' AND (t.origen_id IN " + SUS_PUNTOS
            + " OR t.destino_id IN " + SUS_PUNTOS + ")";

    private record Cabecera(long id, Tipo tipo, Estado estado, String observacion, Timestamp creadaEn,
                            Timestamp actualizadaEn, String productor, Lugar origen, Lugar destino) {
    }

    private record LoteOrigen(long id, long productoId, long productorId, long ubicacionId, String codigo,
                              BigDecimal cantidad, LocalDate produccion, LocalDate vence) {
    }

    private record ItemGuardado(long id, long productoId, long loteOrigenId, BigDecimal cantidad) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;

    public TransferenciaService(NamedParameterJdbcTemplate jdbc, PuntosDeAcopio puntos) {
        this.jdbc = jdbc;
        this.puntos = puntos;
    }

    // ------------------------------------------------------------------------------------------
    // Productor
    // ------------------------------------------------------------------------------------------

    /** CCPIP-146 (RF-112): su almacen y los puntos de acopio, con su ubicacion registrada. */
    @Transactional(readOnly = true)
    public Ubicaciones ubicaciones(long productorId) {
        List<Lugar> lugares = jdbc.query("""
                SELECT id, nombre, tipo, departamento, provincia, distrito, direccion FROM ubicacion
                WHERE activo AND (tipo = 'PUNTO_ACOPIO' OR (tipo = 'ALMACEN_PRODUCTOR' AND productor_id = :productor))
                ORDER BY tipo DESC, nombre""", new MapSqlParameterSource("productor", productorId),
                (rs, row) -> new Lugar(rs.getLong("id"), rs.getString("nombre"), rs.getString("tipo"),
                        rs.getString("departamento"), rs.getString("provincia"), rs.getString("distrito"),
                        rs.getString("direccion")));
        Lugar almacen = lugares.stream().filter(l -> ALMACEN.equals(l.tipo())).findFirst().orElse(null);
        return new Ubicaciones(almacen, lugares.stream().filter(l -> PUNTO.equals(l.tipo())).toList());
    }

    /**
     * CCPIP-73 (RF-67) y CCPIP-277 (RF-177). Desde su almacen queda INICIADA; entre puntos, SOLICITADA.
     * Aun no compromete unidades: eso lo hace la preparacion, que vuelve a revisar el saldo con bloqueo.
     */
    @Transactional
    public Transferencia crear(long productorId, long usuarioId, DatosTransferencia d) {
        Lugar destino = punto(d.destinoId(), "DESTINO_INVALIDO", "Elige un punto de acopio como destino.");
        long origenId;
        if (d.tipo() == Tipo.ALMACEN_A_PUNTO) {
            origenId = almacen(productorId);
            if (d.origenId() != null && d.origenId() != origenId) {
                throw ErrorNegocio.invalido("ORIGEN_INVALIDO", "El origen debe ser tu almacén.");
            }
        } else {
            if (d.origenId() == null) {
                throw ErrorNegocio.invalido("ORIGEN_INVALIDO", "Elige el punto de acopio de origen.");
            }
            origenId = punto(d.origenId(), "ORIGEN_INVALIDO", "Elige un punto de acopio como origen.").id();
        }
        if (origenId == destino.id()) {
            throw ErrorNegocio.invalido("MISMO_ORIGEN_DESTINO", "El origen y el destino deben ser distintos.");
        }

        Set<Long> vistos = new HashSet<>();
        LocalDate hoy = LocalDate.now(LIMA);
        for (DatosItem item : d.items()) {
            if (!vistos.add(item.loteId())) {
                throw ErrorNegocio.invalido("LOTE_YA_INCLUIDO", "Cada lote va una sola vez en la transferencia.");
            }
            LoteOrigen lote = lote(item.loteId(), false);
            validarLote(lote, productorId, origenId, item.cantidad(), hoy);
        }

        Estado estado = d.tipo() == Tipo.ALMACEN_A_PUNTO ? Estado.INICIADA : Estado.SOLICITADA;
        String observacion = d.observacion() == null || d.observacion().isBlank() ? null : d.observacion().trim();
        Long id = jdbc.queryForObject("""
                INSERT INTO transferencia (tipo, productor_id, origen_id, destino_id, estado, observacion, creada_por)
                VALUES (:tipo, :productor, :origen, :destino, :estado, :observacion, :usuario) RETURNING id""",
                new MapSqlParameterSource("tipo", d.tipo().name()).addValue("productor", productorId)
                        .addValue("origen", origenId).addValue("destino", destino.id())
                        .addValue("estado", estado.name()).addValue("observacion", observacion)
                        .addValue("usuario", usuarioId), Long.class);
        for (DatosItem item : d.items()) {
            jdbc.update("""
                    INSERT INTO transferencia_item (transferencia_id, producto_id, lote_origen_id, cantidad)
                    SELECT :transferencia, producto_id, id, :cantidad FROM lote WHERE id = :lote""",
                    new MapSqlParameterSource("transferencia", id).addValue("cantidad", item.cantidad())
                            .addValue("lote", item.loteId()));
        }
        historial(id, estado, usuarioId);
        return detalleProductor(productorId, id);
    }

    /**
     * CCPIP-164 (RF-133): separa las unidades del lote de origen. Cada lote baja con su movimiento
     * TRANSFERENCIA_SALIDA ligado a la linea. Si un lote ya no alcanza (otra transferencia o una venta
     * lo comprometio), se rechaza entera sin descontar a medias.
     */
    @Transactional
    public Transferencia preparar(long productorId, long usuarioId, long id) {
        Cabecera t = bloquearDelProductor(productorId, id);
        if (t.estado() == Estado.SOLICITADA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "APROBACION_PENDIENTE",
                    "La solicitud entre puntos aún no tiene quien la apruebe; no se puede preparar.");
        }
        exigir(t, Estado.INICIADA, "Solo se prepara una transferencia iniciada.");

        List<ItemGuardado> items = items(id);
        LocalDate hoy = LocalDate.now(LIMA);
        // Bloquea los lotes en orden de id para no cruzarse con otra transaccion
        Map<Long, LoteOrigen> lotes = new LinkedHashMap<>();
        items.stream().map(ItemGuardado::loteOrigenId).sorted().forEach(l -> lotes.put(l, lote(l, true)));
        for (ItemGuardado item : items) {
            LoteOrigen lote = lotes.get(item.loteOrigenId());
            if (lote.vence() != null && lote.vence().isBefore(hoy)) {
                throw new ErrorNegocio(HttpStatus.CONFLICT, "LOTE_VENCIDO", "El lote " + lote.codigo() + " ya venció.");
            }
            if (lote.cantidad().compareTo(item.cantidad()) < 0) {
                throw saldoInsuficiente(lote);
            }
        }

        String codigo = codigo(id);
        for (ItemGuardado item : items) {
            MapSqlParameterSource p = new MapSqlParameterSource("cantidad", item.cantidad())
                    .addValue("lote", item.loteOrigenId()).addValue("producto", item.productoId())
                    .addValue("ubicacion", t.origen().id()).addValue("item", item.id()).addValue("usuario", usuarioId)
                    .addValue("observacion", "Preparada para la transferencia " + codigo);
            jdbc.update("UPDATE lote SET cantidad_actual = cantidad_actual - :cantidad WHERE id = :lote", p);
            jdbc.update("""
                    UPDATE stock SET cantidad = cantidad - :cantidad, actualizado_en = now()
                    WHERE producto_id = :producto AND ubicacion_id = :ubicacion""", p);
            jdbc.update("""
                    INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, usuario_id,
                                                       transferencia_item_id, observacion)
                    VALUES (:producto, :ubicacion, :lote, 'TRANSFERENCIA_SALIDA', :cantidad, :usuario, :item, :observacion)""",
                    p);
        }
        cambiarEstado(id, Estado.PREPARADA, usuarioId);
        return detalleProductor(productorId, id);
    }

    /**
     * CCPIP-362 (RF-252): despacha lo preparado. No toca ningun lote: las unidades ya salieron al
     * preparar y no se descuentan dos veces. Pasan a transito, no vendible.
     */
    @Transactional
    public Transferencia despachar(long productorId, long usuarioId, long id) {
        Cabecera t = bloquearDelProductor(productorId, id);
        exigir(t, Estado.PREPARADA, "Solo se despacha una transferencia preparada.");
        cambiarEstado(id, Estado.DESPACHADA, usuarioId);
        return detalleProductor(productorId, id);
    }

    /** PW-17: sus transferencias, la mas reciente primero, con los contadores de ambas pestanas. */
    @Transactional(readOnly = true)
    public Transferencias listarProductor(long productorId, Vista vista, Tipo tipo) {
        MapSqlParameterSource p = new MapSqlParameterSource("productor", productorId);
        List<Transferencia> todas = cargar(" WHERE t.productor_id = :productor", p, null, false);
        int enCurso = (int) todas.stream().filter(t -> t.estado().enCurso()).count();
        List<Transferencia> items = todas.stream()
                .filter(t -> vista == null || (vista == Vista.EN_CURSO) == t.estado().enCurso())
                .filter(t -> tipo == null || t.tipo() == tipo)
                .toList();
        return new Transferencias(items, enCurso, todas.size() - enCurso);
    }

    @Transactional(readOnly = true)
    public Transferencia detalleProductor(long productorId, long id) {
        return cargar(" WHERE t.id = :id AND t.productor_id = :productor",
                new MapSqlParameterSource("id", id).addValue("productor", productorId), null, true)
                .stream().findFirst().orElseThrow(TransferenciaService::noEncontrada);
    }

    // ------------------------------------------------------------------------------------------
    // Administrador del punto de acopio
    // ------------------------------------------------------------------------------------------

    /** CCPIP-278 (RF-178): lo que entra o sale de sus puntos y lo que espera su recepcion. */
    @Transactional(readOnly = true)
    public TransferenciasPunto listarPunto(long usuarioId) {
        String ubicacion = puntos.nombres(usuarioId);
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId);
        List<Transferencia> items = cargar(VISIBLE_PUNTO, p, misPuntos(usuarioId), false);
        List<Transferencia> porRecibir = items.stream()
                .filter(t -> t.sentido() == Sentido.ENTRANTE && t.estado() == Estado.DESPACHADA).toList();
        return new TransferenciasPunto(ubicacion, porRecibir, items);
    }

    /** CCPIP-278: el detalle con su historial. 404 si no entra ni sale de sus puntos (RNF-05). */
    @Transactional(readOnly = true)
    public Transferencia detallePunto(long usuarioId, long id) {
        puntos.nombres(usuarioId);
        return cargar(VISIBLE_PUNTO + " AND t.id = :id", new MapSqlParameterSource("usuario", usuarioId).addValue("id", id),
                misPuntos(usuarioId), true).stream().findFirst().orElseThrow(TransferenciaService::noEncontrada);
    }

    /**
     * CCPIP-278: el administrador del destino registra la recepcion. Cada linea entra a un lote nuevo
     * del punto, con el mismo vencimiento que el de origen y su movimiento TRANSFERENCIA_ENTRADA; recien
     * ahi es vendible. Solo el destino recibe; el origen ve la transferencia pero no la recibe.
     */
    @Transactional
    public Transferencia recibir(long usuarioId, long id) {
        puntos.nombres(usuarioId);
        MapSqlParameterSource p = new MapSqlParameterSource("usuario", usuarioId).addValue("id", id);
        Long bloqueada = jdbc.query("SELECT t.id FROM transferencia t" + VISIBLE_PUNTO + " AND t.id = :id FOR UPDATE",
                p, (rs, row) -> rs.getLong("id")).stream().findFirst().orElseThrow(TransferenciaService::noEncontrada);
        Cabecera t = cabecera(bloqueada);
        if (!misPuntos(usuarioId).contains(t.destino().id())) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "NO_ES_DESTINO",
                    "Solo el punto de destino registra la recepción.");
        }
        exigir(t, Estado.DESPACHADA, "Solo se recibe una transferencia despachada.");

        String codigo = codigo(id);
        for (ItemGuardado item : items(id)) {
            LoteOrigen origen = lote(item.loteOrigenId(), false);
            String codigoDestino = abreviar(origen.codigo()) + "-" + codigo;
            MapSqlParameterSource q = new MapSqlParameterSource("producto", item.productoId())
                    .addValue("ubicacion", t.destino().id()).addValue("codigo", codigoDestino)
                    .addValue("cantidad", item.cantidad()).addValue("produccion", origen.produccion())
                    .addValue("vence", origen.vence()).addValue("usuario", usuarioId).addValue("item", item.id())
                    .addValue("observacion", "Recepción de la transferencia " + codigo);
            Long loteDestino = jdbc.queryForObject("""
                    INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                      fecha_produccion, fecha_vencimiento, registrado_por)
                    VALUES (:producto, :ubicacion, :codigo, :cantidad, :cantidad, :produccion, :vence, :usuario)
                    RETURNING id""", q, Long.class);
            q.addValue("lote", loteDestino);
            jdbc.update("""
                    INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, usuario_id,
                                                       transferencia_item_id, observacion)
                    VALUES (:producto, :ubicacion, :lote, 'TRANSFERENCIA_ENTRADA', :cantidad, :usuario, :item, :observacion)""",
                    q);
            jdbc.update("""
                    INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (:producto, :ubicacion, :cantidad)
                    ON CONFLICT (producto_id, ubicacion_id)
                    DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad, actualizado_en = now()""", q);
            jdbc.update("UPDATE transferencia_item SET lote_destino_id = :lote WHERE id = :item", q);
        }
        cambiarEstado(id, Estado.RECIBIDA, usuarioId);
        return detallePunto(usuarioId, id);
    }

    // ------------------------------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------------------------------

    /** Codigo visible de la transferencia, como en PW-17 (TR-0031). */
    static String codigo(long id) {
        return "TR-%04d".formatted(id);
    }

    /** El codigo del lote de destino cabe en 40 caracteres aunque el de origen sea largo. */
    private static String abreviar(String codigo) {
        return codigo.length() > 30 ? codigo.substring(0, 30) : codigo;
    }

    private Set<Long> misPuntos(long usuarioId) {
        return new HashSet<>(jdbc.queryForList("SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = :usuario",
                new MapSqlParameterSource("usuario", usuarioId), Long.class));
    }

    private long almacen(long productorId) {
        return jdbc.queryForList("""
                SELECT id FROM ubicacion WHERE productor_id = :productor AND tipo = 'ALMACEN_PRODUCTOR' AND activo""",
                new MapSqlParameterSource("productor", productorId), Long.class).stream().findFirst()
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.CONFLICT, "SIN_ALMACEN", "Aún no tienes un almacén registrado."));
    }

    private Lugar punto(long id, String code, String mensaje) {
        return jdbc.query("""
                SELECT id, nombre, tipo, departamento, provincia, distrito FROM ubicacion
                WHERE id = :id AND tipo = 'PUNTO_ACOPIO' AND activo""", new MapSqlParameterSource("id", id),
                (rs, row) -> new Lugar(rs.getLong("id"), rs.getString("nombre"), rs.getString("tipo"),
                        rs.getString("departamento"), rs.getString("provincia"), rs.getString("distrito"), null))
                .stream().findFirst().orElseThrow(() -> ErrorNegocio.invalido(code, mensaje));
    }

    private LoteOrigen lote(long id, boolean bloquear) {
        return jdbc.query("""
                SELECT l.id, l.producto_id, p.productor_id, l.ubicacion_id, l.codigo, l.cantidad_actual,
                       l.fecha_produccion, l.fecha_vencimiento
                FROM lote l JOIN producto p ON p.id = l.producto_id WHERE l.id = :id""" + (bloquear ? " FOR UPDATE OF l" : ""),
                new MapSqlParameterSource("id", id),
                (rs, row) -> new LoteOrigen(rs.getLong("id"), rs.getLong("producto_id"), rs.getLong("productor_id"),
                        rs.getLong("ubicacion_id"), rs.getString("codigo"), rs.getBigDecimal("cantidad_actual"),
                        rs.getObject("fecha_produccion", LocalDate.class), rs.getObject("fecha_vencimiento", LocalDate.class)))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("LOTE_NO_ENCONTRADO", "El lote no existe."));
    }

    private static void validarLote(LoteOrigen lote, long productorId, long origenId, BigDecimal cantidad, LocalDate hoy) {
        // Un lote ajeno se reporta igual que uno inexistente, para no revelar lotes de otros (RNF-05)
        if (lote.productorId() != productorId) {
            throw ErrorNegocio.noEncontrado("LOTE_NO_ENCONTRADO", "El lote no existe.");
        }
        if (lote.ubicacionId() != origenId) {
            throw ErrorNegocio.invalido("LOTE_FUERA_DE_ORIGEN", "El lote " + lote.codigo() + " no está en el origen elegido.");
        }
        if (lote.vence() != null && lote.vence().isBefore(hoy)) {
            throw ErrorNegocio.invalido("LOTE_VENCIDO", "El lote " + lote.codigo() + " ya venció.");
        }
        if (lote.cantidad().compareTo(cantidad) < 0) {
            throw saldoInsuficiente(lote);
        }
    }

    private static ErrorNegocio saldoInsuficiente(LoteOrigen lote) {
        return new ErrorNegocio(HttpStatus.CONFLICT, "SALDO_INSUFICIENTE",
                "El lote " + lote.codigo() + " solo tiene " + lote.cantidad().stripTrailingZeros().toPlainString()
                        + " disponibles.");
    }

    private static ErrorNegocio noEncontrada() {
        return ErrorNegocio.noEncontrado("TRANSFERENCIA_NO_ENCONTRADA", "La transferencia no existe.");
    }

    private static void exigir(Cabecera t, Estado esperado, String mensaje) {
        if (t.estado() != esperado) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "TRANSICION_INVALIDA",
                    mensaje + " Esta está " + t.estado().name().toLowerCase(java.util.Locale.ROOT) + ".");
        }
    }

    private Cabecera bloquearDelProductor(long productorId, long id) {
        Long bloqueada = jdbc.query("SELECT id FROM transferencia WHERE id = :id AND productor_id = :productor FOR UPDATE",
                new MapSqlParameterSource("id", id).addValue("productor", productorId), (rs, row) -> rs.getLong("id"))
                .stream().findFirst().orElseThrow(TransferenciaService::noEncontrada);
        return cabecera(bloqueada);
    }

    private Cabecera cabecera(long id) {
        return jdbc.query(SELECT + " WHERE t.id = :id", new MapSqlParameterSource("id", id), (rs, row) -> cabecera(rs))
                .get(0);
    }

    private List<ItemGuardado> items(long transferenciaId) {
        return jdbc.query("""
                SELECT id, producto_id, lote_origen_id, cantidad FROM transferencia_item
                WHERE transferencia_id = :id ORDER BY id""", new MapSqlParameterSource("id", transferenciaId),
                (rs, row) -> new ItemGuardado(rs.getLong("id"), rs.getLong("producto_id"), rs.getLong("lote_origen_id"),
                        rs.getBigDecimal("cantidad")));
    }

    private void cambiarEstado(long id, Estado estado, long usuarioId) {
        jdbc.update("UPDATE transferencia SET estado = :estado, actualizada_en = now() WHERE id = :id",
                new MapSqlParameterSource("estado", estado.name()).addValue("id", id));
        historial(id, estado, usuarioId);
    }

    private void historial(long id, Estado estado, long usuarioId) {
        jdbc.update("""
                INSERT INTO transferencia_historial (transferencia_id, estado, usuario_id) VALUES (:id, :estado, :usuario)""",
                new MapSqlParameterSource("id", id).addValue("estado", estado.name()).addValue("usuario", usuarioId));
    }

    private static Cabecera cabecera(ResultSet rs) throws SQLException {
        return new Cabecera(rs.getLong("id"), Tipo.valueOf(rs.getString("tipo")), Estado.valueOf(rs.getString("estado")),
                rs.getString("observacion"), rs.getTimestamp("creada_en"), rs.getTimestamp("actualizada_en"),
                rs.getString("productor"),
                new Lugar(rs.getLong("o_id"), rs.getString("o_nombre"), rs.getString("o_tipo"), rs.getString("o_depto"),
                        rs.getString("o_prov"), rs.getString("o_dist"), null),
                new Lugar(rs.getLong("d_id"), rs.getString("d_nombre"), rs.getString("d_tipo"), rs.getString("d_depto"),
                        rs.getString("d_prov"), rs.getString("d_dist"), null));
    }

    /**
     * Arma las transferencias que cumplen {@code donde}, la mas reciente primero, con sus lineas.
     * {@code puntosDelUsuario} no nulo agrega el sentido para el administrador del punto.
     */
    private List<Transferencia> cargar(String donde, MapSqlParameterSource p, Set<Long> puntosDelUsuario,
                                       boolean conHistorial) {
        List<Cabecera> cabeceras = jdbc.query(SELECT + donde + " ORDER BY t.creada_en DESC, t.id DESC", p,
                (rs, row) -> cabecera(rs));
        if (cabeceras.isEmpty()) {
            return List.of();
        }
        List<Long> ids = cabeceras.stream().map(Cabecera::id).toList();
        Map<Long, List<Item>> itemsPorTransferencia = new LinkedHashMap<>();
        jdbc.query("""
                SELECT ti.transferencia_id, ti.id, ti.producto_id, pr.nombre AS producto, um.codigo AS unidad,
                       ti.lote_origen_id, lo.codigo AS lote_origen, lo.fecha_produccion, lo.fecha_vencimiento,
                       ti.cantidad, ld.codigo AS lote_destino
                FROM transferencia_item ti
                JOIN producto pr ON pr.id = ti.producto_id
                JOIN unidad_medida um ON um.id = pr.unidad_medida_id
                JOIN lote lo ON lo.id = ti.lote_origen_id
                LEFT JOIN lote ld ON ld.id = ti.lote_destino_id
                WHERE ti.transferencia_id IN (:ids) ORDER BY ti.id""", new MapSqlParameterSource("ids", ids), rs -> {
            itemsPorTransferencia.computeIfAbsent(rs.getLong("transferencia_id"), k -> new ArrayList<>())
                    .add(new Item(rs.getLong("id"), rs.getLong("producto_id"), rs.getString("producto"),
                            rs.getString("unidad"), rs.getLong("lote_origen_id"), rs.getString("lote_origen"),
                            rs.getObject("fecha_produccion", LocalDate.class),
                            rs.getObject("fecha_vencimiento", LocalDate.class), rs.getBigDecimal("cantidad"),
                            rs.getString("lote_destino")));
        });
        Map<Long, List<Paso>> pasos = new LinkedHashMap<>();
        if (conHistorial) {
            jdbc.query("""
                    SELECT h.transferencia_id, h.estado, h.registrado_en, u.nombre FROM transferencia_historial h
                    LEFT JOIN usuario u ON u.id = h.usuario_id
                    WHERE h.transferencia_id IN (:ids) ORDER BY h.registrado_en, h.id""",
                    new MapSqlParameterSource("ids", ids), rs -> {
                pasos.computeIfAbsent(rs.getLong("transferencia_id"), k -> new ArrayList<>())
                        .add(new Paso(Estado.valueOf(rs.getString("estado")),
                                rs.getTimestamp("registrado_en").toInstant(), rs.getString("nombre")));
            });
        }
        return cabeceras.stream().map(c -> {
            List<Item> items = itemsPorTransferencia.getOrDefault(c.id(), List.of());
            BigDecimal cantidad = items.stream().map(Item::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
            Sentido sentido = puntosDelUsuario == null ? null
                    : puntosDelUsuario.contains(c.destino().id()) ? Sentido.ENTRANTE : Sentido.SALIENTE;
            return new Transferencia(c.id(), codigo(c.id()), c.tipo(), c.estado(), c.productor(), c.origen(),
                    c.destino(), cantidad, items, c.observacion(), c.creadaEn().toInstant(),
                    c.actualizadaEn().toInstant(), sentido, conHistorial ? pasos.getOrDefault(c.id(), List.of()) : null);
        }).toList();
    }
}
