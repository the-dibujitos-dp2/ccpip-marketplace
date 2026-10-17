package pe.ccpip.ia;

import java.text.Normalizer;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CCPIP-50: analista SIMULADO para pruebas automatizadas y demo. NO es una IA ni lee el contenido
 * de los archivos: aplica reglas fijas sobre lo que el productor declaro (tipo, titulo, emisor y
 * vigencia) para probar la pantalla de punta a punta hasta conectar el proveedor definitivo. Su
 * resultado queda marcado como simulado en la base y en la pantalla, y nunca se configura en el
 * ambiente desplegado como si fuera un analisis real.
 */
public class AnalistaSimulado implements AnalistaEvidencias {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    public Analisis analizar(Solicitud s) {
        List<Hallazgo> hallazgos = new ArrayList<>();
        Set<String> delProducto = palabras(s.producto());
        boolean certificadoVigente = false;
        for (Evidencia e : s.evidencias()) {
            boolean esCertificado = "CERTIFICADO".equals(e.tipo());
            boolean mencionaProducto = delProducto.stream().anyMatch(palabras(e.titulo())::contains);
            if (e.vigenteHasta() != null && e.vigenteHasta().isBefore(s.hoy())) {
                hallazgos.add(new Hallazgo(e.id(), TipoHallazgo.INCONSISTENCIA,
                        "Venció el " + FECHA.format(e.vigenteHasta()) + ": ya no sustenta la solicitud."));
            } else if (e.vigenteDesde() != null && e.vigenteDesde().isAfter(s.hoy())) {
                hallazgos.add(new Hallazgo(e.id(), TipoHallazgo.INCONSISTENCIA,
                        "Recién rige desde el " + FECHA.format(e.vigenteDesde()) + "."));
            } else if (esCertificado && !mencionaProducto) {
                hallazgos.add(new Hallazgo(e.id(), TipoHallazgo.INCONSISTENCIA,
                        "El título no menciona «" + s.producto() + "»: confirma que el certificado es de este producto."));
            } else if (esCertificado && (e.emisor() == null || e.emisor().isBlank())) {
                certificadoVigente = true;
                hallazgos.add(new Hallazgo(e.id(), TipoHallazgo.INCONSISTENCIA, "No indica quién emite el certificado."));
            } else {
                certificadoVigente |= esCertificado;
                hallazgos.add(new Hallazgo(e.id(), TipoHallazgo.CONFORME, e.vigenteHasta() == null
                        ? "Datos declarados coherentes; no indica vencimiento."
                        : "Vigente hasta el " + FECHA.format(e.vigenteHasta()) + "; datos declarados coherentes."));
            }
        }
        if (!certificadoVigente) {
            hallazgos.add(new Hallazgo(null, TipoHallazgo.FALTANTE, "Falta un certificado vigente que mencione el producto."));
        }
        if (s.evidencias().stream().noneMatch(e -> "EVIDENCIA".equals(e.tipo()))) {
            hallazgos.add(new Hallazgo(null, TipoHallazgo.FALTANTE, "Faltan fotos o registros del proceso de elaboración."));
        }
        long observaciones = hallazgos.stream().filter(h -> h.tipo() != TipoHallazgo.CONFORME).count();
        Recomendacion recomendacion = observaciones == 0 ? Recomendacion.SIN_OBSERVACIONES : Recomendacion.REVISAR;
        int total = s.evidencias().size();
        String resumen = "Resultado de prueba del analista simulado, no es un análisis real: "
                + (observaciones == 0 ? "sin observaciones" : observaciones + (observaciones == 1 ? " observación" : " observaciones"))
                + " en " + total + (total == 1 ? " evidencia." : " evidencias.");
        return new Analisis(recomendacion, resumen, List.copyOf(hallazgos));
    }

    @Override
    public String proveedor() {
        return "simulado";
    }

    @Override
    public boolean simulado() {
        return true;
    }

    /** Palabras de 4 o mas letras, sin tildes ni mayusculas: "Café" y "cafe" coinciden. */
    static Set<String> palabras(String texto) {
        if (texto == null) {
            return Set.of();
        }
        String plano = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return Arrays.stream(plano.split("[^a-z0-9]+")).filter(p -> p.length() >= 4).collect(Collectors.toSet());
    }
}
