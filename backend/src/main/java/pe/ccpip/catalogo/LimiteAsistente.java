package pe.ccpip.catalogo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import pe.ccpip.comun.ErrorNegocio;

/**
 * El asistente es publico y cada pregunta consume la cuota de la IA: se limita por persona (IP) y
 * en total dentro de una ventana de un minuto. Vive en memoria: hay un solo backend (ADR de despliegue).
 */
@Component
public class LimiteAsistente {

    static final Duration VENTANA = Duration.ofMinutes(1);

    private final int porPersona;
    private final int total;
    private final Clock reloj;
    private final Map<String, Deque<Instant>> porIp = new ConcurrentHashMap<>();
    private final Deque<Instant> todas = new ArrayDeque<>();

    @Autowired
    public LimiteAsistente(@Value("${ccpip.asistente.por-persona-por-minuto:8}") int porPersona,
                           @Value("${ccpip.asistente.total-por-minuto:60}") int total) {
        this(porPersona, total, Clock.systemUTC());
    }

    LimiteAsistente(int porPersona, int total, Clock reloj) {
        this.porPersona = porPersona;
        this.total = total;
        this.reloj = reloj;
    }

    /** Registra la pregunta o la rechaza con 429 si se supero algun limite. */
    public void registrar(String ip) {
        Instant ahora = reloj.instant();
        Instant desde = ahora.minus(VENTANA);
        synchronized (todas) {
            podar(todas, desde);
            Deque<Instant> propias = porIp.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
            podar(propias, desde);
            if (propias.size() >= porPersona) {
                throw new ErrorNegocio(HttpStatus.TOO_MANY_REQUESTS, "ASISTENTE_LIMITE",
                        "Hiciste muchas preguntas seguidas. Espera un minuto o usa el buscador.");
            }
            if (todas.size() >= total) {
                throw new ErrorNegocio(HttpStatus.TOO_MANY_REQUESTS, "ASISTENTE_OCUPADO",
                        "El asistente está atendiendo muchas preguntas. Intenta en un minuto o usa el buscador.");
            }
            propias.addLast(ahora);
            todas.addLast(ahora);
            // Las IP sin preguntas recientes no se guardan
            porIp.values().removeIf(d -> d.isEmpty() || d.peekLast().isBefore(desde));
        }
    }

    private static void podar(Deque<Instant> marcas, Instant desde) {
        while (!marcas.isEmpty() && marcas.peekFirst().isBefore(desde)) {
            marcas.removeFirst();
        }
    }
}
