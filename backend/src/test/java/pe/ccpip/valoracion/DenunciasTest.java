package pe.ccpip.valoracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-83 (RF-90): el comprador denuncia un producto o un productor con motivo y descripcion, sin
 * aprobacion previa. CCPIP-116 (RF-91) y CCPIP-323 (RF-216): el productor ve las suyas anonimizadas en el
 * servidor y responde. CCPIP-84 (RF-77): el subadministrador las consulta por productor, ordenadas por
 * cantidad, con su historial. RNF-41: el denunciante no aparece en ninguna respuesta.
 */
@Transactional
class DenunciasTest extends IntegrationTest {

    private static final String DENUNCIANTE = "denuncia@ccpip.test";
    private static final String NOMBRE_DENUNCIANTE = "Denunciante Reservada";
    private static final String DESCRIPCION = "El aceite llegó rancio. Escríbeme a reservada@correo.pe o al 987 654 321.";

    private Cookie comprador;
    private long ungurahui;
    private long productor;
    private long productor2;

    @BeforeEach
    void preparar() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT ?, contrasena_hash, ?, 1, true FROM usuario WHERE correo = 'consumidor@ccpip.test'
                ON CONFLICT (correo) DO NOTHING""", DENUNCIANTE, NOMBRE_DENUNCIANTE);
        comprador = loginAs(DENUNCIANTE);
        ungurahui = productoConLote("Ungurahui denunciado", "PUBLICADO", "PUNTO_ACOPIO", LocalDate.now().plusMonths(6));
        productor = productorDe("productor@ccpip.test");
        productor2 = productorDe("productor2@ccpip.test");
    }

    // --- CCPIP-83: el comprador denuncia ---

    @Test
    void elCompradorDenunciaUnProductoSinAprobacionPrevia() throws Exception {
        denunciar(ungurahui, null, "CALIDAD", DESCRIPCION)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("RECIBIDA"))
                .andExpect(jsonPath("$.producto.id").value(ungurahui))
                .andExpect(jsonPath("$.producto.nombre").value("Ungurahui denunciado"))
                .andExpect(jsonPath("$.productor.id").value(productor))
                .andExpect(jsonPath("$.motivo").value("CALIDAD"))
                // El comprador ve su propio texto tal como lo escribio
                .andExpect(jsonPath("$.descripcion").value(DESCRIPCION));

        mockMvc.perform(get("/api/denuncias").cookie(comprador))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].estado").value("RECIBIDA"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM denuncia_evento e JOIN denuncia d ON d.id = e.denuncia_id "
                + "WHERE d.producto_id = ? AND e.tipo = 'REGISTRADA'", Integer.class, ungurahui)).isEqualTo(1);
    }

    @Test
    void tambienDenunciaAUnaComunidadYNoDuplicaUnaDenunciaAbierta() throws Exception {
        denunciar(null, productor2, "PRACTICA_INDEBIDA", "No respetan los acuerdos de la comunidad.")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.producto").doesNotExist())
                .andExpect(jsonPath("$.productor.id").value(productor2));
        denunciar(null, productor2, "OTRO", "Vuelvo a denunciar lo mismo otra vez.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DENUNCIA_DUPLICADA"));
        // Sobre un producto de esa comunidad es otra denuncia
        long cacao = productoConLote("Cacao denunciado", "PUBLICADO", null, null, "productor2@ccpip.test");
        denunciar(cacao, null, "NO_CORRESPONDE", "El cacao no es el que se describe.").andExpect(status().isCreated());
    }

    @Test
    void validaElObjetoElMotivoYLaDescripcion() throws Exception {
        denunciar(ungurahui, productor, "CALIDAD", DESCRIPCION)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OBJETO_DENUNCIA_INVALIDO"));
        denunciar(null, null, "CALIDAD", DESCRIPCION).andExpect(status().isUnprocessableEntity());
        denunciar(ungurahui, null, "CALIDAD", "corta")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.descripcion").exists());
        denunciar(ungurahui, null, null, DESCRIPCION)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.motivo").exists());
        denunciar(999_999L, null, "CALIDAD", DESCRIPCION)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        long borrador = productoConLote("Borrador sin comprar", "BORRADOR", null, null);
        denunciar(borrador, null, "CALIDAD", DESCRIPCION).andExpect(status().isNotFound());
    }

    @Test
    void soloElCompradorDenuncia() throws Exception {
        mockMvc.perform(post("/api/denuncias").cookie(loginAs("productor2@ccpip.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productoId", ungurahui, "motivo", "OTRO", "descripcion", DESCRIPCION))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/denuncias")).andExpect(status().isUnauthorized());
    }

    // --- CCPIP-116 y CCPIP-323: el productor ---

    @Test
    void elProductorVeSusDenunciasAnonimizadasEnElServidor() throws Exception {
        denunciar(ungurahui, null, "CALIDAD", DESCRIPCION).andExpect(status().isCreated());
        denunciar(null, productor2, "OTRO", "Denuncia contra otra comunidad.").andExpect(status().isCreated());

        String cuerpo = mockMvc.perform(get("/api/productor/denuncias").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumen.total").value(1))
                .andExpect(jsonPath("$.resumen.abiertas").value(1))
                .andExpect(jsonPath("$.resumen.sinResponder").value(1))
                .andExpect(jsonPath("$.denuncias", hasSize(1)))
                .andExpect(jsonPath("$.denuncias[0].producto.id").value(ungurahui))
                .andExpect(jsonPath("$.denuncias[0].motivo").value("CALIDAD"))
                .andExpect(jsonPath("$.denuncias[0].descripcion")
                        .value("El aceite llegó rancio. Escríbeme a [correo oculto] o al [teléfono oculto]."))
                .andExpect(jsonPath("$.denuncias[0].fecha").value(LocalDate.now(CalificacionesService.LIMA).toString()))
                .andExpect(jsonPath("$.denuncias[0].puedeResponder").value(true))
                .andExpect(jsonPath("$..denunciante").doesNotExist())
                .andExpect(jsonPath("$..usuarioId").doesNotExist())
                .andExpect(jsonPath("$..creadaEn").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain(NOMBRE_DENUNCIANTE).doesNotContain(DENUNCIANTE)
                .doesNotContain("reservada@correo.pe").doesNotContain("987 654 321");
    }

    @Test
    void elProductorRespondeUnaVezYSoloLasPropias() throws Exception {
        long id = idDe(denunciar(ungurahui, null, "CALIDAD", DESCRIPCION));
        Cookie duenio = loginAs("productor@ccpip.test");

        responder(loginAs("productor2@ccpip.test"), id, "Esta denuncia no es mía para responder.")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DENUNCIA_NO_ENCONTRADA"));
        responder(duenio, id, "corta").andExpect(status().isBadRequest());
        responder(duenio, id, "Revisamos el lote y lo reemplazaremos.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respuesta").value("Revisamos el lote y lo reemplazaremos."))
                .andExpect(jsonPath("$.respondidaEn").exists())
                .andExpect(jsonPath("$.puedeResponder").value(false));
        responder(duenio, id, "Una segunda respuesta que no corresponde.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DENUNCIA_YA_RESPONDIDA"));
        // El comprador ve el descargo
        mockMvc.perform(get("/api/denuncias").cookie(comprador))
                .andExpect(jsonPath("$[0].respuestaProductor").value("Revisamos el lote y lo reemplazaremos."));
    }

    @Test
    void unaDenunciaCerradaYaNoSeResponde() throws Exception {
        long id = idDe(denunciar(ungurahui, null, "CALIDAD", DESCRIPCION));
        cambiarEstado(loginAs("sub_admin@ccpip.test"), id, "DESESTIMADA", "No hay evidencia suficiente.")
                .andExpect(status().isOk());
        responder(loginAs("productor@ccpip.test"), id, "Respuesta tardía a una denuncia cerrada.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DENUNCIA_CERRADA"));
    }

    // --- CCPIP-84: el subadministrador ---

    @Test
    void laBandejaOrdenaLosProductoresPorCantidadDeDenuncias() throws Exception {
        denunciar(ungurahui, null, "CALIDAD", DESCRIPCION).andExpect(status().isCreated());
        long cacao = productoConLote("Cacao en la bandeja", "PUBLICADO", null, null, "productor2@ccpip.test");
        denunciar(cacao, null, "ORIGEN_DUDOSO", "No parece cacao de la comunidad.").andExpect(status().isCreated());
        denunciar(null, productor2, "PRACTICA_INDEBIDA", "La comunidad no cumple lo ofrecido.").andExpect(status().isCreated());

        String cuerpo = mockMvc.perform(get("/api/admin/denuncias").cookie(loginAs("sub_admin@ccpip.test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contadores.recibidas").value(3))
                .andExpect(jsonPath("$.productores[*].productorId").value(contains((int) productor2, (int) productor)))
                .andExpect(jsonPath("$.productores[0].total").value(2))
                .andExpect(jsonPath("$.productores[0].abiertas").value(2))
                .andExpect(jsonPath("$.productores[1].total").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain(NOMBRE_DENUNCIANTE).doesNotContain(DENUNCIANTE);
        // El superadministrador supervisa en modo lectura
        mockMvc.perform(get("/api/admin/denuncias").cookie(loginAs("sudo@ccpip.test"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/denuncias").cookie(comprador)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/denuncias").cookie(loginAs("productor@ccpip.test"))).andExpect(status().isForbidden());
    }

    @Test
    void elExpedienteMuestraElHistorialCompletoSinElDenunciante() throws Exception {
        // Compra recibida del productor: el subadministrador lo sabe sin saber quien es
        long pedido = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT 'DEN-1', u.id, 'RECOJO', l.id, 20, 'ENTREGADO', now()
                FROM usuario u, ubicacion l WHERE u.correo = ? AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, DENUNCIANTE);
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal)
                SELECT ?, id, productor_id, 1, precio, precio FROM producto WHERE id = ?""", pedido, ungurahui);
        long id = idDe(denunciar(ungurahui, null, "CALIDAD", DESCRIPCION));
        responder(loginAs("productor@ccpip.test"), id, "Revisamos el lote y lo reemplazaremos.").andExpect(status().isOk());
        Cookie subAdmin = loginAs("sub_admin@ccpip.test");
        cambiarEstado(subAdmin, id, "EN_REVISION", "Pedimos fotos del lote.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_REVISION"))
                .andExpect(jsonPath("$.siguientes", contains("RESUELTA", "DESESTIMADA")));
        cambiarEstado(subAdmin, id, "RESUELTA", "El productor reemplazó el lote.").andExpect(status().isOk());

        String cuerpo = mockMvc.perform(get("/api/admin/denuncias/productores/" + productor).cookie(subAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productor.total").value(1))
                .andExpect(jsonPath("$.productor.abiertas").value(0))
                .andExpect(jsonPath("$.denuncias[0].estado").value("RESUELTA"))
                .andExpect(jsonPath("$.denuncias[0].compraVerificada").value(true))
                .andExpect(jsonPath("$.denuncias[0].descripcion").value(
                        "El aceite llegó rancio. Escríbeme a [correo oculto] o al [teléfono oculto]."))
                .andExpect(jsonPath("$.denuncias[0].respuestaProductor").value("Revisamos el lote y lo reemplazaremos."))
                .andExpect(jsonPath("$.denuncias[0].siguientes", hasSize(0)))
                .andExpect(jsonPath("$.denuncias[0].historial[*].tipo")
                        .value(contains("REGISTRADA", "RESPUESTA", "CAMBIO_ESTADO", "CAMBIO_ESTADO")))
                .andExpect(jsonPath("$.denuncias[0].historial[0].rol").value("CONSUMIDOR"))
                .andExpect(jsonPath("$.denuncias[0].historial[0].responsable").doesNotExist())
                .andExpect(jsonPath("$.denuncias[0].historial[0].nota").doesNotExist())
                .andExpect(jsonPath("$.denuncias[0].historial[1].responsable").exists())
                .andExpect(jsonPath("$.denuncias[0].historial[3].responsable").value("Sub-Administrador de Prueba"))
                .andExpect(jsonPath("$.denuncias[0].historial[3].nota").value("El productor reemplazó el lote."))
                .andReturn().getResponse().getContentAsString();
        assertThat(cuerpo).doesNotContain(NOMBRE_DENUNCIANTE).doesNotContain(DENUNCIANTE).doesNotContain("reservada@correo.pe");
        // Quien cambio el estado queda auditado en la base
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM denuncia_evento e JOIN usuario u ON u.id = e.usuario_id
                WHERE e.denuncia_id = ? AND e.tipo = 'CAMBIO_ESTADO' AND u.correo = 'sub_admin@ccpip.test'""",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void unaDenunciaCerradaNoCambiaYSoloElSubadministradorLaGestiona() throws Exception {
        long id = idDe(denunciar(ungurahui, null, "CALIDAD", DESCRIPCION));
        Cookie subAdmin = loginAs("sub_admin@ccpip.test");
        cambiarEstado(loginAs("sudo@ccpip.test"), id, "RESUELTA", "El super no decide.").andExpect(status().isForbidden());
        cambiarEstado(subAdmin, id, "RECIBIDA", "Volver al inicio no existe.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSICION_INVALIDA"));
        cambiarEstado(subAdmin, id, "RESUELTA", "").andExpect(status().isBadRequest());
        cambiarEstado(subAdmin, id, "RESUELTA", "Se resolvió con el productor.").andExpect(status().isOk());
        cambiarEstado(subAdmin, id, "DESESTIMADA", "Cambio de opinión tardío.")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSICION_INVALIDA"));
        cambiarEstado(subAdmin, 999_999L, "RESUELTA", "No existe esta denuncia.").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/denuncias/productores/" + productor2).cookie(subAdmin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTOR_SIN_DENUNCIAS"));
    }

    @Test
    void lasDenunciasNuncaSeBorranYSuHistorialNoSeEdita() throws Exception {
        long id = idDe(denunciar(ungurahui, null, "CALIDAD", DESCRIPCION));
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM denuncia WHERE id = ?", id), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("UPDATE denuncia_evento SET nota = 'x' WHERE denuncia_id = ?", id), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("UPDATE denuncia SET descripcion = 'otra cosa' WHERE id = ?", id), "no se modifica");
    }

    @Test
    void ocultaCorreosYTelefonosSinTocarElRestoDelTexto() {
        assertThat(DenunciasService.anonimizar("Pedido de 3 frascos, código 12-34.\nMi correo: a.b@c.pe y +51 912-345-678"))
                .isEqualTo("Pedido de 3 frascos, código 12-34.\nMi correo: [correo oculto] y [teléfono oculto]");
        assertThat(DenunciasService.anonimizar("Precio + envío de 2026")).isEqualTo("Precio + envío de 2026");
        assertThat(DenunciasService.anonimizar(null)).isNull();
    }

    // --- Ayudas ---

    private ResultActions denunciar(Long productoId, Long productorId, String motivo, String descripcion) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("productoId", productoId);
        cuerpo.put("productorId", productorId);
        cuerpo.put("motivo", motivo);
        cuerpo.put("descripcion", descripcion);
        return mockMvc.perform(post("/api/denuncias").cookie(comprador)
                .contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private ResultActions responder(Cookie sesion, long id, String texto) throws Exception {
        return mockMvc.perform(post("/api/productor/denuncias/" + id + "/respuesta").cookie(sesion)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("texto", texto))));
    }

    private ResultActions cambiarEstado(Cookie sesion, long id, String estado, String nota) throws Exception {
        return mockMvc.perform(post("/api/admin/denuncias/" + id + "/estado").cookie(sesion)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("estado", estado, "nota", nota))));
    }

    private long idDe(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asLong();
    }

    private long productorDe(String correo) {
        return jdbc.queryForObject("SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = ?",
                Long.class, correo);
    }
}
