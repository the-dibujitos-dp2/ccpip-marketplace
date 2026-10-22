package pe.ccpip.valoracion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.catalogo.CatalogoRepository;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.valoracion.ValoracionModelos.BandejaDenuncias;
import pe.ccpip.valoracion.ValoracionModelos.CambioEstado;
import pe.ccpip.valoracion.ValoracionModelos.Contadores;
import pe.ccpip.valoracion.ValoracionModelos.DenunciaAdmin;
import pe.ccpip.valoracion.ValoracionModelos.DenunciaRecibida;
import pe.ccpip.valoracion.ValoracionModelos.DenunciasRecibidas;
import pe.ccpip.valoracion.ValoracionModelos.EstadoDenuncia;
import pe.ccpip.valoracion.ValoracionModelos.EventoDenuncia;
import pe.ccpip.valoracion.ValoracionModelos.ExpedienteProductor;
import pe.ccpip.valoracion.ValoracionModelos.MiDenuncia;
import pe.ccpip.valoracion.ValoracionModelos.Motivo;
import pe.ccpip.valoracion.ValoracionModelos.NuevaDenuncia;
import pe.ccpip.valoracion.ValoracionModelos.ProductorDenunciado;
import pe.ccpip.valoracion.ValoracionModelos.Referencia;
import pe.ccpip.valoracion.ValoracionModelos.ResumenRecibidas;

/**
 * Denuncias de compradores sobre productos o productores.
 *
 * <ul>
 *   <li>CCPIP-83 (RF-90): el comprador la registra con motivo y descripcion, sin aprobacion previa.</li>
 *   <li>CCPIP-84 (RF-77): el subadministrador las consulta por productor, ordenadas por cantidad, con su
 *       historial, y las pasa a revision o las cierra con una nota que queda auditada.</li>
 *   <li>CCPIP-116 (RF-91) y CCPIP-323 (RF-216): el productor ve las suyas anonimizadas y responde una vez.</li>
 * </ul>
 * RNF-41: el denunciante nunca sale del servidor. Las consultas del productor y del subadministrador no
 * seleccionan {@code denunciante_id} ni datos del usuario; y la descripcion se entrega con correos y
 * telefonos ocultos. Nada se borra: cada paso queda en {@code denuncia_evento}.
 */
@Service
public class DenunciasService {

    private static final Logger log = LoggerFactory.getLogger(DenunciasService.class);

    /** Una secuencia con al menos estos digitos se trata como telefono o documento. */
    private static final int DIGITOS_IDENTIFICABLES = 8;
    static final String CORREO_OCULTO = "[correo oculto]";
    static final String TELEFONO_OCULTO = "[teléfono oculto]";

    private static final String ORGANIZACION = "coalesce(pr.nombre_tienda, pr.comunidad)";

    /** Columnas de la denuncia que ven el productor y el subadministrador. Nunca el denunciante. */
    private static final String COLUMNAS = """
            d.id, d.producto_id, p.nombre AS producto, d.motivo, d.descripcion, d.estado, d.creada_en,
            d.respuesta_productor, d.respondida_en
            FROM denuncia d LEFT JOIN producto p ON p.id = d.producto_id
            """;

    private static final String RESUMEN_PRODUCTOR = """
            SELECT pr.id, %s AS organizacion, pr.comunidad, pr.region, count(*) AS total,
                   count(*) FILTER (WHERE d.estado IN ('RECIBIDA', 'EN_REVISION')) AS abiertas,
                   max(d.creada_en) AS ultima_en, pr.venta_bloqueada
            FROM denuncia d JOIN productor pr ON pr.id = d.productor_id""".formatted(ORGANIZACION);

    /** {@code compra_verificada} se calcula aqui; el denunciante no se selecciona. */
    private static final String COLUMNAS_ADMIN = """
            EXISTS (SELECT 1 FROM pedido pe JOIN item_pedido i ON i.pedido_id = pe.id
                    WHERE pe.usuario_id = d.denunciante_id AND i.productor_id = d.productor_id
                      AND pe.estado = 'ENTREGADO') AS compra_verificada,
            """ + COLUMNAS;

    private final JdbcTemplate jdbc;

    public DenunciasService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- Comprador (CCPIP-83) ---

    /**
     * Registra la denuncia sin aprobacion previa. Se denuncia una oferta visible o comprada, o una
     * comunidad activa con tienda publicada o a la que se le compro. Una denuncia abierta del mismo
     * comprador sobre lo mismo no se duplica.
     */
    @Transactional
    public MiDenuncia registrar(long usuarioId, NuevaDenuncia nueva) {
        if ((nueva.productoId() == null) == (nueva.productorId() == null)) {
            throw ErrorNegocio.invalido("OBJETO_DENUNCIA_INVALIDO", "Indica el producto o la comunidad que denuncias.");
        }
        long productorId = nueva.productoId() != null
                ? productorDeOferta(usuarioId, nueva.productoId())
                : productorDenunciable(usuarioId, nueva.productorId());
        // Bloquea al denunciante: dos envios simultaneos no crean dos denuncias abiertas iguales
        jdbc.queryForObject("SELECT id FROM usuario WHERE id = ? FOR UPDATE", Long.class, usuarioId);
        Integer abiertas = jdbc.queryForObject("""
                SELECT count(*) FROM denuncia
                WHERE denunciante_id = ? AND productor_id = ? AND producto_id IS NOT DISTINCT FROM ?
                  AND estado IN ('RECIBIDA', 'EN_REVISION')""", Integer.class, usuarioId, productorId, nueva.productoId());
        if (abiertas != null && abiertas > 0) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "DENUNCIA_DUPLICADA",
                    "Ya tienes una denuncia abierta sobre esto. La Cámara la está atendiendo.");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO denuncia (denunciante_id, productor_id, producto_id, motivo, descripcion)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class,
                usuarioId, productorId, nueva.productoId(), nueva.motivo().name(), nueva.descripcion().strip());
        evento(id, "REGISTRADA", EstadoDenuncia.RECIBIDA, null, usuarioId, "CONSUMIDOR");
        return misDenuncias(usuarioId, id).get(0);
    }

    @Transactional(readOnly = true)
    public List<MiDenuncia> misDenuncias(long usuarioId) {
        return misDenuncias(usuarioId, null);
    }

    private List<MiDenuncia> misDenuncias(long usuarioId, Long id) {
        return jdbc.query("""
                SELECT d.id, d.producto_id, p.nombre AS producto, pr.id AS productor_id, %s AS organizacion,
                       d.motivo, d.descripcion, d.estado, d.creada_en, d.respuesta_productor, d.respondida_en
                FROM denuncia d
                JOIN productor pr ON pr.id = d.productor_id
                LEFT JOIN producto p ON p.id = d.producto_id
                WHERE d.denunciante_id = ? AND (?::bigint IS NULL OR d.id = ?)
                ORDER BY d.creada_en DESC, d.id DESC""".formatted(ORGANIZACION),
                (rs, row) -> new MiDenuncia(rs.getLong("id"), producto(rs),
                        new Referencia(rs.getLong("productor_id"), rs.getString("organizacion")),
                        Motivo.valueOf(rs.getString("motivo")), rs.getString("descripcion"), estado(rs),
                        instante(rs, "creada_en"), rs.getString("respuesta_productor"), instante(rs, "respondida_en")),
                usuarioId, id, id);
    }

    private long productorDeOferta(long usuarioId, long productoId) {
        // La subconsulta del catalogo usa su propio alias p: no depende del producto exterior
        return jdbc.query("""
                SELECT o.productor_id FROM producto o
                WHERE o.id = ? AND (o.id IN (SELECT p.id %s)
                   OR EXISTS (SELECT 1 FROM item_pedido i JOIN pedido pe ON pe.id = i.pedido_id
                              WHERE i.producto_id = o.id AND pe.usuario_id = ?))""".formatted(CatalogoRepository.VISIBLE),
                        (rs, row) -> rs.getLong(1), productoId, usuarioId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "El producto no existe o ya no está disponible"));
    }

    private long productorDenunciable(long usuarioId, long productorId) {
        return jdbc.query("""
                SELECT pr.id FROM productor pr
                WHERE pr.id = ? AND pr.activo AND (pr.tienda_publicada
                   OR EXISTS (SELECT 1 FROM item_pedido i JOIN pedido pe ON pe.id = i.pedido_id
                              WHERE i.productor_id = pr.id AND pe.usuario_id = ?))""",
                        (rs, row) -> rs.getLong(1), productorId, usuarioId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PRODUCTOR_NO_ENCONTRADO", "La comunidad no existe o no está activa."));
    }

    // --- Productor (CCPIP-116, CCPIP-323) ---

    /** Solo las denuncias sobre el productor de la sesion (RNF-05), anonimizadas en el servidor (RNF-41). */
    @Transactional(readOnly = true)
    public DenunciasRecibidas recibidas(long productorId) {
        List<DenunciaRecibida> denuncias = jdbc.query("SELECT " + COLUMNAS
                        + "WHERE d.productor_id = ? ORDER BY d.creada_en DESC, d.id DESC",
                (rs, row) -> recibida(rs), productorId);
        int abiertas = (int) denuncias.stream().filter(d -> d.estado().abierta()).count();
        int sinResponder = (int) denuncias.stream().filter(DenunciaRecibida::puedeResponder).count();
        return new DenunciasRecibidas(new ResumenRecibidas(denuncias.size(), abiertas, sinResponder), denuncias);
    }

    /** CCPIP-323: una sola respuesta por denuncia, mientras siga abierta. Una ajena responde 404. */
    @Transactional
    public DenunciaRecibida responder(long productorId, long usuarioId, long denunciaId, String texto) {
        Fila d = bloquear("d.id = ? AND d.productor_id = ?", denunciaId, productorId);
        if (d.respondida()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "DENUNCIA_YA_RESPONDIDA", "Ya respondiste esta denuncia.");
        }
        if (!d.estado().abierta()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "DENUNCIA_CERRADA", "La denuncia ya fue cerrada por la Cámara.");
        }
        String respuesta = texto.strip();
        jdbc.update("UPDATE denuncia SET respuesta_productor = ?, respondida_en = now(), actualizada_en = now() WHERE id = ?",
                respuesta, denunciaId);
        evento(denunciaId, "RESPUESTA", d.estado(), respuesta, usuarioId, "PRODUCTOR");
        return jdbc.queryForObject("SELECT " + COLUMNAS + "WHERE d.id = ?", (rs, row) -> recibida(rs), denunciaId);
    }

    private static DenunciaRecibida recibida(ResultSet rs) throws SQLException {
        EstadoDenuncia estado = estado(rs);
        String respuesta = rs.getString("respuesta_productor");
        return new DenunciaRecibida(rs.getLong("id"), producto(rs), Motivo.valueOf(rs.getString("motivo")),
                anonimizar(rs.getString("descripcion")), estado,
                rs.getTimestamp("creada_en").toInstant().atZone(CalificacionesService.LIMA).toLocalDate(),
                respuesta, instante(rs, "respondida_en"), respuesta == null && estado.abierta());
    }

    // --- Subadministrador (CCPIP-84) ---

    /** Productores con denuncias, del mas denunciado al menos; a igual cantidad, mas abiertas primero. */
    @Transactional(readOnly = true)
    public BandejaDenuncias bandeja() {
        Contadores contadores = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE estado = 'RECIBIDA'), count(*) FILTER (WHERE estado = 'EN_REVISION'),
                       count(*) FILTER (WHERE estado = 'RESUELTA'), count(*) FILTER (WHERE estado = 'DESESTIMADA')
                FROM denuncia""", (rs, row) -> new Contadores(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
        List<ProductorDenunciado> productores = jdbc.query(RESUMEN_PRODUCTOR
                        + " GROUP BY pr.id ORDER BY total DESC, abiertas DESC, ultima_en DESC, pr.id",
                (rs, row) -> productorDenunciado(rs));
        return new BandejaDenuncias(contadores, productores);
    }

    /** Historial completo de denuncias de un productor, con cada paso y quien lo dio. */
    @Transactional(readOnly = true)
    public ExpedienteProductor expediente(long productorId) {
        ProductorDenunciado productor = jdbc.query(RESUMEN_PRODUCTOR + " WHERE pr.id = ? GROUP BY pr.id",
                        (rs, row) -> productorDenunciado(rs), productorId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("PRODUCTOR_SIN_DENUNCIAS", "Esta comunidad no tiene denuncias."));
        List<DenunciaAdmin> denuncias = jdbc.query("SELECT " + COLUMNAS_ADMIN
                        + "WHERE d.productor_id = ? ORDER BY d.creada_en DESC, d.id DESC",
                (rs, row) -> denunciaAdmin(rs), productorId);
        return new ExpedienteProductor(productor, conHistorial(denuncias));
    }

    /**
     * Pasa la denuncia a revision o la cierra (resuelta o desestimada) con una nota. Accion sensible:
     * queda en el historial con el subadministrador que la tomo, y en el registro de la aplicacion.
     */
    @Transactional
    public DenunciaAdmin cambiarEstado(long usuarioId, long denunciaId, CambioEstado cambio) {
        Fila d = bloquear("d.id = ?", denunciaId);
        if (!siguientes(d.estado()).contains(cambio.estado())) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "TRANSICION_INVALIDA", d.estado().abierta()
                    ? "La denuncia no puede pasar a ese estado."
                    : "La denuncia ya está cerrada y no cambia de estado.");
        }
        String nota = cambio.nota().strip();
        jdbc.update("UPDATE denuncia SET estado = ?, actualizada_en = now() WHERE id = ?", cambio.estado().name(), denunciaId);
        evento(denunciaId, "CAMBIO_ESTADO", cambio.estado(), nota, usuarioId, "SUB_ADMIN");
        log.info("Denuncia {} pasa de {} a {} por el usuario {}", denunciaId, d.estado(), cambio.estado(), usuarioId);
        DenunciaAdmin actual = jdbc.queryForObject("SELECT " + COLUMNAS_ADMIN + "WHERE d.id = ?",
                (rs, row) -> denunciaAdmin(rs), denunciaId);
        return conHistorial(List.of(actual)).get(0);
    }

    private static ProductorDenunciado productorDenunciado(ResultSet rs) throws SQLException {
        return new ProductorDenunciado(rs.getLong("id"), rs.getString("organizacion"), rs.getString("comunidad"),
                rs.getString("region"), rs.getInt("total"), rs.getInt("abiertas"), instante(rs, "ultima_en"),
                rs.getBoolean("venta_bloqueada"));
    }

    private static DenunciaAdmin denunciaAdmin(ResultSet rs) throws SQLException {
        EstadoDenuncia estado = estado(rs);
        return new DenunciaAdmin(rs.getLong("id"), producto(rs), Motivo.valueOf(rs.getString("motivo")),
                anonimizar(rs.getString("descripcion")), estado, instante(rs, "creada_en"),
                rs.getBoolean("compra_verificada"), rs.getString("respuesta_productor"), instante(rs, "respondida_en"),
                siguientes(estado), List.of());
    }

    /** Agrega a cada denuncia su historial. El responsable del registro (el comprador) nunca se nombra. */
    private List<DenunciaAdmin> conHistorial(List<DenunciaAdmin> denuncias) {
        if (denuncias.isEmpty()) {
            return denuncias;
        }
        Map<Long, List<EventoDenuncia>> eventos = new LinkedHashMap<>();
        Long[] ids = denuncias.stream().map(DenunciaAdmin::id).toArray(Long[]::new);
        jdbc.query("""
                    SELECT e.denuncia_id, e.tipo, e.estado, e.rol, e.nota, e.registrado_en,
                           CASE e.rol WHEN 'SUB_ADMIN' THEN u.nombre
                                      WHEN 'PRODUCTOR' THEN coalesce(pr.nombre_tienda, pr.comunidad) END AS responsable
                    FROM denuncia_evento e
                    JOIN denuncia d ON d.id = e.denuncia_id
                    JOIN productor pr ON pr.id = d.productor_id
                    LEFT JOIN usuario u ON u.id = e.usuario_id AND e.rol = 'SUB_ADMIN'
                    WHERE e.denuncia_id = ANY (?) ORDER BY e.registrado_en, e.id""", (RowCallbackHandler) rs -> {
            eventos.computeIfAbsent(rs.getLong("denuncia_id"), k -> new ArrayList<>())
                    .add(new EventoDenuncia(rs.getString("tipo"), EstadoDenuncia.valueOf(rs.getString("estado")),
                            rs.getString("rol"), rs.getString("responsable"),
                            "REGISTRADA".equals(rs.getString("tipo")) ? null : rs.getString("nota"),
                            instante(rs, "registrado_en")));
        }, (Object) ids);
        return denuncias.stream().map(d -> new DenunciaAdmin(d.id(), d.producto(), d.motivo(), d.descripcion(), d.estado(),
                d.creadaEn(), d.compraVerificada(), d.respuestaProductor(), d.respondidaEn(), d.siguientes(),
                eventos.getOrDefault(d.id(), List.of()))).toList();
    }

    static List<EstadoDenuncia> siguientes(EstadoDenuncia estado) {
        return switch (estado) {
            case RECIBIDA -> List.of(EstadoDenuncia.EN_REVISION, EstadoDenuncia.RESUELTA, EstadoDenuncia.DESESTIMADA);
            case EN_REVISION -> List.of(EstadoDenuncia.RESUELTA, EstadoDenuncia.DESESTIMADA);
            case RESUELTA, DESESTIMADA -> List.of();
        };
    }

    // --- Comun ---

    /**
     * RNF-41: oculta correos y telefonos que el comprador haya escrito en la descripcion, para que la
     * denuncia no lo identifique ante el denunciado ni ante quien la revisa.
     */
    static String anonimizar(String texto) {
        if (texto == null) {
            return null;
        }
        StringBuilder salida = new StringBuilder(texto.length());
        StringBuilder palabra = new StringBuilder();
        for (int i = 0; i <= texto.length(); i++) {
            char c = i < texto.length() ? texto.charAt(i) : ' ';
            if (!Character.isWhitespace(c)) {
                palabra.append(c);
                continue;
            }
            salida.append(esCorreo(palabra) ? CORREO_OCULTO : palabra);
            palabra.setLength(0);
            if (i < texto.length()) {
                salida.append(c);
            }
        }
        return ocultarNumeros(salida.toString());
    }

    private static boolean esCorreo(CharSequence palabra) {
        String p = palabra.toString();
        int arroba = p.indexOf('@');
        return arroba > 0 && p.indexOf('.', arroba) > arroba + 1;
    }

    /** Reemplaza cada tramo de digitos, espacios, guiones o "+" que junte suficientes digitos. */
    private static String ocultarNumeros(String texto) {
        StringBuilder salida = new StringBuilder(texto.length());
        int i = 0;
        while (i < texto.length()) {
            if (!Character.isDigit(texto.charAt(i)) && texto.charAt(i) != '+') {
                salida.append(texto.charAt(i++));
                continue;
            }
            int fin = i;
            int digitos = 0;
            int ultimoDigito = i;
            while (fin < texto.length() && esParteDeNumero(texto.charAt(fin))) {
                if (Character.isDigit(texto.charAt(fin))) {
                    digitos++;
                    ultimoDigito = fin;
                }
                fin++;
            }
            if (digitos >= DIGITOS_IDENTIFICABLES) {
                salida.append(TELEFONO_OCULTO);
                i = ultimoDigito + 1;
            } else {
                salida.append(texto, i, ultimoDigito + 1);
                i = Math.max(ultimoDigito + 1, i + 1);
            }
        }
        return salida.toString();
    }

    private static boolean esParteDeNumero(char c) {
        return Character.isDigit(c) || c == ' ' || c == '-' || c == '+';
    }

    private void evento(long denunciaId, String tipo, EstadoDenuncia estado, String nota, long usuarioId, String rol) {
        jdbc.update("""
                INSERT INTO denuncia_evento (denuncia_id, tipo, estado, nota, usuario_id, rol)
                VALUES (?, ?, ?, ?, ?, ?)""", denunciaId, tipo, estado.name(), nota, usuarioId, rol);
    }

    private record Fila(EstadoDenuncia estado, boolean respondida) {
    }

    private Fila bloquear(String condicion, Object... args) {
        return jdbc.query("SELECT d.estado, d.respuesta_productor IS NOT NULL AS respondida FROM denuncia d WHERE "
                                + condicion + " FOR UPDATE",
                        (rs, row) -> new Fila(estado(rs), rs.getBoolean("respondida")), args)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("DENUNCIA_NO_ENCONTRADA", "La denuncia no existe."));
    }

    private static Referencia producto(ResultSet rs) throws SQLException {
        long id = rs.getLong("producto_id");
        return rs.wasNull() ? null : new Referencia(id, rs.getString("producto"));
    }

    private static EstadoDenuncia estado(ResultSet rs) throws SQLException {
        return EstadoDenuncia.valueOf(rs.getString("estado"));
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
