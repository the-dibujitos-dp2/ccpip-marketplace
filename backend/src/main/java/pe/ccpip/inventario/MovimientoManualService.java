package pe.ccpip.inventario;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.PuntosDeAcopio;
import pe.ccpip.inventario.InventarioModelos.DatosMovimiento;
import pe.ccpip.inventario.InventarioModelos.Lugar;
import pe.ccpip.inventario.InventarioModelos.Motivo;
import pe.ccpip.inventario.InventarioModelos.MovimientoRegistrado;

/**
 * Entradas y salidas manuales de existencias en un lote:
 *
 * <ul>
 *   <li>CCPIP-121 (RF-59) y CCPIP-287 (RF-208), Figma A-02 y A-03: el administrador en los puntos que
 *       tiene en usuario_ubicacion. Un lote de otro punto no existe para el (404, RNF-05).</li>
 *   <li>CCPIP-283 (RF-203), Figma PW-16: el productor en su almacen virtual unico. Un almacen por
 *       productor en el MVP; los lotes de sus productos que estan en un punto no los mueve el.</li>
 * </ul>
 *
 * Lote, stock y movimiento cambian en la misma transaccion (RNF-15, ADR-10): ninguna existencia
 * cambia sin su movimiento, que guarda producto, lote, cantidad, motivo, responsable y fecha.
 * El lote se bloquea con FOR UPDATE, asi dos salidas simultaneas se ordenan y ninguna deja el lote
 * en negativo (RNF-09). La clave del envio hace idempotente el reintento: sin doble conteo.
 */
@Service
public class MovimientoManualService {

    static final ZoneId LIMA = ZoneId.of("America/Lima");

    /** El lote ya bloqueado, con lo que hace falta para decidir. */
    private record Lote(long id, String codigo, long productoId, long ubicacionId, BigDecimal cantidad, LocalDate vence) {
    }

    /** Un movimiento ya registrado con la misma clave. */
    private record Previo(long id, String tipo, long loteId, BigDecimal cantidad, String motivo) {
    }

    private final JdbcTemplate jdbc;
    private final PuntosDeAcopio puntos;
    private final MovimientosService movimientos;

    public MovimientoManualService(JdbcTemplate jdbc, PuntosDeAcopio puntos, MovimientosService movimientos) {
        this.jdbc = jdbc;
        this.puntos = puntos;
        this.movimientos = movimientos;
    }

    /** CCPIP-121 y CCPIP-287: entrada o salida en un lote de los puntos del administrador. */
    @Transactional
    public MovimientoRegistrado registrarEnPunto(long usuarioId, DatosMovimiento d) {
        puntos.nombres(usuarioId);
        return registrar(usuarioId, Lugar.PUNTO_ACOPIO,
                "l.ubicacion_id IN (SELECT ubicacion_id FROM usuario_ubicacion WHERE usuario_id = ?)", usuarioId, d);
    }

    /** CCPIP-283: entrada o salida en un lote de su almacen, de un producto suyo. */
    @Transactional
    public MovimientoRegistrado registrarEnAlmacen(long productorId, long usuarioId, DatosMovimiento d) {
        return registrar(usuarioId, Lugar.ALMACEN_PRODUCTOR,
                "u.productor_id = ? AND p.productor_id = u.productor_id", productorId, d);
    }

    private MovimientoRegistrado registrar(long usuarioId, Lugar lugar, String alcance, long alcanceId, DatosMovimiento d) {
        Motivo motivo = motivo(d, lugar);
        Lote lote = bloquear(lugar, alcance, alcanceId, d.loteId());

        // Despues del bloqueo: un reintento simultaneo con la misma clave ya ve el movimiento del primero
        Optional<Previo> previo = previo(usuarioId, d.clave());
        if (previo.isPresent()) {
            return repetido(previo.get(), d, motivo, lote);
        }

        BigDecimal cantidad = d.cantidad();
        BigDecimal saldo;
        if ("ENTRADA".equals(motivo.tipo())) {
            if (lote.vence() != null && lote.vence().isBefore(LocalDate.now(LIMA))) {
                throw new ErrorNegocio(HttpStatus.CONFLICT, "LOTE_VENCIDO",
                        "El lote " + lote.codigo() + " está vencido: no puede recibir más unidades.");
            }
            // Lo ingresado crece con la entrada: el saldo nunca supera lo que entro al lote (RNF-09)
            jdbc.update("UPDATE lote SET cantidad_inicial = cantidad_inicial + ?, cantidad_actual = cantidad_actual + ? WHERE id = ?",
                    cantidad, cantidad, lote.id());
            jdbc.update("""
                    INSERT INTO stock (producto_id, ubicacion_id, cantidad) VALUES (?, ?, ?)
                    ON CONFLICT (producto_id, ubicacion_id)
                    DO UPDATE SET cantidad = stock.cantidad + EXCLUDED.cantidad, actualizado_en = now()""",
                    lote.productoId(), lote.ubicacionId(), cantidad);
            saldo = lote.cantidad().add(cantidad);
        } else {
            // RNF-09: la salida nunca deja el lote ni el stock por debajo de cero
            if (lote.cantidad().compareTo(cantidad) < 0
                    || jdbc.update("UPDATE lote SET cantidad_actual = cantidad_actual - ? WHERE id = ? AND cantidad_actual >= ?",
                            cantidad, lote.id(), cantidad) == 0
                    || jdbc.update("""
                            UPDATE stock SET cantidad = cantidad - ?, actualizado_en = now()
                            WHERE producto_id = ? AND ubicacion_id = ? AND cantidad >= ?""",
                            cantidad, lote.productoId(), lote.ubicacionId(), cantidad) == 0) {
                throw new ErrorNegocio(HttpStatus.CONFLICT, "SALDO_INSUFICIENTE",
                        "El lote " + lote.codigo() + " tiene " + lote.cantidad().stripTrailingZeros().toPlainString()
                                + " unidades: no puede salir más de eso.");
            }
            saldo = lote.cantidad().subtract(cantidad);
        }

        String observacion = d.observacion() == null || d.observacion().isBlank() ? null : d.observacion().trim();
        Long id;
        try {
            id = jdbc.queryForObject("""
                    INSERT INTO movimiento_inventario (producto_id, ubicacion_id, lote_id, tipo, cantidad, motivo,
                                                       usuario_id, observacion, clave_idempotencia)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                    lote.productoId(), lote.ubicacionId(), lote.id(), motivo.tipo(), cantidad, motivo.name(), usuarioId,
                    observacion, d.clave());
        } catch (DuplicateKeyException e) {
            // La misma clave en otro lote, en paralelo: el indice unico la frena
            throw envioReutilizado();
        }
        return new MovimientoRegistrado(movimientos.obtener(id), saldo, false);
    }

    /** El tipo y el motivo deben existir y corresponder al lugar: el punto no "produce" ni el productor "recibe". */
    private static Motivo motivo(DatosMovimiento d, Lugar lugar) {
        String tipo = d.tipo().trim().toUpperCase(Locale.ROOT);
        if (!"ENTRADA".equals(tipo) && !"SALIDA".equals(tipo)) {
            throw ErrorNegocio.invalido("TIPO_INVALIDO", "El movimiento es una entrada o una salida.");
        }
        Motivo motivo;
        try {
            motivo = Motivo.valueOf(d.motivo().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ErrorNegocio.invalido("MOTIVO_INVALIDO", "Elige un motivo de la lista.");
        }
        if (!motivo.tipo().equals(tipo) || motivo.lugar() != lugar) {
            throw ErrorNegocio.invalido("MOTIVO_INVALIDO", "Ese motivo no corresponde a este movimiento.");
        }
        return motivo;
    }

    /** Busca el lote dentro del alcance de quien registra y lo bloquea hasta el fin de la transaccion. */
    private Lote bloquear(Lugar lugar, String alcance, long alcanceId, long loteId) {
        return jdbc.query("""
                SELECT l.id, l.codigo, l.producto_id, l.ubicacion_id, l.cantidad_actual, l.fecha_vencimiento
                FROM lote l
                JOIN ubicacion u ON u.id = l.ubicacion_id
                JOIN producto p ON p.id = l.producto_id
                WHERE l.id = ? AND u.tipo = ? AND %s
                FOR UPDATE OF l""".formatted(alcance),
                (rs, row) -> new Lote(rs.getLong("id"), rs.getString("codigo"), rs.getLong("producto_id"),
                        rs.getLong("ubicacion_id"), rs.getBigDecimal("cantidad_actual"),
                        rs.getObject("fecha_vencimiento", LocalDate.class)),
                loteId, lugar.name(), alcanceId)
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("LOTE_NO_ENCONTRADO", lugar == Lugar.PUNTO_ACOPIO
                        ? "El lote no existe en tu punto de acopio." : "El lote no existe en tu almacén."));
    }

    private Optional<Previo> previo(long usuarioId, UUID clave) {
        return jdbc.query("""
                SELECT id, tipo, lote_id, cantidad, motivo FROM movimiento_inventario
                WHERE usuario_id = ? AND clave_idempotencia = ?""",
                (rs, row) -> new Previo(rs.getLong("id"), rs.getString("tipo"), rs.getLong("lote_id"),
                        rs.getBigDecimal("cantidad"), rs.getString("motivo")), usuarioId, clave)
                .stream().findFirst();
    }

    /** El mismo envio otra vez: devuelve lo ya registrado sin mover nada. Otro contenido con la misma clave es un error. */
    private MovimientoRegistrado repetido(Previo previo, DatosMovimiento d, Motivo motivo, Lote lote) {
        boolean mismo = previo.loteId() == lote.id() && previo.tipo().equals(motivo.tipo())
                && previo.motivo().equals(motivo.name()) && previo.cantidad().compareTo(d.cantidad()) == 0;
        if (!mismo) {
            throw envioReutilizado();
        }
        return new MovimientoRegistrado(movimientos.obtener(previo.id()), lote.cantidad(), true);
    }

    private static ErrorNegocio envioReutilizado() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "ENVIO_REUTILIZADO",
                "Este envío ya se usó para otro movimiento. Vuelve a abrir el formulario.");
    }
}
