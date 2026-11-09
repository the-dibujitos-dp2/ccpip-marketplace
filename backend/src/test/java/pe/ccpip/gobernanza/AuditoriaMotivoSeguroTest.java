package pe.ccpip.gobernanza;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuditoriaMotivoSeguroTest {
    private String seguro(String motivo) throws Exception {
        Method method = AuditoriaService.class.getDeclaredMethod("motivoSeguro", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, motivo);
    }

    @Test
    void enmascaraContactosYConservaTextoYFormato() throws Exception {
        assertEquals("Decisión aprobada [oculto] y [oculto]", seguro("  Decisión aprobada ana.perez+1@ejemplo.com y +51 987-654-321.  "));
        assertEquals("[oculto] [oculto]", seguro("uno@ejemplo.com dos@dominio.pe"));
        assertEquals("Texto normal: sí.", seguro("  Texto normal: sí.  "));
        assertEquals("", seguro("  \n "));
        assertEquals(null, seguro(null));
    }

    @Test
    void manejaSeparadoresPrefijoYRuidoSinMascararTexto() throws Exception {
        assertEquals("[oculto]ruido", seguro("1234567 1.234.567 +1-234-567 ruido"));
    }

    @Test
    void conservaPuntuacionYRedactaCoincidenciasParcialesYCorreosConsecutivos() throws Exception {
        assertEquals("[oculto].", seguro("ana@ejemplo.com."));
        assertEquals("[oculto]123", seguro("ana@ejemplo.com123"));
        assertEquals("prefijo@[oculto]", seguro("prefijo@ana@ejemplo.com"));
        assertEquals("[oculto][oculto]", seguro("ana@ejemplo.com-otra@dominio.pe"));
        assertEquals("a@.com", seguro("a@.com"));
        assertEquals("[oculto]", seguro("a@..com"));
    }

    @Test
    void procesaContactosGrandesYCandidatosInvalidosEnTiempoAcotado() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            String digits = "7".repeat(20_000);
            String malformedEmail = "a".repeat(20_000) + "!";
            String result = seguro(digits + " " + malformedEmail);
            // The legacy phone mask consumes its optional trailing separator as well.
            assertEquals("[oculto]" + malformedEmail, result);
            assertEquals("[oculto]", seguro("a".repeat(20_000) + "@ejemplo.com"));
            String invalidDomain = "a@" + "b".repeat(20_000) + "!";
            assertEquals(invalidDomain, seguro(invalidDomain));
        });
    }
}
