package pe.ccpip.ia;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Asistente de catalogo de prueba: sin IA, toma como terminos las palabras de la pregunta que no
 * son de relleno y cita todo lo que encontro la busqueda. Solo para pruebas y desarrollo.
 */
public class AsistenteCatalogoSimulado implements AsistenteCatalogo {

    private static final Set<String> RELLENO = Set.of("quiero", "busco", "buscar", "tienen", "tienes", "venden",
            "para", "algun", "alguna", "alguno", "algo", "como", "donde", "producto", "productos", "comprar", "regalo",
            "regalar", "favor", "precio", "menos", "soles", "hola", "necesito", "recomiendas", "recomienda", "esta",
            "este", "estos", "estas", "with", "want", "need", "some", "please", "something", "product", "products");

    @Override
    public Interpretacion interpretar(String pregunta, List<Opcion> lineas, List<Opcion> regiones) {
        List<String> terminos = new ArrayList<>();
        for (String palabra : pregunta.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            String llana = Normalizer.normalize(palabra, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            if (llana.length() >= 4 && !RELLENO.contains(llana) && terminos.size() < AsistenteCatalogoGemini.MAX_TERMINOS
                    && !terminos.contains(palabra)) {
                terminos.add(palabra);
            }
        }
        return new Interpretacion(List.copyOf(terminos), List.of(), List.of(), null, null, terminos.isEmpty());
    }

    @Override
    public Respuesta responder(String pregunta, List<Candidato> candidatos, boolean ingles) {
        String texto = ingles
                ? "Test answer: these catalog products match your question."
                : "Respuesta de prueba: estos productos del catálogo coinciden con tu pregunta.";
        return new Respuesta(texto, candidatos.stream().map(Candidato::id).toList());
    }

    @Override
    public String proveedor() {
        return "simulado";
    }
}
