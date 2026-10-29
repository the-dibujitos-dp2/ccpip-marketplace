package pe.ccpip.favoritos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * Favoritos del consumidor: guardar, consultar y retirar productos (CCPIP-177, CCPIP-327, CCPIP-396),
 * productores (CCPIP-368, CCPIP-385, CCPIP-397) y comunidades (CCPIP-369, CCPIP-386, CCPIP-398).
 */
@Transactional
class FavoritosTest extends IntegrationTest {

    private static final String RUTA = "/api/cuenta/favoritos";
    private static final String COMPRADOR = "consumidor@ccpip.test";

    private long productoPublicado() {
        return jdbc.queryForObject("""
                SELECT p.id FROM producto p JOIN productor pr ON pr.id = p.productor_id AND pr.activo AND pr.tienda_publicada
                WHERE p.estado = 'PUBLICADO' ORDER BY p.id LIMIT 1""", Long.class);
    }

    private long tiendaPublicada() {
        return jdbc.queryForObject("SELECT id FROM productor WHERE activo AND tienda_publicada ORDER BY id LIMIT 1", Long.class);
    }

    private String comunidad(long tienda) {
        return jdbc.queryForObject("SELECT comunidad FROM productor WHERE id = ?", String.class, tienda);
    }

    private ResultActions guardar(Cookie sesion, String tipo, long id) throws Exception {
        return mockMvc.perform(put(RUTA + "/" + tipo + "/{id}", id).cookie(sesion));
    }

    private ResultActions retirar(Cookie sesion, String tipo, long id) throws Exception {
        return mockMvc.perform(delete(RUTA + "/" + tipo + "/{id}", id).cookie(sesion));
    }

    private ResultActions listar(Cookie sesion) throws Exception {
        return mockMvc.perform(get(RUTA).cookie(sesion));
    }

    private void crearOtraCompradora() {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'otra.compradora@ccpip.test', contrasena_hash, 'Otra Compradora', 1, true FROM usuario
                WHERE correo = 'consumidor@ccpip.test' ON CONFLICT (correo) DO NOTHING""");
    }

    // ---------------------------------------------------------------- productos

    @Test
    void guardaConsultaYRetiraUnProducto() throws Exception {
        Cookie sesion = loginAs(COMPRADOR);
        long producto = productoPublicado();
        String nombre = jdbc.queryForObject("SELECT nombre FROM producto WHERE id = ?", String.class, producto);
        String estadoAntes = jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, producto);

        guardar(sesion, "productos", producto).andExpect(status().isNoContent());
        guardar(sesion, "productos", producto).andExpect(status().isNoContent());

        listar(sesion).andExpect(status().isOk())
                .andExpect(jsonPath("$.productos", hasSize(1)))
                .andExpect(jsonPath("$.productos[0].id").value(producto))
                .andExpect(jsonPath("$.productos[0].nombre").value(nombre))
                .andExpect(jsonPath("$.productos[0].guardadoEn").isNotEmpty())
                .andExpect(jsonPath("$.productos[0].oferta.id").value(producto))
                .andExpect(jsonPath("$.productos[0].cantidad").doesNotExist());

        retirar(sesion, "productos", producto).andExpect(status().isNoContent());
        listar(sesion).andExpect(jsonPath("$.productos", hasSize(0)));
        retirar(sesion, "productos", producto).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FAVORITO_NO_ENCONTRADO"));
        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, producto)).isEqualTo(estadoAntes);
    }

    @Test
    void noGuardaUnProductoQueNoEstaPublicado() throws Exception {
        Cookie sesion = loginAs(COMPRADOR);
        long borrador = productoConLote("Borrador de favoritos", "BORRADOR", null, null);

        guardar(sesion, "productos", borrador).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));
        guardar(sesion, "productos", 999_999).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorito_producto", Long.class)).isZero();
    }

    @Test
    void unProductoRetiradoDelCatalogoSigueEnSusFavoritosSinOferta() throws Exception {
        Cookie sesion = loginAs(COMPRADOR);
        long producto = productoPublicado();
        guardar(sesion, "productos", producto).andExpect(status().isNoContent());
        jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", producto);

        listar(sesion).andExpect(jsonPath("$.productos", hasSize(1)))
                .andExpect(jsonPath("$.productos[0].id").value(producto))
                .andExpect(jsonPath("$.productos[0].oferta").value(nullValue()));
        retirar(sesion, "productos", producto).andExpect(status().isNoContent());
    }

    @Test
    void cadaConsumidorVeYRetiraSoloLosSuyos() throws Exception {
        crearOtraCompradora();
        long producto = productoPublicado();
        guardar(loginAs(COMPRADOR), "productos", producto).andExpect(status().isNoContent());

        Cookie otra = loginAs("otra.compradora@ccpip.test");
        listar(otra).andExpect(jsonPath("$.productos", hasSize(0)));
        retirar(otra, "productos", producto).andExpect(status().isNotFound());

        listar(loginAs(COMPRADOR)).andExpect(jsonPath("$.productos", hasSize(1)));
    }

    @Test
    void soloElConsumidorTieneFavoritos() throws Exception {
        long producto = productoPublicado();
        mockMvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(RUTA + "/productos/{id}", producto)).andExpect(status().isUnauthorized());
        guardar(loginAs("productor@ccpip.test"), "productos", producto).andExpect(status().isForbidden());
        listar(loginAs("sub_admin@ccpip.test")).andExpect(status().isForbidden());
    }

    @Test
    void consultaEnInglesConLosNombresTraducidos() throws Exception {
        Cookie sesion = loginAs(COMPRADOR);
        guardar(sesion, "productos", productoPublicado()).andExpect(status().isNoContent());
        mockMvc.perform(get(RUTA).cookie(sesion).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.productos", hasSize(1)));
    }

    // ---------------------------------------------------------------- productores

    @Test
    void guardaConsultaYRetiraUnProductor() throws Exception {
        Cookie sesion = loginAs(COMPRADOR);
        long tienda = tiendaPublicada();

        guardar(sesion, "productores", tienda).andExpect(status().isNoContent());
        guardar(sesion, "productores", tienda).andExpect(status().isNoContent());
        listar(sesion).andExpect(jsonPath("$.productores", hasSize(1)))
                .andExpect(jsonPath("$.productores[0].id").value(tienda))
                .andExpect(jsonPath("$.productores[0].tienda.id").value(tienda));

        retirar(sesion, "productores", tienda).andExpect(status().isNoContent());
        listar(sesion).andExpect(jsonPath("$.productores", hasSize(0)));
        retirar(sesion, "productores", tienda).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT activo AND tienda_publicada FROM productor WHERE id = ?", Boolean.class, tienda)).isTrue();
    }

    @Test
    void noGuardaUnProductorSinTiendaPublicada() throws Exception {
        Cookie sesion = loginAs(COMPRADOR);
        long tienda = tiendaPublicada();
        jdbc.update("UPDATE productor SET tienda_publicada = false WHERE id = ?", tienda);

        guardar(sesion, "productores", tienda).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TIENDA_NO_ENCONTRADA"));
        guardar(sesion, "productores", 999_999).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- comunidades

    @Test
    void guardaConsultaYRetiraUnaComunidad() throws Exception {
        Cookie sesion = loginAs(COMPRADOR);
        long tienda = tiendaPublicada();
        String comunidad = comunidad(tienda);

        guardar(sesion, "comunidades", tienda).andExpect(status().isNoContent());
        guardar(sesion, "comunidades", tienda).andExpect(status().isNoContent());
        listar(sesion).andExpect(jsonPath("$.comunidades", hasSize(1)))
                .andExpect(jsonPath("$.comunidades[0].comunidad").value(comunidad))
                .andExpect(jsonPath("$.comunidades[0].tiendaId").value(tienda))
                .andExpect(jsonPath("$.comunidades[0].tiendas[0].id").value(tienda));

        retirar(sesion, "comunidades", tienda).andExpect(status().isNoContent());
        listar(sesion).andExpect(jsonPath("$.comunidades", hasSize(0)));
        retirar(sesion, "comunidades", tienda).andExpect(status().isNotFound());
        assertThat(comunidad(tienda)).isEqualTo(comunidad);
    }

    @Test
    void laMismaComunidadDesdeOtraTiendaNoSeDuplica() throws Exception {
        Cookie sesion = loginAs(COMPRADOR);
        long tienda = tiendaPublicada();
        long otra = jdbc.queryForObject("""
                SELECT id FROM productor WHERE activo AND tienda_publicada AND id <> ? ORDER BY id LIMIT 1""", Long.class, tienda);
        // La misma comunidad escrita con otra capitalizacion y sin tilde
        jdbc.update("UPDATE productor SET comunidad = ' ' || upper(inmutable_unaccent((SELECT comunidad FROM productor WHERE id = ?))) WHERE id = ?",
                tienda, otra);

        guardar(sesion, "comunidades", tienda).andExpect(status().isNoContent());
        guardar(sesion, "comunidades", otra).andExpect(status().isNoContent());
        listar(sesion).andExpect(jsonPath("$.comunidades", hasSize(1)))
                .andExpect(jsonPath("$.comunidades[0].tiendas", hasSize(2)));

        // Se puede retirar desde cualquiera de sus tiendas
        retirar(sesion, "comunidades", otra).andExpect(status().isNoContent());
        listar(sesion).andExpect(jsonPath("$.comunidades", hasSize(0)));
    }

    @Test
    void noGuardaLaComunidadDeUnaTiendaNoPublicada() throws Exception {
        Cookie sesion = loginAs(COMPRADOR);
        long tienda = tiendaPublicada();
        jdbc.update("UPDATE productor SET activo = false WHERE id = ?", tienda);

        guardar(sesion, "comunidades", tienda).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TIENDA_NO_ENCONTRADA"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM favorito_comunidad", Long.class)).isZero();
    }
}
