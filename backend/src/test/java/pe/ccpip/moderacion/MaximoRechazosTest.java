package pe.ccpip.moderacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-493: al tercer rechazo el productor ya no puede volver a enviar la oferta; solo el subadministrador
 * habilita un nuevo intento, con motivo y auditado, y la cuenta vuelve a empezar.
 */
@Transactional
class MaximoRechazosTest extends IntegrationTest {

    private static final LocalDate EN_UN_ANIO = LocalDate.now(ZoneId.of("America/Lima")).plusYears(1);

    private Cookie productor;
    private Cookie subAdmin;
    private long oferta;

    @BeforeEach
    void preparar() throws Exception {
        productor = loginAs("productor@ccpip.test");
        subAdmin = loginAs("sub_admin@ccpip.test");
        oferta = productoConLote("Miel de abeja melipona LIM", "RETIRADO", "PUNTO_ACOPIO", EN_UN_ANIO);
    }

    @Test
    void alTercerRechazoNoSePuedeVolverAEnviar() throws Exception {
        rechazar(publicar(), "Primera");
        rechazar(publicar(), "Segunda");
        mockMvc.perform(get("/api/productor/productos/{id}", oferta).cookie(productor))
                .andExpect(jsonPath("$.moderacion.rechazos").value(2))
                .andExpect(jsonPath("$.moderacion.maximoRechazos").value(3))
                .andExpect(jsonPath("$.moderacion.bloqueada").value(false));
        long tercera = publicar();
        rechazar(tercera, "Tercera");

        mockMvc.perform(post("/api/productor/productos/{id}/publicar", oferta).cookie(productor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAXIMO_RECHAZOS"));
        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, oferta)).isEqualTo("RETIRADO");
        mockMvc.perform(get("/api/productor/productos/{id}", oferta).cookie(productor))
                .andExpect(jsonPath("$.moderacion.rechazos").value(3))
                .andExpect(jsonPath("$.moderacion.bloqueada").value(true));
        mockMvc.perform(get("/api/admin/moderacion/{id}", tercera).cookie(subAdmin))
                .andExpect(jsonPath("$.limite.rechazos").value(3))
                .andExpect(jsonPath("$.limite.bloqueada").value(true))
                .andExpect(jsonPath("$.limite.puedeHabilitar").value(true))
                .andExpect(jsonPath("$.limite.reintentos.length()").value(0));
    }

    @Test
    void elSubadministradorHabilitaUnNuevoIntentoConMotivoYLaCuentaEmpiezaDeNuevo() throws Exception {
        long primera = publicar();
        rechazar(primera, "Primera");
        // Antes del maximo no hay nada que habilitar
        reintento(primera, "Que lo intente").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REINTENTO_NO_NECESARIO"));
        long segunda = publicar();
        rechazar(segunda, "Segunda");
        long tercera = publicar();
        rechazar(tercera, "Tercera");

        reintento(tercera, "  ").andExpect(status().isBadRequest());
        // Solo desde la ultima solicitud de la oferta
        reintento(primera, "Desde una vieja").andExpect(status().isConflict());
        mockMvc.perform(post("/api/admin/moderacion/{id}/reintento", tercera).cookie(loginAs("sudo@ccpip.test"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Conforme\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/moderacion/{id}/reintento", tercera).cookie(productor)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Conforme\"}"))
                .andExpect(status().isForbidden());

        reintento(tercera, "El productor cambió las fotos por teléfono.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limite.rechazos").value(0))
                .andExpect(jsonPath("$.limite.bloqueada").value(false))
                .andExpect(jsonPath("$.limite.puedeHabilitar").value(false))
                .andExpect(jsonPath("$.limite.reintentos[0].motivo").value("El productor cambió las fotos por teléfono."))
                .andExpect(jsonPath("$.limite.reintentos[0].habilitadoPor").isNotEmpty());
        reintento(tercera, "Otra vez").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("""
                SELECT cambios->>'motivo' FROM producto_auditoria WHERE producto_id = ? ORDER BY id DESC LIMIT 1""",
                String.class, oferta)).isEqualTo("El productor cambió las fotos por teléfono.");

        // Puede volver a enviarla y otra vez tiene 3 intentos
        long cuarta = publicar();
        rechazar(cuarta, "Cuarta");
        mockMvc.perform(get("/api/productor/productos/{id}", oferta).cookie(productor))
                .andExpect(jsonPath("$.moderacion.rechazos").value(1))
                .andExpect(jsonPath("$.moderacion.bloqueada").value(false));
        rechazar(publicar(), "Quinta");
        rechazar(publicar(), "Sexta");
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", oferta).cookie(productor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAXIMO_RECHAZOS"));
        // El intento habilitado es constancia: no se borra (al final: el error aborta la transaccion de la prueba)
        assertThatThrownBy(() -> jdbc.update("DELETE FROM reintento_publicacion WHERE producto_id = ?", oferta))
                .hasMessageContaining("CCPIP-493");
    }

    private void rechazar(long solicitud, String motivo) throws Exception {
        mockMvc.perform(post("/api/admin/moderacion/{id}/rechazar", solicitud).cookie(subAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("motivo", motivo))))
                .andExpect(status().isOk());
    }

    private ResultActions reintento(long solicitud, String motivo) throws Exception {
        return mockMvc.perform(post("/api/admin/moderacion/{id}/reintento", solicitud).cookie(subAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("motivo", motivo))));
    }

    private long publicar() throws Exception {
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", oferta).cookie(productor)).andExpect(status().isOk());
        return jdbc.queryForObject("SELECT id FROM moderacion_publicacion WHERE producto_id = ? AND decision IS NULL",
                Long.class, oferta);
    }
}
