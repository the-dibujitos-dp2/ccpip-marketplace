package pe.ccpip.catalogomaestro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * Catalogo maestro de la Camara: lineas (CCPIP-96, 309, 310, 311) y productos (CCPIP-351, 312, 313, 314) sobre
 * el producto_maestro de CCPIP-42.
 * Lo administra el superadministrador; nunca se borra y no altera las ofertas ni los pedidos historicos.
 *
 * <p>Transaccional: lo creado o desactivado aqui no llega a las demas pruebas.
 */
@Transactional
class CatalogoMaestroTest extends IntegrationTest {

    private static final String SUPER = "sudo@ccpip.test";
    private static final String LINEAS = "/api/admin/catalogo/lineas";
    private static final String PRODUCTOS = "/api/admin/catalogo/productos";

    // --- Permisos (RNF-04, RNF-05) ---

    @ParameterizedTest
    @ValueSource(strings = {"sub_admin@ccpip.test", "productor@ccpip.test", "consumidor@ccpip.test", "admin_acopio@ccpip.test"})
    void soloElSuperadministradorAdministraElCatalogoMaestro(String correo) throws Exception {
        Cookie otro = loginAs(correo);
        mockMvc.perform(get(LINEAS).cookie(otro)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        mockMvc.perform(get(PRODUCTOS).cookie(otro)).andExpect(status().isForbidden());
        enviar(post(LINEAS), otro, nuevaLinea("MIEL", "Miel")).andExpect(status().isForbidden());
        enviar(post(PRODUCTOS), otro, nuevoProducto("MIEL", "Miel de abeja", "CAFE")).andExpect(status().isForbidden());
        mockMvc.perform(post(LINEAS + "/{id}/desactivar", idLinea("CAFE")).cookie(otro)).andExpect(status().isForbidden());
        mockMvc.perform(post(PRODUCTOS + "/{id}/desactivar", idMaestro("Café molido")).cookie(otro)).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT activo FROM linea_producto WHERE codigo = 'CAFE'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM linea_producto WHERE codigo = 'MIEL'", Long.class)).isZero();
    }

    @Test
    void sinSesionNoSeConsulta() throws Exception {
        mockMvc.perform(get(LINEAS)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PRODUCTOS)).andExpect(status().isUnauthorized());
    }

    // --- CCPIP-96: consultar lineas ---

    @Test
    void elSuperadministradorConsultaLasLineasConSusProductosYOfertas() throws Exception {
        Cookie sesion = loginAs(SUPER);
        mockMvc.perform(get(LINEAS).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contadores.activos").value(7))
                .andExpect(jsonPath("$.contadores.inactivos").value(0))
                .andExpect(jsonPath("$.lineas[0].codigo").value("CACAO"))
                .andExpect(jsonPath("$.lineas[0].nombre").value("Cacao y chocolates"))
                .andExpect(jsonPath("$.lineas[0].nombreEn").value("Cacao & chocolate"))
                .andExpect(jsonPath("$.lineas[0].requiereVencimiento").value(true))
                .andExpect(jsonPath("$.lineas[0].unidadSugerida.codigo").value("BOLSA"))
                .andExpect(jsonPath("$.lineas[0].activa").value(true))
                .andExpect(jsonPath("$.lineas[0].productos").value(2))
                .andExpect(jsonPath("$.lineas[0].ofertas").value(3))
                .andExpect(jsonPath("$.lineas[5].codigo").value("ARTESANIA"))
                .andExpect(jsonPath("$.lineas[5].requiereVencimiento").value(false));

        // Busqueda sin tildes ni mayusculas, y filtro por estado
        mockMvc.perform(get(LINEAS).cookie(sesion).param("q", "CAFE"))
                .andExpect(jsonPath("$.lineas.length()").value(1))
                .andExpect(jsonPath("$.lineas[0].nombre").value("Café"));
        mockMvc.perform(get(LINEAS).cookie(sesion).param("estado", "INACTIVOS"))
                .andExpect(jsonPath("$.lineas.length()").value(0));
        mockMvc.perform(get(LINEAS).cookie(sesion).param("q", "100%"))
                .andExpect(jsonPath("$.lineas.length()").value(0));
    }

    @Test
    void unFiltroDeEstadoDesconocidoSeRechaza() throws Exception {
        Cookie sesion = loginAs(SUPER);
        mockMvc.perform(get(LINEAS).cookie(sesion).param("estado", "BORRADAS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETRO_INVALIDO"));
        mockMvc.perform(get(PRODUCTOS).cookie(sesion).param("estado", "x"))
                .andExpect(status().isBadRequest());
    }

    // --- CCPIP-309: crear linea ---

    @Test
    void crearUnaLineaLaOfreceALosProductoresYQuedaAuditada() throws Exception {
        Cookie sesion = loginAs(SUPER);
        Map<String, Object> miel = nuevaLinea("miel_abeja", "Miel y derivados");
        miel.put("nombreEn", "Honey");
        miel.put("unidadSugerida", "FRASCO");
        JsonNode creada = leer(enviar(post(LINEAS), sesion, miel)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codigo").value("MIEL_ABEJA"))
                .andExpect(jsonPath("$.nombre").value("Miel y derivados"))
                .andExpect(jsonPath("$.nombreEn").value("Honey"))
                .andExpect(jsonPath("$.unidadSugerida.codigo").value("FRASCO"))
                .andExpect(jsonPath("$.orden").value(8))
                .andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.productos").value(0))
                .andExpect(jsonPath("$.ofertas").value(0)));

        // Los formularios del productor la ofrecen, tambien en ingles
        mockMvc.perform(get("/api/catalogos")).andExpect(jsonPath("$.lineas[*].codigo", hasItem("MIEL_ABEJA")));
        mockMvc.perform(get("/api/catalogos").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.lineas[*].nombre", hasItem("Honey")));

        Map<String, Object> auditoria = jdbc.queryForMap("""
                SELECT a.accion, a.cambios::text AS cambios, u.correo FROM catalogo_maestro_auditoria a
                JOIN usuario u ON u.id = a.usuario_id WHERE a.entidad = 'LINEA' AND a.entidad_id = ?""", creada.get("id").asLong());
        assertThat(auditoria.get("accion")).isEqualTo("CREAR");
        assertThat(auditoria.get("correo")).isEqualTo(SUPER);
        assertThat((String) auditoria.get("cambios")).contains("MIEL_ABEJA").contains("Miel y derivados");
    }

    @Test
    void noSeCreaUnaLineaRepetidaNiInvalida() throws Exception {
        Cookie sesion = loginAs(SUPER);
        enviar(post(LINEAS), sesion, nuevaLinea("cafe", "Café de altura"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODIGO_DUPLICADO"));
        // El nombre tampoco se repite, ni cambiando mayusculas o tildes
        enviar(post(LINEAS), sesion, nuevaLinea("CAFE_2", "CAFE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOMBRE_DUPLICADO"));
        enviar(post(LINEAS), sesion, nuevaLinea("con espacio", "Nueva"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.codigo").exists());
        enviar(post(LINEAS), sesion, nuevaLinea("NUEVA", " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.nombre").exists());
        Map<String, Object> sinUnidad = nuevaLinea("NUEVA", "Nueva");
        sinUnidad.put("unidadSugerida", "TONELADA");
        enviar(post(LINEAS), sesion, sinUnidad)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNIDAD_INVALIDA"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM linea_producto", Long.class)).isEqualTo(7);
    }

    // --- CCPIP-310: modificar linea ---

    @Test
    void modificarUnaLineaNoCambiaSuCodigoNiLasOfertasYDesactualizaSuTraduccion() throws Exception {
        Cookie sesion = loginAs(SUPER);
        int cafe = idLinea("CAFE");
        Map<String, Object> ofertasAntes = ofertas("CAFE");

        Map<String, Object> datos = datosLinea("Cafés especiales", false, "KG", 9);
        enviar(put(LINEAS + "/{id}", cafe), sesion, datos)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value("CAFE"))
                .andExpect(jsonPath("$.nombre").value("Cafés especiales"))
                .andExpect(jsonPath("$.requiereVencimiento").value(false))
                .andExpect(jsonPath("$.unidadSugerida.codigo").value("KG"))
                .andExpect(jsonPath("$.orden").value(9))
                // Cambio el original: la traduccion anterior deja de publicarse (CCPIP-373)
                .andExpect(jsonPath("$.nombreEn").doesNotExist())
                .andExpect(jsonPath("$.ofertas").value(2));
        assertThat(jdbc.queryForObject("""
                SELECT estado FROM traduccion WHERE entidad = 'LINEA' AND clave = 'CAFE' AND campo = 'nombre'""", String.class))
                .isEqualTo("DESACTUALIZADA");
        assertThat(ofertas("CAFE")).isEqualTo(ofertasAntes);

        // Con el nombre en ingles nuevo, la traduccion vuelve a publicarse
        datos.put("nombreEn", "Specialty coffee");
        enviar(put(LINEAS + "/{id}", cafe), sesion, datos).andExpect(jsonPath("$.nombreEn").value("Specialty coffee"));
        mockMvc.perform(get("/api/catalogos").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.lineas[*].nombre", hasItem("Specialty coffee")));

        String cambios = jdbc.queryForObject("""
                SELECT cambios::text FROM catalogo_maestro_auditoria WHERE entidad = 'LINEA' AND entidad_id = ? AND accion = 'EDITAR'
                ORDER BY id LIMIT 1""", String.class, cafe);
        assertThat(cambios).contains("\"nombre\"").contains("Café").contains("Cafés especiales").contains("\"requiereVencimiento\"");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM catalogo_maestro_auditoria WHERE entidad = 'LINEA' AND entidad_id = ? AND accion = 'EDITAR'""",
                Long.class, cafe)).isEqualTo(2);
    }

    @Test
    void noSeModificaUnaLineaInexistenteNiConUnNombreAjeno() throws Exception {
        Cookie sesion = loginAs(SUPER);
        enviar(put(LINEAS + "/{id}", 30000), sesion, datosLinea("Otra", true, null, null))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LINEA_NO_ENCONTRADA"));
        enviar(put(LINEAS + "/{id}", idLinea("CAFE")), sesion, datosLinea("aceites", true, null, null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOMBRE_DUPLICADO"));
    }

    // --- CCPIP-430: umbrales de merma de la linea ---

    @Test
    void lasLineasNacenConUmbralesDeMermaYElSuperadministradorLosCambiaAuditados() throws Exception {
        Cookie sesion = loginAs(SUPER);
        // Toda linea tiene sus umbrales a la vista: las existentes y las nuevas nacen en 5 % y 10 %
        mockMvc.perform(get(LINEAS).cookie(sesion))
                .andExpect(jsonPath("$.lineas[?(@.codigo == 'CAFE')].mermaUmbralAlerta", hasItem(5.0)))
                .andExpect(jsonPath("$.lineas[?(@.codigo == 'CAFE')].mermaUmbralCritico", hasItem(10.0)));
        enviar(post(LINEAS), sesion, nuevaLinea("MIEL", "Miel"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mermaUmbralAlerta").value(5.0))
                .andExpect(jsonPath("$.mermaUmbralCritico").value(10.0));
        Map<String, Object> flores = nuevaLinea("FLORES", "Flores");
        flores.put("mermaUmbralAlerta", 12);
        flores.put("mermaUmbralCritico", 25.5);
        enviar(post(LINEAS), sesion, flores)
                .andExpect(jsonPath("$.mermaUmbralAlerta").value(12.0))
                .andExpect(jsonPath("$.mermaUmbralCritico").value(25.5));

        int cafe = idLinea("CAFE");
        enviar(put(LINEAS + "/{id}", cafe), sesion, umbrales(8, 15))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mermaUmbralAlerta").value(8.0))
                .andExpect(jsonPath("$.mermaUmbralCritico").value(15.0));
        assertThat(jdbc.queryForObject("""
                SELECT cambios::text FROM catalogo_maestro_auditoria WHERE entidad = 'LINEA' AND entidad_id = ? AND accion = 'EDITAR'""",
                String.class, cafe)).contains("\"mermaUmbralAlerta\"").contains("\"mermaUmbralCritico\"");

        // Sin umbrales en el cuerpo (un formulario anterior a CCPIP-430) se conservan los que tiene
        enviar(put(LINEAS + "/{id}", cafe), sesion, datosLinea("Café", true, null, null))
                .andExpect(jsonPath("$.mermaUmbralAlerta").value(8.0))
                .andExpect(jsonPath("$.mermaUmbralCritico").value(15.0));
    }

    @Test
    void losUmbralesDeMermaSeValidan() throws Exception {
        Cookie sesion = loginAs(SUPER);
        int cafe = idLinea("CAFE");
        enviar(put(LINEAS + "/{id}", cafe), sesion, umbrales(10, 10))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UMBRALES_MERMA_INVALIDOS"));
        // Solo la alerta, por encima del critico que ya tiene (10 %)
        enviar(put(LINEAS + "/{id}", cafe), sesion, umbrales(12, null))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UMBRALES_MERMA_INVALIDOS"));
        Map<String, Object> nueva = nuevaLinea("MIEL", "Miel");
        nueva.put("mermaUmbralAlerta", 20);
        enviar(post(LINEAS), sesion, nueva)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UMBRALES_MERMA_INVALIDOS"));
        enviar(put(LINEAS + "/{id}", cafe), sesion, umbrales(0, 10))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.mermaUmbralAlerta").exists());
        enviar(put(LINEAS + "/{id}", cafe), sesion, umbrales(5, 101))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.mermaUmbralCritico").exists());
        enviar(put(LINEAS + "/{id}", cafe), sesion, umbrales(5.25, 10))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.mermaUmbralAlerta").exists());

        assertThat(jdbc.queryForMap("""
                SELECT merma_umbral_alerta::text AS alerta, merma_umbral_critico::text AS critico
                FROM linea_producto WHERE id = ?""", cafe))
                .containsEntry("alerta", "5.0").containsEntry("critico", "10.0");
    }

    // --- CCPIP-311: desactivar linea ---

    @Test
    void desactivarUnaLineaLaRetiraDeLosFormulariosPeroConservaSusOfertas() throws Exception {
        Cookie sesion = loginAs(SUPER);
        int superfrutos = idLinea("SUPERFRUTOS");
        long camu = jdbc.queryForObject("SELECT id FROM producto WHERE nombre = 'Camu camu en polvo 150 g'", Long.class);
        Map<String, Object> ofertasAntes = ofertas("SUPERFRUTOS");

        mockMvc.perform(post(LINEAS + "/{id}/desactivar", superfrutos).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activa").value(false))
                .andExpect(jsonPath("$.desactivadaEn").isNotEmpty())
                .andExpect(jsonPath("$.ofertas").value(1))
                .andExpect(jsonPath("$.productos").value(1));
        mockMvc.perform(post(LINEAS + "/{id}/desactivar", superfrutos).cookie(sesion))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LINEA_YA_INACTIVA"));

        // Fuera de los formularios y de la consulta de activas; sigue en la de inactivas
        mockMvc.perform(get("/api/catalogos")).andExpect(jsonPath("$.lineas[*].codigo", not(hasItem("SUPERFRUTOS"))));
        mockMvc.perform(get(LINEAS).cookie(sesion).param("estado", "INACTIVOS"))
                .andExpect(jsonPath("$.contadores.inactivos").value(1))
                .andExpect(jsonPath("$.lineas[0].codigo").value("SUPERFRUTOS"));

        // La oferta sigue igual y a la venta, con su linea
        assertThat(ofertas("SUPERFRUTOS")).isEqualTo(ofertasAntes);
        mockMvc.perform(get("/api/productos/{id}", camu)).andExpect(status().isOk())
                .andExpect(jsonPath("$.linea.codigo").value("SUPERFRUTOS"));

        // El productor puede seguir editando su oferta con la linea que ya tenia, pero no elegirla para otra
        Cookie productor = loginAs("productor@ccpip.test");
        enviar(put("/api/productor/productos/{id}", camu), productor, datosOferta(camu))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lineas[0]").value("SUPERFRUTOS"));
        Map<String, Object> nueva = datosOferta(camu);
        nueva.put("nombre", "Camu camu entero");
        enviar(post("/api/productor/productos"), productor, nueva)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LINEA_INVALIDA"));

        // Tampoco recibe productos maestros nuevos
        enviar(post(PRODUCTOS), sesion, nuevoProducto("AGUAYMANTO", "Aguaymanto deshidratado", "SUPERFRUTOS"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LINEA_INACTIVA"));

        // Se puede reactivar
        mockMvc.perform(post(LINEAS + "/{id}/reactivar", superfrutos).cookie(sesion))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activa").value(true))
                .andExpect(jsonPath("$.desactivadaEn").doesNotExist());
        mockMvc.perform(post(LINEAS + "/{id}/reactivar", superfrutos).cookie(sesion))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LINEA_YA_ACTIVA"));
        assertThat(jdbc.queryForList("""
                SELECT accion FROM catalogo_maestro_auditoria WHERE entidad = 'LINEA' AND entidad_id = ? ORDER BY id""",
                String.class, superfrutos)).containsExactly("DESACTIVAR", "REACTIVAR");
    }

    // --- CCPIP-351: consultar productos ---

    @Test
    void elSuperadministradorConsultaLosProductosDelCatalogoMaestro() throws Exception {
        Cookie sesion = loginAs(SUPER);
        mockMvc.perform(get(PRODUCTOS).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contadores.activos").value(10))
                .andExpect(jsonPath("$.productos.length()").value(10))
                .andExpect(jsonPath("$.productos[0].linea.codigo").value("CACAO"))
                .andExpect(jsonPath("$.productos[0].codigo").value("CACAO-GRANO"))
                .andExpect(jsonPath("$.productos[0].nombre").value("Cacao en grano fermentado"))
                // Dos comunidades ofrecen el mismo producto maestro (CCPIP-42)
                .andExpect(jsonPath("$.productos[0].ofertas").value(2))
                .andExpect(jsonPath("$.productos[0].ofertasPublicadas").value(2))
                // Sin precio ni stock: no es una oferta
                .andExpect(jsonPath("$.productos[0].precio").doesNotExist());

        mockMvc.perform(get(PRODUCTOS).cookie(sesion).param("linea", "CAFE"))
                .andExpect(jsonPath("$.productos.length()").value(2))
                .andExpect(jsonPath("$.productos[0].nombre").value("Café molido"));
        mockMvc.perform(get(PRODUCTOS).cookie(sesion).param("q", "cafe tostado"))
                .andExpect(jsonPath("$.productos.length()").value(1));
        mockMvc.perform(get(PRODUCTOS).cookie(sesion).param("estado", "INACTIVOS"))
                .andExpect(jsonPath("$.productos.length()").value(0));
        mockMvc.perform(get(PRODUCTOS).cookie(sesion).param("linea", "NO_EXISTE"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LINEA_INVALIDA"));
    }

    // --- CCPIP-312: crear producto ---

    @Test
    void crearUnProductoMaestroEnUnaLineaActiva() throws Exception {
        Cookie sesion = loginAs(SUPER);
        Map<String, Object> datos = nuevoProducto("chocolate-70", "Chocolate al 70 %", "CACAO");
        datos.put("descripcion", "Tableta de cacao fino de aroma.");
        JsonNode creado = leer(enviar(post(PRODUCTOS), sesion, datos)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codigo").value("CHOCOLATE-70"))
                .andExpect(jsonPath("$.nombre").value("Chocolate al 70 %"))
                .andExpect(jsonPath("$.descripcion").value("Tableta de cacao fino de aroma."))
                .andExpect(jsonPath("$.linea.codigo").value("CACAO"))
                .andExpect(jsonPath("$.activo").value(true))
                .andExpect(jsonPath("$.ofertas").value(0)));
        assertThat(jdbc.queryForObject("""
                SELECT accion FROM catalogo_maestro_auditoria WHERE entidad = 'PRODUCTO' AND entidad_id = ?""",
                String.class, creado.get("id").asLong())).isEqualTo("CREAR");

        // No se repite en la misma linea (sin importar mayusculas ni tildes); en otra linea si puede
        enviar(post(PRODUCTOS), sesion, nuevoProducto("CAFE-2", "CAFE MOLIDO", "CAFE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCTO_DUPLICADO"));
        enviar(post(PRODUCTOS), sesion, nuevoProducto("cafe-molido", "Café soluble", "CAFE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODIGO_DUPLICADO"));
        enviar(post(PRODUCTOS), sesion, nuevoProducto("GUAYUSA-CAFE", "Café molido", "INFUSIONES")).andExpect(status().isCreated());

        enviar(post(PRODUCTOS), sesion, nuevoProducto("ALGO", "Algo", "NO_EXISTE"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LINEA_INVALIDA"));
        enviar(post(PRODUCTOS), sesion, nuevoProducto("ALGO", "", "CACAO"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.nombre").exists());
        enviar(post(PRODUCTOS), sesion, nuevoProducto("con espacio", "Algo", "CACAO"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.codigo").exists());
    }

    // --- CCPIP-313: modificar producto sin alterar ofertas historicas ---

    @Test
    void modificarUnProductoMaestroNoAlteraSusOfertasNiLosPedidos() throws Exception {
        Cookie sesion = loginAs(SUPER);
        long maestro = idMaestro("Café molido");
        long oferta = jdbc.queryForObject("SELECT id FROM producto WHERE producto_maestro_id = ?", Long.class, maestro);
        long pedido = pedidoConfirmado("MC-CM313", "Centro Regional Cusco", oferta, 2);
        Map<String, Object> ofertaAntes = oferta(oferta);
        Map<String, Object> itemAntes = item(pedido);

        Map<String, Object> datos = producto("Café molido para filtro", "CAFE");
        datos.put("descripcion", "Café arábica de altura, molienda media.");
        enviar(put(PRODUCTOS + "/{id}", maestro), sesion, datos)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value("CAFE-MOLIDO"))
                .andExpect(jsonPath("$.nombre").value("Café molido para filtro"))
                .andExpect(jsonPath("$.descripcion").value("Café arábica de altura, molienda media."))
                .andExpect(jsonPath("$.ofertas").value(1));

        assertThat(oferta(oferta)).isEqualTo(ofertaAntes);
        assertThat(item(pedido)).isEqualTo(itemAntes);
        mockMvc.perform(get("/api/productos/{id}", oferta)).andExpect(jsonPath("$.nombre").value("Cafe molido 250 g"));

        String cambios = jdbc.queryForObject("""
                SELECT cambios::text FROM catalogo_maestro_auditoria WHERE entidad = 'PRODUCTO' AND entidad_id = ? AND accion = 'EDITAR'""",
                String.class, maestro);
        assertThat(cambios).contains("Café molido para filtro").contains("\"descripcion\"").doesNotContain("\"linea\"").doesNotContain("\"codigo\"");

        enviar(put(PRODUCTOS + "/{id}", maestro), sesion, producto("Café tostado en grano", "CAFE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCTO_DUPLICADO"));
        enviar(put(PRODUCTOS + "/{id}", 999999999L), sesion, datos)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
    }

    @Test
    void unProductoSeQuedaEnSuLineaDesactivadaPeroNoPasaAOtraInactiva() throws Exception {
        Cookie sesion = loginAs(SUPER);
        long camu = idMaestro("Camu camu en polvo");
        mockMvc.perform(post(LINEAS + "/{id}/desactivar", idLinea("SUPERFRUTOS")).cookie(sesion)).andExpect(status().isOk());
        mockMvc.perform(post(LINEAS + "/{id}/desactivar", idLinea("TEXTIL")).cookie(sesion)).andExpect(status().isOk());

        enviar(put(PRODUCTOS + "/{id}", camu), sesion, producto("Camu camu liofilizado", "SUPERFRUTOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linea.activa").value(false));
        enviar(put(PRODUCTOS + "/{id}", camu), sesion, producto("Camu camu liofilizado", "TEXTIL"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LINEA_INACTIVA"));
    }

    // --- CCPIP-314: desactivar producto conservando referencias historicas ---

    @Test
    void desactivarUnProductoMaestroConservaSusOfertasYReferencias() throws Exception {
        Cookie sesion = loginAs(SUPER);
        long maestro = idMaestro("Aceite de aguaje");
        long oferta = jdbc.queryForObject("SELECT id FROM producto WHERE producto_maestro_id = ?", Long.class, maestro);
        long pedido = pedidoConfirmado("MC-CM314", "Centro Regional Cusco", oferta, 1);
        Map<String, Object> ofertaAntes = oferta(oferta);
        Map<String, Object> itemAntes = item(pedido);

        mockMvc.perform(post(PRODUCTOS + "/{id}/desactivar", maestro).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false))
                .andExpect(jsonPath("$.desactivadoEn").isNotEmpty())
                .andExpect(jsonPath("$.ofertas").value(1))
                .andExpect(jsonPath("$.ofertasPublicadas").value(1));
        mockMvc.perform(post(PRODUCTOS + "/{id}/desactivar", maestro).cookie(sesion))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCTO_YA_INACTIVO"));

        // La oferta lo sigue referenciando, sin cambios, y el pedido tambien
        assertThat(jdbc.queryForObject("SELECT producto_maestro_id FROM producto WHERE id = ?", Long.class, oferta)).isEqualTo(maestro);
        assertThat(oferta(oferta)).isEqualTo(ofertaAntes);
        assertThat(item(pedido)).isEqualTo(itemAntes);
        mockMvc.perform(get("/api/productos/{id}", oferta)).andExpect(status().isOk());

        mockMvc.perform(get(PRODUCTOS).cookie(sesion).param("estado", "INACTIVOS"))
                .andExpect(jsonPath("$.contadores.inactivos").value(1))
                .andExpect(jsonPath("$.productos[0].nombre").value("Aceite de aguaje"));
        String conservado = jdbc.queryForObject("""
                SELECT cambios::text FROM catalogo_maestro_auditoria WHERE entidad = 'PRODUCTO' AND entidad_id = ? AND accion = 'DESACTIVAR'""",
                String.class, maestro);
        assertThat(conservado).contains("\"ofertasConservadas\": 1");

        mockMvc.perform(post(PRODUCTOS + "/{id}/reactivar", maestro).cookie(sesion))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activo").value(true));
    }

    @Test
    void noSeReactivaUnProductoDeUnaLineaInactiva() throws Exception {
        Cookie sesion = loginAs(SUPER);
        long canasta = idMaestro("Canasta tejida de tamshi");
        mockMvc.perform(post(PRODUCTOS + "/{id}/reactivar", canasta).cookie(sesion))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRODUCTO_YA_ACTIVO"));
        mockMvc.perform(post(PRODUCTOS + "/{id}/desactivar", canasta).cookie(sesion)).andExpect(status().isOk());
        mockMvc.perform(post(LINEAS + "/{id}/desactivar", idLinea("ARTESANIA")).cookie(sesion)).andExpect(status().isOk());
        mockMvc.perform(post(PRODUCTOS + "/{id}/reactivar", canasta).cookie(sesion))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LINEA_INACTIVA"));
    }

    /** Nada del catalogo maestro se borra, el codigo de una linea no cambia y la auditoria no se edita. */
    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM linea_producto WHERE codigo = 'TEXTIL'",
            "DELETE FROM producto_maestro WHERE nombre = 'Aceite de aguaje'",
            "UPDATE linea_producto SET codigo = 'CAFE_X' WHERE codigo = 'CAFE'",
            "UPDATE producto_maestro SET codigo = 'CAFE-X' WHERE codigo = 'CAFE-MOLIDO'",
            "UPDATE catalogo_maestro_auditoria SET accion = 'CREAR'",
            "DELETE FROM catalogo_maestro_auditoria",
            "UPDATE linea_producto SET merma_umbral_alerta = 20 WHERE codigo = 'CAFE'"})
    void laBaseConservaElCatalogoYSuHistorial(String sql) throws Exception {
        Cookie sesion = loginAs(SUPER);
        mockMvc.perform(post(LINEAS + "/{id}/desactivar", idLinea("TEXTIL")).cookie(sesion)).andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataAccessException.class);
    }

    // --- Ayudas ---

    private ResultActions enviar(MockHttpServletRequestBuilder peticion, Cookie sesion, Object cuerpo) throws Exception {
        return mockMvc.perform(peticion.cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private JsonNode leer(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, Object> nuevaLinea(String codigo, String nombre) {
        Map<String, Object> d = datosLinea(nombre, true, null, null);
        d.put("codigo", codigo);
        return d;
    }

    private static Map<String, Object> datosLinea(String nombre, boolean vence, String unidad, Integer orden) {
        Map<String, Object> d = new HashMap<>();
        d.put("nombre", nombre);
        d.put("requiereVencimiento", vence);
        d.put("unidadSugerida", unidad);
        d.put("orden", orden);
        return d;
    }

    /** Los datos de la linea Cafe con esos umbrales de merma (nulo: no se envia). */
    private static Map<String, Object> umbrales(Number alerta, Number critico) {
        Map<String, Object> d = datosLinea("Café", true, null, null);
        d.put("mermaUmbralAlerta", alerta);
        d.put("mermaUmbralCritico", critico);
        return d;
    }

    private static Map<String, Object> producto(String nombre, String linea) {
        Map<String, Object> d = new HashMap<>();
        d.put("nombre", nombre);
        d.put("linea", linea);
        return d;
    }

    private static Map<String, Object> nuevoProducto(String codigo, String nombre, String linea) {
        Map<String, Object> d = producto(nombre, linea);
        d.put("codigo", codigo);
        return d;
    }

    /** Los datos actuales de una oferta, tal como el productor los enviaria al guardarla sin cambios. */
    private Map<String, Object> datosOferta(long id) {
        Map<String, Object> fila = jdbc.queryForMap("""
                SELECT p.nombre, p.descripcion, p.modalidad, p.precio, u.codigo AS unidad
                FROM producto p JOIN unidad_medida u ON u.id = p.unidad_medida_id WHERE p.id = ?""", id);
        Map<String, Object> d = new HashMap<>();
        d.put("nombre", fila.get("nombre"));
        d.put("descripcion", fila.get("descripcion"));
        d.put("lineas", List.of("SUPERFRUTOS"));
        d.put("unidad", fila.get("unidad"));
        d.put("modalidad", fila.get("modalidad"));
        d.put("precio", fila.get("precio"));
        return d;
    }

    private int idLinea(String codigo) {
        return jdbc.queryForObject("SELECT id FROM linea_producto WHERE codigo = ?", Integer.class, codigo);
    }

    private long idMaestro(String nombre) {
        return jdbc.queryForObject("SELECT id FROM producto_maestro WHERE nombre = ?", Long.class, nombre);
    }

    /** Lo que una oferta muestra y cobra: no debe cambiar por el catalogo maestro. */
    private Map<String, Object> oferta(long id) {
        return jdbc.queryForMap("""
                SELECT nombre, descripcion, precio, estado, linea_id, unidad_medida_id, producto_maestro_id, actualizado_en,
                       (SELECT string_agg(linea_id::text, ',' ORDER BY linea_id) FROM producto_linea WHERE producto_id = p.id) AS lineas
                FROM producto p WHERE id = ?""", id);
    }

    private Map<String, Object> ofertas(String linea) {
        return jdbc.queryForMap("""
                SELECT count(*) AS total, string_agg(p.nombre || ':' || p.estado || ':' || p.precio, ',' ORDER BY p.id) AS detalle
                FROM producto p JOIN linea_producto l ON l.id = p.linea_id WHERE l.codigo = ?""", linea);
    }

    private Map<String, Object> item(long pedido) {
        return jdbc.queryForMap("SELECT producto_id, cantidad, precio_unitario, subtotal FROM item_pedido WHERE pedido_id = ?", pedido);
    }
}
