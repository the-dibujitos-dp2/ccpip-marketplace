package pe.ccpip.gobernanza;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-126 (RF-85): el subadministrador consulta sus propias acciones de gobernanza (Figma S-05). No
 * altera registros ni consulta acciones fuera de permiso. Transaccional: nada llega a otras pruebas.
 */
@Transactional
class MiHistorialTest extends IntegrationTest {

    private static final String SUB = "sub_admin@ccpip.test";
    private static final String HISTORIAL = "/api/admin/mi-historial";

    private Cookie subAdmin;
    private long miel;
    private long autorizacion;
    private long productor;

    @BeforeEach
    void preparar() throws Exception {
        subAdmin = loginAs(SUB);
        miel = productoConLote("Miel del historial " + UUID.randomUUID(), "PUBLICADO", "PUNTO_ACOPIO", hoy().plusMonths(6));
        autorizacion = jdbc.queryForObject("SELECT id FROM autorizacion_producto WHERE producto_id = ?", Long.class, miel);
        productor = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, miel);
    }

    @Test
    void reuneLasAccionesDeGobernanzaDeCadaModulo() throws Exception {
        String producto = jdbc.queryForObject("SELECT nombre FROM producto WHERE id = ?", String.class, miel);
        String organizacion = jdbc.queryForObject("SELECT coalesce(nombre_tienda, comunidad) FROM productor WHERE id = ?",
                String.class, productor);
        long consumidor = usuario("consumidor@ccpip.test");
        long denuncia = idDe(mockMvc.perform(post("/api/denuncias").cookie(loginAs("consumidor@ccpip.test"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("productoId", miel, "motivo", "ORIGEN_DUDOSO",
                        "descripcion", "La etiqueta no indica la comunidad de origen.")))));

        accion("/api/admin/denuncias/" + denuncia + "/estado", Map.of("estado", "EN_REVISION", "nota", "Pedimos fotos del lote"));
        accion("/api/admin/autorizaciones/" + autorizacion + "/renovaciones",
                Map.of("evidenciaId", certificado(), "motivo", "El certificado vence pronto"));
        accion("/api/admin/autorizaciones/" + autorizacion + "/revocar", Map.of("motivo", "Certificado observado por el emisor"));
        accion("/api/admin/productores/" + productor + "/bloquear", Map.of("motivo", "Denuncias reiteradas sin respuesta"));
        accion("/api/admin/cuentas/" + consumidor + "/suspender", Map.of("motivo", "Denuncias falsas reiteradas"));

        miHistorial("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(hoy().minusDays(29).toString()))
                .andExpect(jsonPath("$.hasta").value(hoy().toString()))
                .andExpect(jsonPath("$.acciones", hasSize(5)))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'DENUNCIA_EN_REVISION')].tipo").value(hasItem("DENUNCIAS")))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'DENUNCIA_EN_REVISION')].detalle").value(hasItem(String.valueOf(denuncia))))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'DENUNCIA_EN_REVISION')].motivo").value(hasItem("Pedimos fotos del lote")))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'RENOVACION_SOLICITADA')].detalle").value(hasItem(producto)))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'RENOVACION_SOLICITADA')].objetoId").value(hasItem((int) autorizacion)))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'AUTORIZACION_REVOCADA')].tipo").value(hasItem("AUTORIZACIONES")))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'AUTORIZACION_REVOCADA')].sobre").value(hasItem(organizacion)))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'AUTORIZACION_REVOCADA')].objeto").value(hasItem("AUTORIZACION")))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'BLOQUEAR_VENTA')].tipo").value(hasItem("PRODUCTORES")))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'BLOQUEAR_VENTA')].objetoId").value(hasItem((int) productor)))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'BLOQUEAR_VENTA')].motivo").value(hasItem("Denuncias reiteradas sin respuesta")))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'CUENTA_SUSPENDER')].tipo").value(hasItem("CUENTAS")))
                .andExpect(jsonPath("$.acciones[?(@.accion == 'CUENTA_SUSPENDER')].objetoId").value(hasItem((int) consumidor)));
    }

    @Test
    void soloMuestraLasAccionesPropiasDeLaMasRecienteALaMasAntigua() throws Exception {
        long yo = usuario(SUB);
        long otro = otroSubadministrador();
        decisionSobreProductor(yo, "Primera decisión propia", 3);
        decisionSobreProductor(yo, "Segunda decisión propia", 1);
        decisionSobreProductor(otro, "Decisión de otro subadministrador", 2);

        miHistorial("")
                .andExpect(jsonPath("$.acciones", hasSize(2)))
                .andExpect(jsonPath("$.acciones[0].motivo").value("Segunda decisión propia"))
                .andExpect(jsonPath("$.acciones[1].motivo").value("Primera decisión propia"))
                .andExpect(jsonPath("$.acciones[*].motivo", not(hasItem("Decisión de otro subadministrador"))));
        // El otro ve solo la suya
        mockMvc.perform(get(HISTORIAL).cookie(loginAs("otro_sub@ccpip.test")))
                .andExpect(jsonPath("$.acciones", hasSize(1)))
                .andExpect(jsonPath("$.acciones[0].motivo").value("Decisión de otro subadministrador"));
    }

    @Test
    void filtraPorPeriodoYPorTipoDeAccion() throws Exception {
        long yo = usuario(SUB);
        decisionSobreProductor(yo, "Hace 40 días", 40);
        accion("/api/admin/productores/" + productor + "/bloquear", Map.of("motivo", "Bloqueo de hoy"));
        accion("/api/admin/autorizaciones/" + autorizacion + "/revocar", Map.of("motivo", "Revocación de hoy"));

        // Por defecto, los ultimos 30 dias
        miHistorial("").andExpect(jsonPath("$.acciones[*].motivo", not(hasItem("Hace 40 días"))));
        miHistorial("?desde=" + hoy().minusDays(60))
                .andExpect(jsonPath("$.acciones[*].motivo", hasItem("Hace 40 días")));
        miHistorial("?tipo=PRODUCTORES")
                .andExpect(jsonPath("$.tipo").value("PRODUCTORES"))
                .andExpect(jsonPath("$.acciones", hasSize(1)))
                .andExpect(jsonPath("$.acciones[0].motivo").value("Bloqueo de hoy"));
        miHistorial("?tipo=AUTORIZACIONES")
                .andExpect(jsonPath("$.acciones[*].motivo", hasItem("Revocación de hoy")))
                .andExpect(jsonPath("$.acciones[*].motivo", not(hasItem("Bloqueo de hoy"))));
        miHistorial("?tipo=CUENTAS").andExpect(jsonPath("$.acciones", hasSize(0)));
    }

    @Test
    void elPeriodoSeValida() throws Exception {
        miHistorial("?desde=" + hoy() + "&hasta=" + hoy().minusDays(1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
        miHistorial("?desde=" + hoy().minusYears(2))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_MUY_LARGO"));
        miHistorial("?tipo=OTRO").andExpect(status().isBadRequest());
    }

    // --- Permisos (RNF-04, RNF-05) ---

    @ParameterizedTest
    @ValueSource(strings = {"sudo@ccpip.test", "productor@ccpip.test", "consumidor@ccpip.test", "admin_acopio@ccpip.test"})
    void esSoloDelSubadministrador(String correo) throws Exception {
        mockMvc.perform(get(HISTORIAL).cookie(loginAs(correo))).andExpect(status().isForbidden());
    }

    @Test
    void sinSesionPideIngresar() throws Exception {
        mockMvc.perform(get(HISTORIAL)).andExpect(status().isUnauthorized());
    }

    // --- Apoyo ---

    private ResultActions miHistorial(String consulta) throws Exception {
        return mockMvc.perform(get(HISTORIAL + consulta).cookie(subAdmin));
    }

    private void accion(String ruta, Map<String, Object> cuerpo) throws Exception {
        int estado = mockMvc.perform(post(ruta).cookie(subAdmin).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)))
                .andReturn().getResponse().getStatus();
        if (estado >= 300) {
            throw new AssertionError(ruta + " respondio " + estado);
        }
    }

    /** Una decision sobre el productor tomada hace {@code dias} dias (la tabla es de solo insercion). */
    private void decisionSobreProductor(long autor, String motivo, int dias) {
        jdbc.update("""
                INSERT INTO productor_decision (productor_id, decision, motivo, decidido_por, decidido_en)
                VALUES (?, 'HABILITAR_TIENDA', ?, ?, now() - make_interval(days => ?))""", productor, motivo, autor, dias);
    }

    private long otroSubadministrador() {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'otro_sub@ccpip.test', contrasena_hash, 'Otro Subadministrador', rol_id, true
                FROM usuario WHERE correo = ?""", SUB);
        return usuario("otro_sub@ccpip.test");
    }

    private UUID certificado() {
        return jdbc.queryForObject("""
                INSERT INTO evidencia_producto (autorizacion_id, productor_id, producto_id, tipo, titulo, emisor,
                                                vigente_desde, vigente_hasta, nombre_archivo, content_type, bytes, clave,
                                                subida_por)
                SELECT a.id, a.productor_id, a.producto_id, 'CERTIFICADO', 'Certificado orgánico', 'SENASA',
                       ?::date - 365, ?::date + 20, 'cert.pdf', 'application/pdf', 10, 'evidencias/' || gen_random_uuid(),
                       (SELECT usuario_id FROM productor WHERE id = a.productor_id)
                FROM autorizacion_producto a WHERE a.id = ?
                RETURNING id""", UUID.class, hoy(), hoy(), autorizacion);
    }

    private long idDe(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asLong();
    }

    private long usuario(String correo) {
        return jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, correo);
    }

    private static LocalDate hoy() {
        return LocalDate.now(ZoneId.of("America/Lima"));
    }
}
