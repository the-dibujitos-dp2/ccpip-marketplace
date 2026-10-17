package pe.ccpip.carrito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-142 (RF-109, RNF-36): el comprador retoma su carrito persistido mientras siga vigente, con
 * precio y disponibilidad revalidados. La vigencia se cuenta desde el ultimo cambio del carrito y no
 * depende de la sesion; vencido, se vacia sin tocar el inventario porque nunca reservo (RNF-35).
 *
 * <p>Sin configuracion del superadministrador rige la provisional de application.properties (2 h).
 * Transaccional: la vigencia que se configura aqui no llega a las demas pruebas.
 */
@Transactional
class VigenciaCarritoTest extends IntegrationTest {

    private static final LocalDate EN_UN_MES = LocalDate.now(ZoneId.of("America/Lima")).plusDays(30);
    private static final String SUPER = "sudo@ccpip.test";

    @Autowired
    private VigenciaCarritoService vigencia;

    private Cookie consumidor;
    private long oferta;

    @BeforeEach
    void preparar() throws Exception {
        consumidor = loginAs("consumidor@ccpip.test");
        oferta = productoConLote("Sacha inchi tostado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
    }

    // --- Vigencia del carrito ---

    @Test
    void unCarritoVigenteSeRetomaRevalidadoYDiceHastaCuandoSeGuarda() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        envejecer("consumidor@ccpip.test", Duration.ofMinutes(100));
        // Mientras tanto el productor cambio el precio y otros compradores se llevaron stock
        jdbc.update("UPDATE producto SET precio = 22 WHERE id = ?", oferta);

        JsonNode carrito = leer(consultar(consumidor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].precio").value(22))
                .andExpect(jsonPath("$.items[0].subtotal").value(44))
                .andExpect(jsonPath("$.total").value(44))
                .andExpect(jsonPath("$.vigenciaHoras").value(2))
                .andExpect(jsonPath("$.vencidoEn").doesNotExist()));
        assertThat(Instant.parse(carrito.get("vigenteHasta").asText()))
                .isEqualTo(actualizadoEn("consumidor@ccpip.test").plus(Duration.ofHours(2)));

        jdbc.update("UPDATE lote SET cantidad_actual = 1 WHERE producto_id = ?", oferta);
        consultar(consumidor)
                .andExpect(jsonPath("$.items[0].estado").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.items[0].disponibles").value(1))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void unCarritoVencidoNoSeRetomaYSeVaciaSinTocarElInventario() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        envejecer("consumidor@ccpip.test", Duration.ofMinutes(121));

        consultar(consumidor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.grupos").isEmpty())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.vigenteHasta").doesNotExist())
                .andExpect(jsonPath("$.vencidoEn").isNotEmpty());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM item_carrito WHERE producto_id = ?", Integer.class, oferta))
                .isZero();
        // RNF-35: nada que devolver al inventario, porque estar en el carrito nunca reservo
        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id = ?", Integer.class, oferta))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE producto_id = ?",
                Integer.class, oferta)).isZero();
    }

    @Test
    void consultarNoRenuevaLaVigenciaPeroCambiarElCarritoSi() throws Exception {
        agregar(consumidor, oferta, 1).andExpect(status().isOk());
        envejecer("consumidor@ccpip.test", Duration.ofMinutes(90));
        Instant antes = actualizadoEn("consumidor@ccpip.test");

        consultar(consumidor).andExpect(jsonPath("$.items.length()").value(1));
        assertThat(actualizadoEn("consumidor@ccpip.test")).isEqualTo(antes);

        modificar(consumidor, oferta, 2).andExpect(status().isOk());
        assertThat(actualizadoEn("consumidor@ccpip.test")).isAfter(antes);
    }

    @Test
    void loDeUnCarritoVencidoNoSeSumaAlVolverAAgregar() throws Exception {
        agregar(consumidor, oferta, 4).andExpect(status().isOk());
        envejecer("consumidor@ccpip.test", Duration.ofHours(3));

        // Las 4 de antes ya no estan: queda solo lo nuevo, y el aviso de vencido se limpia
        agregar(consumidor, oferta, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(1))
                .andExpect(jsonPath("$.articulos").value(1));
        consultar(consumidor)
                .andExpect(jsonPath("$.items[0].cantidad").value(1))
                .andExpect(jsonPath("$.vencidoEn").doesNotExist())
                .andExpect(jsonPath("$.vigenteHasta").isNotEmpty());
    }

    @Test
    void unCarritoVencidoNoSeModificaNiSeQuitaDeEl() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        envejecer("consumidor@ccpip.test", Duration.ofHours(3));

        modificar(consumidor, oferta, 3)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NO_ENCONTRADO"));
        mockMvc.perform(delete("/api/carrito/items/{id}", oferta).cookie(consumidor))
                .andExpect(status().isNotFound());
    }

    @Test
    void laTareaProgramadaVaciaLosVencidosYRespetaLosVigentes() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        Cookie otro = loginAs(nuevoComprador("comprador.vigente@ccpip.test"));
        agregar(otro, oferta, 1).andExpect(status().isOk());
        envejecer("consumidor@ccpip.test", Duration.ofHours(5));
        envejecer("comprador.vigente@ccpip.test", Duration.ofMinutes(30));

        vigencia.vaciarVencidos();

        assertThat(lineas("consumidor@ccpip.test")).isZero();
        assertThat(lineas("comprador.vigente@ccpip.test")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT c.vencido_en IS NOT NULL FROM carrito c JOIN usuario u ON u.id = c.usuario_id
                WHERE u.correo = 'consumidor@ccpip.test'""", Boolean.class)).isTrue();
        // Al volver, el comprador ve que se vacio por vencer
        consultar(consumidor).andExpect(jsonPath("$.vencidoEn").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id = ?", Integer.class, oferta))
                .isEqualTo(5);
    }

    // --- El plazo no depende de la sesion ---

    @Test
    void elCarritoSigueVigenteAunqueLaSesionHayaExpiradoOSeHayaCerrado() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        // La sesion vence a los 30 minutos de inactividad; el carrito lleva 110 sin cambios, aun vigente
        envejecer("consumidor@ccpip.test", Duration.ofMinutes(110));
        jdbc.update("""
                UPDATE sesion SET ultimo_acceso = now() - interval '31 minutes'
                WHERE usuario_id = (SELECT id FROM usuario WHERE correo = 'consumidor@ccpip.test')""");
        consultar(consumidor).andExpect(status().isUnauthorized());

        Cookie nueva = loginAs("consumidor@ccpip.test");
        consultar(nueva)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].cantidad").value(2));
        // Iniciar sesion de nuevo no renueva el plazo del carrito
        mockMvc.perform(post("/api/auth/logout").cookie(nueva)).andExpect(status().is2xxSuccessful());
        assertThat(actualizadoEn("consumidor@ccpip.test"))
                .isBefore(Instant.now().minus(Duration.ofMinutes(100)));
    }

    @Test
    void unaSesionActivaNoMantieneVivoUnCarritoVencido() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        envejecer("consumidor@ccpip.test", Duration.ofMinutes(125));
        // La sesion sigue activa (ultimo acceso recien): igual vence, porque el plazo es del carrito
        consultar(consumidor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.vencidoEn").isNotEmpty());
    }

    // --- Configuracion por el superadministrador (SA-02) ---

    @Test
    void sinConfiguracionRigeLaVigenciaProvisional() throws Exception {
        mockMvc.perform(get("/api/admin/parametros/vigencia-carrito").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horas").value(2))
                .andExpect(jsonPath("$.provisional").value(true))
                .andExpect(jsonPath("$.registradoPor").doesNotExist());
    }

    @Test
    void elSuperadministradorConfiguraLaVigenciaYRigeDeInmediato() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        envejecer("consumidor@ccpip.test", Duration.ofHours(3));
        Cookie sudo = loginAs(SUPER);

        configurar(sudo, 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horas").value(4))
                .andExpect(jsonPath("$.provisional").value(false))
                .andExpect(jsonPath("$.registradoPor").value("Super Administrador de Prueba"))
                .andExpect(jsonPath("$.registradoEn").isNotEmpty());
        mockMvc.perform(get("/api/admin/parametros/vigencia-carrito").cookie(sudo))
                .andExpect(jsonPath("$.horas").value(4));

        // Con 4 horas, el carrito de hace 3 sigue vigente
        JsonNode carrito = leer(consultar(consumidor)
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.vigenciaHoras").value(4)));
        assertThat(Instant.parse(carrito.get("vigenteHasta").asText()))
                .isEqualTo(actualizadoEn("consumidor@ccpip.test").plus(Duration.ofHours(4)));

        // Al acortarla a 1 hora, vence
        configurar(sudo, 1).andExpect(jsonPath("$.horas").value(1));
        consultar(consumidor).andExpect(jsonPath("$.items").isEmpty());

        // Queda el historial auditado de ambos cambios
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parametro_plataforma WHERE clave = 'CARRITO_VIGENCIA_HORAS'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void laVigenciaEsUnNumeroEnteroDeHorasRazonable() throws Exception {
        Cookie sudo = loginAs(SUPER);
        configurar(sudo, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.horas").value("El carrito se guarda al menos una hora"));
        configurar(sudo, 721)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.horas").value("El carrito se guarda como máximo 30 días (720 horas)"));
        configurar(sudo, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.horas").value("Indica cuántas horas se guarda el carrito"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parametro_plataforma", Integer.class)).isZero();
    }

    @Test
    void soloElSuperadministradorVeYCambiaLaVigencia() throws Exception {
        for (String correo : new String[] {"sub_admin@ccpip.test", "consumidor@ccpip.test", "productor@ccpip.test"}) {
            Cookie sesion = loginAs(correo);
            mockMvc.perform(get("/api/admin/parametros/vigencia-carrito").cookie(sesion))
                    .andExpect(status().isForbidden());
            configurar(sesion, 24).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/admin/parametros/vigencia-carrito")).andExpect(status().isUnauthorized());
        configurar(null, 24).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parametro_plataforma", Integer.class)).isZero();
    }

    @Test
    void elHistorialDeParametrosNoSeEditaNiSeBorra() throws Exception {
        configurar(loginAs(SUPER), 6).andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("UPDATE parametro_plataforma SET valor = 1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM parametro_plataforma"))
                .isInstanceOf(DataAccessException.class);
    }

    // --- Apoyo ---

    /** Simula que el ultimo cambio del carrito fue hace {@code hace}. */
    private void envejecer(String correo, Duration hace) {
        jdbc.update("""
                UPDATE carrito SET actualizado_en = now() - ? * interval '1 second'
                WHERE usuario_id = (SELECT id FROM usuario WHERE correo = ?)""", hace.toSeconds(), correo);
    }

    private Instant actualizadoEn(String correo) {
        return jdbc.queryForObject("""
                SELECT c.actualizado_en FROM carrito c JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = ?""",
                Timestamp.class, correo).toInstant();
    }

    private int lineas(String correo) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM item_carrito i JOIN carrito c ON c.id = i.carrito_id
                JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = ?""", Integer.class, correo);
    }

    private String nuevoComprador(String correo) {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, 'Otro comprador', rol_id, true
                FROM usuario WHERE correo = 'consumidor@ccpip.test'""", correo);
        return correo;
    }

    private JsonNode leer(ResultActions respuesta) throws Exception {
        return objectMapper.readTree(respuesta.andReturn().getResponse().getContentAsString());
    }

    private ResultActions configurar(Cookie sesion, Number horas) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("horas", horas);
        var peticion = put("/api/admin/parametros/vigencia-carrito").contentType(MediaType.APPLICATION_JSON)
                .content(json(cuerpo));
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private ResultActions consultar(Cookie sesion) throws Exception {
        return mockMvc.perform(get("/api/carrito").cookie(sesion));
    }

    private ResultActions modificar(Cookie sesion, long productoId, int cantidad) throws Exception {
        return mockMvc.perform(put("/api/carrito/items/{id}", productoId).cookie(sesion)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("cantidad", cantidad))));
    }

    private ResultActions agregar(Cookie sesion, long productoId, int cantidad) throws Exception {
        return mockMvc.perform(post("/api/carrito/items").cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("productoId", productoId, "cantidad", cantidad))));
    }
}
