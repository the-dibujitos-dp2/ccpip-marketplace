package pe.ccpip.alerta;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import pe.ccpip.alerta.AlertaModelos.Alerta;
import pe.ccpip.aviso.Avisos;
import pe.ccpip.aviso.Correo;
import pe.ccpip.aviso.EnvioCorreo;
import pe.ccpip.productor.ProductosService;

/**
 * Genera las alertas de PW-21 y A-08 con una revision periodica, como el aviso de oferta disponible
 * (CCPIP-273): cubre todas las causas sin engancharse en los flujos de venta, transferencia o merma.
 * Para que se vean en tiempo real (CCPIP-425), la bandeja y "Configurar alertas" las ponen al dia
 * con {@link #actualizar()} antes de responder; los correos salen con la revision periodica.
 *
 * <ul>
 *   <li>Idempotente: hay una sola alerta abierta por hecho y destino (indices unicos parciales).</li>
 *   <li>Una condicion que deja de cumplirse se resuelve (el lote se vendio, la oferta tiene stock otra
 *       vez); si vuelve a cumplirse, nace otra alerta.</li>
 *   <li>Los eventos (transferencia recibida, pedido nuevo) se buscan en una ventana reciente: al
 *       desplegar no se alertan meses de historia.</li>
 *   <li>Las alertas importantes salen por correo despues de confirmar, en un solo correo por persona y
 *       revision. Un correo caido no deshace nada: queda FALLIDO, con el motivo.</li>
 * </ul>
 */
@Component
public class RevisionAlertas {

    private static final Logger LOG = LoggerFactory.getLogger(RevisionAlertas.class);
    private static final int MAX_ERROR = 300;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String HOY = "(now() AT TIME ZONE 'America/Lima')::date";
    private static final String SALIDAS = "('SALIDA', 'SALIDA_VENTA', 'TRANSFERENCIA_SALIDA')";

    /** Dia de la ultima salida del lote o, si nunca tuvo una, de su ingreso. */
    private static final String DESDE = """
            (coalesce((SELECT max(m.registrado_en) FROM movimiento_inventario m
                       WHERE m.lote_id = l.id AND m.tipo IN %s), l.registrado_en) AT TIME ZONE 'America/Lima')::date"""
            .formatted(SALIDAS);

    /** Preferencia del destino de la alerta {@code a}: tiene productor o punto, nunca ambos. */
    private static final String PREFERENCIA_DE_A =
            "(SELECT pa.%s FROM preferencia_alerta pa WHERE pa.productor_id = a.productor_id OR pa.ubicacion_id = a.ubicacion_id)";

    private static final String EN_CONFLICTO_PRODUCTOR =
            "ON CONFLICT (tipo, productor_id, referencia_id) WHERE productor_id IS NOT NULL AND resuelta_en IS NULL DO NOTHING";
    private static final String EN_CONFLICTO_PUNTO =
            "ON CONFLICT (tipo, ubicacion_id, referencia_id) WHERE ubicacion_id IS NOT NULL AND resuelta_en IS NULL DO NOTHING";

    private static final String CORREO = "CASE WHEN coalesce(pa.correo, true) THEN 'PENDIENTE' ELSE 'NO_APLICA' END";

    /** Una revision a la vez: la periodica y las de cada consulta no se pisan al resolver ni al generar. */
    private static final String UNA_A_LA_VEZ = "SELECT pg_advisory_xact_lock(hashtext('ccpip.alertas'))";

    public record Resultado(int nuevas, int resueltas, int correos) {
    }

    public record Cambios(int nuevas, int resueltas) {
    }

    private record Destino(Long productorId, Long ubicacionId) {
    }

    private record Persona(String correo, String nombre, String idioma) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PreferenciasAlerta preferencias;
    private final AlertasService alertas;
    private final EnvioCorreo envio;
    private final long ventanaSegundos;
    private final String urlApp;

    public RevisionAlertas(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, PreferenciasAlerta preferencias,
                           AlertasService alertas, EnvioCorreo envio,
                           @Value("${ccpip.alertas.ventana-eventos:P2D}") Duration ventana,
                           @Value("${ccpip.app.url}") String urlApp) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.preferencias = preferencias;
        this.alertas = alertas;
        this.envio = envio;
        this.ventanaSegundos = ventana.toSeconds();
        this.urlApp = urlApp.endsWith("/") ? urlApp.substring(0, urlApp.length() - 1) : urlApp;
    }

    @Scheduled(fixedDelayString = "${ccpip.alertas.revision:PT1M}",
            initialDelayString = "${ccpip.alertas.revision-inicial:PT2M}")
    public void revisarProgramado() {
        try {
            Resultado r = revisar();
            if (r.nuevas() > 0 || r.resueltas() > 0) {
                LOG.info("Alertas: {} nuevas, {} resueltas, {} correos", r.nuevas(), r.resueltas(), r.correos());
            }
        } catch (RuntimeException e) {
            LOG.error("Fallo la revision de alertas: {}", e.getMessage(), e);
        }
    }

    public Resultado revisar() {
        Cambios cambios = actualizar();
        return new Resultado(cambios.nuevas(), cambios.resueltas(), enviarCorreos());
    }

    /** Resuelve y genera las alertas en la plataforma, sin enviar correos. Idempotente. */
    public Cambios actualizar() {
        return tx.execute(s -> {
            jdbc.getJdbcOperations().execute(UNA_A_LA_VEZ);
            MapSqlParameterSource p = preferencias.provisionales(new MapSqlParameterSource("ventana", ventanaSegundos));
            int resueltas = resolver(p);
            return new Cambios(generar(p), resueltas);
        });
    }

    /** Cierra las alertas cuya condicion ya no se cumple. Va antes de generar para que puedan renacer. */
    private int resolver(MapSqlParameterSource p) {
        int n = jdbc.update("""
                UPDATE alerta a SET resuelta_en = now() FROM lote l
                WHERE a.tipo = 'POR_VENCER' AND a.resuelta_en IS NULL AND l.id = a.referencia_id
                  AND (l.cantidad_actual = 0 OR l.fecha_vencimiento IS NULL OR l.fecha_vencimiento < %1$s
                       OR l.fecha_vencimiento > %1$s + coalesce(%2$s, :diasVencimientoProvisional))"""
                .formatted(HOY, PREFERENCIA_DE_A.formatted("dias_vencimiento")), p);
        n += jdbc.update("""
                UPDATE alerta a SET resuelta_en = now() FROM lote l
                WHERE a.tipo = 'ESTANCADO' AND a.resuelta_en IS NULL AND l.id = a.referencia_id
                  AND (l.cantidad_actual = 0 OR l.fecha_vencimiento < %1$s OR %2$s <> a.fecha
                       OR a.fecha > %1$s - coalesce(%3$s, :diasEstancadoProvisional))"""
                .formatted(HOY, DESDE, PREFERENCIA_DE_A.formatted("dias_estancado")), p);
        n += jdbc.update("""
                UPDATE alerta a SET resuelta_en = now() FROM producto p
                WHERE a.tipo = 'REPOSICION' AND a.resuelta_en IS NULL AND p.id = a.referencia_id
                  AND (p.estado <> 'PUBLICADO' OR %s > 0)""".formatted(ProductosService.EN_VENTA), p);
        // Un interes nuevo despues de leer la alerta merece otra: la anterior se cierra
        n += jdbc.update("""
                UPDATE alerta a SET resuelta_en = now()
                WHERE a.tipo = 'INTERES' AND a.resuelta_en IS NULL
                  AND (NOT EXISTS (SELECT 1 FROM interes_compra i WHERE i.producto_id = a.referencia_id
                                     AND i.estado = 'PENDIENTE')
                       OR (a.leida_en IS NOT NULL
                           AND EXISTS (SELECT 1 FROM interes_compra i WHERE i.producto_id = a.referencia_id
                                         AND i.estado = 'PENDIENTE' AND i.registrado_en > a.leida_en)))""", p);
        return n;
    }

    private int generar(MapSqlParameterSource p) {
        // CCPIP-79: lotes con saldo que vencen dentro de la anticipacion. Al productor, de todos sus lotes;
        // al punto, de los lotes que estan en el.
        int n = jdbc.update("""
                INSERT INTO alerta (tipo, productor_id, referencia_id, producto_id, fecha, correo)
                SELECT 'POR_VENCER', p.productor_id, l.id, p.id, l.fecha_vencimiento, %s
                FROM lote l JOIN producto p ON p.id = l.producto_id
                LEFT JOIN preferencia_alerta pa ON pa.productor_id = p.productor_id
                WHERE l.cantidad_actual > 0 AND %s
                  AND l.fecha_vencimiento BETWEEN %s AND %s + coalesce(pa.dias_vencimiento, :diasVencimientoProvisional)
                %s""".formatted(CORREO, activo("POR_VENCER"), HOY, HOY, EN_CONFLICTO_PRODUCTOR), p);
        n += jdbc.update("""
                INSERT INTO alerta (tipo, ubicacion_id, referencia_id, producto_id, fecha, correo)
                SELECT 'POR_VENCER', l.ubicacion_id, l.id, l.producto_id, l.fecha_vencimiento, %s
                FROM lote l JOIN ubicacion u ON u.id = l.ubicacion_id AND u.tipo = 'PUNTO_ACOPIO'
                LEFT JOIN preferencia_alerta pa ON pa.ubicacion_id = l.ubicacion_id
                WHERE l.cantidad_actual > 0 AND %s
                  AND l.fecha_vencimiento BETWEEN %s AND %s + coalesce(pa.dias_vencimiento, :diasVencimientoProvisional)
                %s""".formatted(CORREO, activo("POR_VENCER"), HOY, HOY, EN_CONFLICTO_PUNTO), p);

        // CCPIP-337: lotes vigentes con saldo en un punto (donde se venden) sin salidas en N dias
        String estancados = """
                SELECT l.id, l.producto_id, l.ubicacion_id, p.productor_id, %s AS desde
                FROM lote l JOIN producto p ON p.id = l.producto_id
                JOIN ubicacion u ON u.id = l.ubicacion_id AND u.tipo = 'PUNTO_ACOPIO'
                WHERE l.cantidad_actual > 0 AND (l.fecha_vencimiento IS NULL OR l.fecha_vencimiento >= %s)"""
                .formatted(DESDE, HOY);
        n += jdbc.update("""
                INSERT INTO alerta (tipo, productor_id, referencia_id, producto_id, fecha, correo)
                SELECT 'ESTANCADO', x.productor_id, x.id, x.producto_id, x.desde, %s
                FROM (%s) x LEFT JOIN preferencia_alerta pa ON pa.productor_id = x.productor_id
                WHERE %s AND x.desde <= %s - coalesce(pa.dias_estancado, :diasEstancadoProvisional)
                %s""".formatted(CORREO, estancados, activo("ESTANCADO"), HOY, EN_CONFLICTO_PRODUCTOR), p);
        n += jdbc.update("""
                INSERT INTO alerta (tipo, ubicacion_id, referencia_id, producto_id, fecha, correo)
                SELECT 'ESTANCADO', x.ubicacion_id, x.id, x.producto_id, x.desde, %s
                FROM (%s) x LEFT JOIN preferencia_alerta pa ON pa.ubicacion_id = x.ubicacion_id
                WHERE %s AND x.desde <= %s - coalesce(pa.dias_estancado, :diasEstancadoProvisional)
                %s""".formatted(CORREO, estancados, activo("ESTANCADO"), HOY, EN_CONFLICTO_PUNTO), p);

        // CCPIP-293: oferta publicada que tuvo stock en un punto y hoy no tiene nada en venta. La fecha es
        // su ultima salida o el dia despues de que vencio su ultimo lote, lo que paso despues.
        n += jdbc.update("""
                INSERT INTO alerta (tipo, productor_id, referencia_id, producto_id, fecha, correo)
                SELECT 'REPOSICION', p.productor_id, p.id, p.id, least(%1$s, coalesce(greatest(
                           (SELECT max(m.registrado_en AT TIME ZONE 'America/Lima')::date FROM movimiento_inventario m
                            JOIN ubicacion u ON u.id = m.ubicacion_id AND u.tipo = 'PUNTO_ACOPIO'
                            WHERE m.producto_id = p.id AND m.tipo IN %2$s),
                           (SELECT max(l.fecha_vencimiento) + 1 FROM lote l
                            JOIN ubicacion u ON u.id = l.ubicacion_id AND u.tipo = 'PUNTO_ACOPIO'
                            WHERE l.producto_id = p.id AND l.fecha_vencimiento < %1$s)), %1$s)), %3$s
                FROM producto p LEFT JOIN preferencia_alerta pa ON pa.productor_id = p.productor_id
                WHERE p.estado = 'PUBLICADO' AND %4$s AND %5$s = 0
                  AND EXISTS (SELECT 1 FROM lote l JOIN ubicacion u ON u.id = l.ubicacion_id AND u.tipo = 'PUNTO_ACOPIO'
                              WHERE l.producto_id = p.id)
                %6$s""".formatted(HOY, SALIDAS, CORREO, activo("REPOSICION"), ProductosService.EN_VENTA,
                EN_CONFLICTO_PRODUCTOR), p);

        // CCPIP-366: ofertas con interesados pendientes (CCPIP-105). Una alerta por oferta, no por persona
        n += jdbc.update("""
                INSERT INTO alerta (tipo, productor_id, referencia_id, producto_id, fecha, correo)
                SELECT 'INTERES', i.productor_id, i.producto_id, i.producto_id,
                       max(i.registrado_en AT TIME ZONE 'America/Lima')::date, %s
                FROM interes_compra i LEFT JOIN preferencia_alerta pa ON pa.productor_id = i.productor_id
                WHERE i.estado = 'PENDIENTE' AND %s
                GROUP BY i.productor_id, i.producto_id, pa.id
                %s""".formatted(CORREO, activo("INTERES"), EN_CONFLICTO_PRODUCTOR), p);

        // CCPIP-367: eventos operativos recientes. Solo en la plataforma, no por correo.
        n += jdbc.update("""
                INSERT INTO alerta (tipo, productor_id, referencia_id, fecha)
                SELECT 'TRANSFERENCIA_RECIBIDA', t.productor_id, t.id, (t.actualizada_en AT TIME ZONE 'America/Lima')::date
                FROM transferencia t LEFT JOIN preferencia_alerta pa ON pa.productor_id = t.productor_id
                WHERE t.estado = 'RECIBIDA' AND t.actualizada_en > now() - make_interval(secs => :ventana) AND %s
                %s""".formatted(activo("TRANSFERENCIA_RECIBIDA"), EN_CONFLICTO_PRODUCTOR), p);
        n += jdbc.update("""
                INSERT INTO alerta (tipo, productor_id, referencia_id, fecha)
                SELECT DISTINCT 'PEDIDO_NUEVO', i.productor_id, pe.id, (pe.confirmado_en AT TIME ZONE 'America/Lima')::date
                FROM pedido pe JOIN item_pedido i ON i.pedido_id = pe.id
                LEFT JOIN preferencia_alerta pa ON pa.productor_id = i.productor_id
                WHERE pe.confirmado_en IS NOT NULL AND pe.estado <> 'CANCELADO'
                  AND pe.confirmado_en > now() - make_interval(secs => :ventana) AND %s
                %s""".formatted(activo("PEDIDO_NUEVO"), EN_CONFLICTO_PRODUCTOR), p);
        return n;
    }

    /** El destino no silencio ese tipo (sin preferencia, recibe todos). */
    private static String activo(String tipo) {
        return "NOT coalesce('" + tipo + "' = ANY(pa.tipos_silenciados), false)";
    }

    /** Un correo por persona y destino con sus alertas importantes nuevas. Devuelve cuantos salieron. */
    private int enviarCorreos() {
        Map<Destino, List<Long>> porDestino = new LinkedHashMap<>();
        jdbc.query("SELECT id, productor_id, ubicacion_id FROM alerta WHERE correo = 'PENDIENTE' ORDER BY id",
                rs -> {
                    Destino d = new Destino((Long) rs.getObject("productor_id", Long.class),
                            (Long) rs.getObject("ubicacion_id", Long.class));
                    porDestino.computeIfAbsent(d, k -> new ArrayList<>()).add(rs.getLong("id"));
                });
        int enviados = 0;
        for (Map.Entry<Destino, List<Long>> e : porDestino.entrySet()) {
            enviados += enviar(e.getKey(), e.getValue());
        }
        return enviados;
    }

    private int enviar(Destino destino, List<Long> ids) {
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids);
        List<Persona> personas = destino.productorId() != null
                ? jdbc.query("""
                        SELECT u.correo, u.nombre, u.idioma FROM productor pr JOIN usuario u ON u.id = pr.usuario_id
                        WHERE pr.id = :destino AND u.activo""", p.addValue("destino", destino.productorId()),
                        (rs, row) -> new Persona(rs.getString("correo"), rs.getString("nombre"), rs.getString("idioma")))
                : jdbc.query("""
                        SELECT u.correo, u.nombre, u.idioma FROM usuario_ubicacion uu JOIN usuario u ON u.id = uu.usuario_id
                        WHERE uu.ubicacion_id = :destino AND u.activo ORDER BY u.id""",
                        p.addValue("destino", destino.ubicacionId()),
                        (rs, row) -> new Persona(rs.getString("correo"), rs.getString("nombre"), rs.getString("idioma")));
        if (personas.isEmpty()) {
            jdbc.update("UPDATE alerta SET correo = 'NO_APLICA' WHERE id IN (:ids)", p);
            return 0;
        }
        List<Alerta> detalle = alertas.conIds(ids);
        String ruta = destino.productorId() != null ? "/productor/alertas" : "/acopio/alertas";
        int enviados = 0;
        String error = null;
        for (Persona persona : personas) {
            try {
                envio.enviar(correo(persona, detalle, urlApp + ruta));
                enviados++;
            } catch (MailException ex) {
                LOG.error("No se pudo enviar el correo de alertas a {}: {}", persona.correo(), ex.getMessage());
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            }
        }
        if (error == null) {
            jdbc.update("UPDATE alerta SET correo = 'ENVIADO', correo_error = NULL WHERE id IN (:ids)", p);
        } else {
            jdbc.update("UPDATE alerta SET correo = 'FALLIDO', correo_error = :error WHERE id IN (:ids)",
                    p.addValue("error", error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error));
        }
        return enviados;
    }

    private static Correo correo(Persona persona, List<Alerta> detalle, String url) {
        boolean en = Avisos.ingles(persona.idioma());
        StringBuilder lineas = new StringBuilder();
        for (Alerta a : detalle) {
            lineas.append("- ").append(en ? lineaEn(a) : lineaEs(a)).append('\n');
        }
        if (en) {
            return new Correo(persona.correo(), "CCPIP Market: " + detalle.size() + " new alert(s)", """
                    Hello, %s:

                    You have new alerts:

                    %s
                    See them and change what you receive here:

                    %s

                    Chamber of Commerce of the Indigenous Peoples of Peru
                    """.formatted(persona.nombre(), lineas, url));
        }
        return new Correo(persona.correo(), "Mercado CCPIP: " + detalle.size() + " alerta(s) nueva(s)", """
                Hola, %s:

                Tienes alertas nuevas:

                %s
                Revísalas y elige qué recibir aquí:

                %s

                Cámara de Comercio de los Pueblos Indígenas del Perú
                """.formatted(persona.nombre(), lineas, url));
    }

    private static String lineaEs(Alerta a) {
        return switch (a.tipo()) {
            case POR_VENCER -> "Por vencer: %s, lote %s en %s (quedan %s), vence el %s."
                    .formatted(a.producto(), a.lote(), a.ubicacion(), numero(a.cantidad()), a.fecha().format(FECHA));
            case ESTANCADO -> "Lote estancado: %s, lote %s en %s (quedan %s), sin salidas desde el %s."
                    .formatted(a.producto(), a.lote(), a.ubicacion(), numero(a.cantidad()), a.fecha().format(FECHA));
            case REPOSICION -> "Sin stock en venta: %s, agotado desde el %s. Repón stock en un punto de acopio."
                    .formatted(a.producto(), a.fecha().format(FECHA));
            case INTERES -> "Interés de compradores: %s persona(s) quieren %s unidad(es) de %s."
                    .formatted(a.personas(), numero(a.cantidad()), a.producto());
            case TRANSFERENCIA_RECIBIDA -> "Transferencia %s recibida en %s el %s."
                    .formatted(a.referencia(), a.ubicacion(), a.fecha().format(FECHA));
            case PEDIDO_NUEVO -> "Pedido nuevo %s con %s, el %s."
                    .formatted(a.referencia(), String.join(", ", a.productos()), a.fecha().format(FECHA));
        };
    }

    private static String lineaEn(Alerta a) {
        return switch (a.tipo()) {
            case POR_VENCER -> "Expiring soon: %s, batch %s at %s (%s left), expires on %s."
                    .formatted(a.producto(), a.lote(), a.ubicacion(), numero(a.cantidad()), a.fecha().format(FECHA));
            case ESTANCADO -> "Stagnant batch: %s, batch %s at %s (%s left), no outflows since %s."
                    .formatted(a.producto(), a.lote(), a.ubicacion(), numero(a.cantidad()), a.fecha().format(FECHA));
            case REPOSICION -> "Out of stock: %s, sold out since %s. Restock it at a collection point."
                    .formatted(a.producto(), a.fecha().format(FECHA));
            case INTERES -> "Buyer interest: %s person(s) want %s unit(s) of %s."
                    .formatted(a.personas(), numero(a.cantidad()), a.producto());
            case TRANSFERENCIA_RECIBIDA -> "Transfer %s received at %s on %s."
                    .formatted(a.referencia(), a.ubicacion(), a.fecha().format(FECHA));
            case PEDIDO_NUEVO -> "New order %s with %s, on %s."
                    .formatted(a.referencia(), String.join(", ", a.productos()), a.fecha().format(FECHA));
        };
    }

    private static String numero(BigDecimal n) {
        return n == null ? "0" : n.stripTrailingZeros().toPlainString();
    }
}
