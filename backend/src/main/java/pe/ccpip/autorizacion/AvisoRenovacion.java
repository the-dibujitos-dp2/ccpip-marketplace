package pe.ccpip.autorizacion;

import java.time.format.DateTimeFormatter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import pe.ccpip.autorizacion.RenovacionService.Renovacion;
import pe.ccpip.aviso.Avisos;
import pe.ccpip.aviso.Correo;

/**
 * CCPIP-321: la alerta al productor cuando la Camara le pide renovar un certificado. Mismo canal que las
 * decisiones (AvisoAutorizacion): correo en su idioma, registrado en {@code aviso}, y la solicitud visible
 * en las evidencias de su producto (PW-14). Se llama con la solicitud ya guardada: si el correo falla, la
 * solicitud no se deshace.
 */
@Component
public class AvisoRenovacion {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final Avisos avisos;

    public AvisoRenovacion(JdbcTemplate jdbc, Avisos avisos) {
        this.jdbc = jdbc;
        this.avisos = avisos;
    }

    private record Destinatario(long usuarioId, String correo, String nombre, boolean ingles) {
    }

    /** Devuelve false si el aviso no salio (o el productor no tiene usuario). */
    public boolean notificar(Renovacion r) {
        Destinatario d = jdbc.query("""
                        SELECT u.id, u.correo, coalesce(p.nombre_tienda, p.comunidad), u.idioma
                        FROM autorizacion_producto a JOIN productor p ON p.id = a.productor_id
                        JOIN usuario u ON u.id = p.usuario_id WHERE a.id = ?""",
                        (rs, row) -> new Destinatario(rs.getLong(1), rs.getString(2), rs.getString(3),
                                Avisos.ingles(rs.getString(4))),
                        r.autorizacionId())
                .stream().findFirst().orElse(null);
        if (d == null) {
            return false;
        }
        return avisos.registrar(d.usuarioId(), Avisos.Tipo.RENOVACION_CERTIFICADO, r.id())
                .map(aviso -> avisos.enviar(aviso, correo(d.correo(), d.nombre(), d.ingles(), r)))
                .orElse(false);
    }

    static Correo correo(String para, String nombre, boolean ingles, Renovacion r) {
        String certificado = r.certificado().titulo();
        String vence = r.certificado().vigenteHasta() == null ? "" : FECHA.format(r.certificado().vigenteHasta());
        String plazo = FECHA.format(r.plazo());
        if (ingles) {
            return new Correo(para, "Please renew the certificate of " + r.producto() + " · Mercado CCPIP", """
                    Hello, %s:

                    The Chamber asks you to renew the certificate "%s" of %s (valid until %s) by %s.
                    Your product remains on sale. If the certificate expires without being renewed, its sale is paused
                    until a new one is approved; it is not revoked.

                    Reason: %s

                    Chamber of Commerce of the Indigenous Peoples of Peru
                    """.formatted(nombre, certificado, r.producto(), vence, plazo, r.motivo()));
        }
        return new Correo(para, "Renueva el certificado de " + r.producto() + " · Mercado CCPIP", """
                Hola, %s:

                La Cámara te pide renovar el certificado "%s" de %s (vigente hasta el %s) antes del %s.
                Tu producto sigue a la venta. Si el certificado vence sin renovarse, su venta queda pausada
                hasta que se apruebe uno nuevo; no se revoca.

                Motivo: %s

                Cámara de Comercio de los Pueblos Indígenas del Perú
                """.formatted(nombre, certificado, r.producto(), vence, plazo, r.motivo()));
    }
}
