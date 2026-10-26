package pe.ccpip.productor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * El flujo del productor por la API, como lo recorren PW-01 a PW-08: arma su tienda desde cero,
 * publica un producto con su historia y su primer lote, lo edita, lo retira y lo vuelve a
 * publicar. Usa productor_nuevo, sembrado con la tienda vacia.
 */
@Transactional
class ProductorFlujoTest extends IntegrationTest {

    private Cookie nuevo;
    private long acopioLima;
    private long almacenPropio;

    @BeforeEach
    void ingresar() throws Exception {
        nuevo = loginAs("productor_nuevo@ccpip.test");
        // CCPIP-291: la Camara ya le habilito la tienda; aqui se prueba lo que arma el productor
        jdbc.update("""
                UPDATE productor SET tienda_habilitada = true
                WHERE usuario_id = (SELECT id FROM usuario WHERE correo = 'productor_nuevo@ccpip.test')""");
        acopioLima = jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = 'Centro Nacional Lima'", Long.class);
        almacenPropio = jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN productor p ON p.id = u.productor_id JOIN usuario x ON x.id = p.usuario_id
                WHERE x.correo = 'productor_nuevo@ccpip.test'""", Long.class);
    }

    // ---------- Tienda (CCPIP-30) ----------

    @Test
    void armaYPublicaSuTiendaDesdeCero() throws Exception {
        long tiendaId = mockMvc.perform(get("/api/productor/tienda").cookie(nuevo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicada").value(false))
                .andExpect(jsonPath("$.comunidad").value("Comunidad Kukama Kukamiria de Nauta"))
                .andReturn().getResponse().getContentAsString().transform(this::idDe);

        // Sin los datos minimos no se publica, y se dice que falta
        mockMvc.perform(post("/api/productor/tienda/publicar").cookie(nuevo))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TIENDA_INCOMPLETA"))
                .andExpect(jsonPath("$.detalles").value(hasItem("La historia de tu comunidad")));
        mockMvc.perform(get("/api/tiendas/{id}", tiendaId)).andExpect(status().isNotFound());

        guardarTienda();
        mockMvc.perform(post("/api/productor/tienda/publicar").cookie(nuevo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicada").value(true));

        // Los cambios se reflejan en la pagina publica de la tienda
        mockMvc.perform(get("/api/tiendas/{id}", tiendaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Asoc. Kukama Nauta"))
                .andExpect(jsonPath("$.pueblo").value("Kukama-Kukamiria"))
                .andExpect(jsonPath("$.cita").value("El río nos da todo."))
                .andExpect(jsonPath("$.socios").value(18));
    }

    @Test
    void laTiendaValidaSusCampos() throws Exception {
        mockMvc.perform(put("/api/productor/tienda").cookie(nuevo).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombreTienda", " ", "socios", -3))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nombreTienda").value("Escribe el nombre de tu tienda"))
                .andExpect(jsonPath("$.campos.socios").exists());
    }

    // ---------- Producto de punta a punta (CCPIP-40, 41, 160, 49) ----------

    @Test
    void publicaUnProductoConHistoriaYPrimerLoteYElConsumidorLoEncuentra() throws Exception {
        guardarTienda();
        mockMvc.perform(post("/api/productor/tienda/publicar").cookie(nuevo)).andExpect(status().isOk());

        // Paso 1, datos: nace en borrador y no se ve en el catalogo
        long id = crearProducto(datosAceite());
        mockMvc.perform(get("/api/productos").param("q", "camu camu kukama").param("disponibles", "false"))
                .andExpect(jsonPath("$.total").value(0));

        // Aun no se puede publicar: falta un lote vendible
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", id).cookie(nuevo))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_PUBLICABLE"))
                .andExpect(jsonPath("$.detalles").value(hasItem("Un lote vigente con stock en un punto de acopio")));

        // Paso 2, historia con la IA (proveedor simulado en pruebas): propone, el productor edita y aprueba
        mockMvc.perform(post("/api/productor/productos/{id}/historia/propuesta", id).cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("respuestas", respuestas()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proveedor").value("simulado"))
                .andExpect(jsonPath("$.texto").value(containsString("Doce familias kukama")));
        mockMvc.perform(post("/api/productor/productos/{id}/historia/aprobar", id).cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "titulo", "El camu camu del río",
                                "textoFinal", "Doce familias kukama cosechamos el camu camu en canoa, en las orillas inundables del río Marañón.",
                                "asistida", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aprobada").value(true))
                .andExpect(jsonPath("$.asistida").value(true));

        // Un lote en el almacen todavia no es vendible
        registrarLote(id, "l-0001", almacenPropio, "30").andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].codigo").value("L-0001"))
                .andExpect(jsonPath("$.items[0].estado").value("EN_ALMACEN"))
                .andExpect(jsonPath("$.enVenta").value(0));
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", id).cookie(nuevo))
                .andExpect(status().isUnprocessableEntity());

        // Paso 3, primer lote en un punto de acopio: ahora si se publica
        registrarLote(id, "L-0002", acopioLima, "60").andExpect(status().isCreated())
                .andExpect(jsonPath("$.enVenta").value(60))
                .andExpect(jsonPath("$.items[?(@.codigo == 'L-0002')].estado").value(hasItem("VENDE_PRIMERO")));
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", id).cookie(nuevo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PUBLICADO"));

        // El stock queda explicado por movimientos de entrada ligados al lote (RNF-15)
        assertThat(jdbc.queryForObject("SELECT sum(cantidad) FROM stock WHERE producto_id = ?", Integer.class, id)).isEqualTo(90);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM movimiento_inventario WHERE producto_id = ? AND tipo = 'ENTRADA' AND lote_id IS NOT NULL",
                Integer.class, id)).isEqualTo(2);

        // El consumidor lo encuentra sin tildes, lo filtra y lee su historia
        mockMvc.perform(get("/api/productos").param("q", "CAMU").param("region", "Loreto"))
                .andExpect(jsonPath("$.items[*].id").value(hasItem((int) id)));
        mockMvc.perform(get("/api/productos").param("linea", "SUPERFRUTOS").param("modalidad", "MAYORISTA"))
                .andExpect(jsonPath("$.items[*].id").value(hasItem((int) id)));
        mockMvc.perform(get("/api/productos/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contenido").value("150 g"))
                .andExpect(jsonPath("$.modalidad").value("AMBAS"))
                .andExpect(jsonPath("$.precioMayorista").value(30.00))
                .andExpect(jsonPath("$.cantidadMinimaMayorista").value(12))
                .andExpect(jsonPath("$.lineas.length()").value(2))
                .andExpect(jsonPath("$.historia.titulo").value("El camu camu del río"))
                .andExpect(jsonPath("$.historia.asistida").value(true))
                .andExpect(jsonPath("$.historia.partes.comunidad").value("doce familias kukama de Nauta"))
                .andExpect(jsonPath("$.tienda.nombre").value("Asoc. Kukama Nauta"));
    }

    @Test
    void editarCambiaElPrecioDejaRegistroYNoRompeLoPublicado() throws Exception {
        long id = productoPublicado();
        Map<String, Object> datos = datosAceite();
        datos.put("precio", 39.90);
        mockMvc.perform(put("/api/productor/productos/{id}", id).cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(datos)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precio").value(39.90));
        mockMvc.perform(get("/api/productos/{id}", id)).andExpect(jsonPath("$.precio").value(39.90));

        String cambios = jdbc.queryForObject("""
                SELECT cambios::text FROM producto_auditoria WHERE producto_id = ? AND accion = 'EDITAR'
                ORDER BY id DESC LIMIT 1""", String.class, id);
        assertThat(cambios).contains("\"precio\"").contains("42.50").contains("39.90");

        // Un producto publicado no puede quedarse sin unidad ni linea
        datos.put("unidad", null);
        datos.put("lineas", List.of());
        mockMvc.perform(put("/api/productor/productos/{id}", id).cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(datos)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCTO_INCOMPLETO"));
    }

    @Test
    void retirarLoSacaDelCatalogoYSePuedeVolverAPublicar() throws Exception {
        long id = productoPublicado();
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT productor_id, id, 'APROBADA', now() FROM producto WHERE id = ?""", id);
        mockMvc.perform(post("/api/productor/productos/{id}/retirar", id).cookie(nuevo))
                .andExpect(jsonPath("$.estado").value("RETIRADO"));
        mockMvc.perform(get("/api/productos/{id}", id)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productos").param("q", "camu camu").param("disponibles", "false"))
                .andExpect(jsonPath("$.items[*].id").value(not(hasItem((int) id))));
        mockMvc.perform(get("/api/productor/productos").param("estado", "RETIRADO").cookie(nuevo))
                .andExpect(jsonPath("$.items[*].id").value(hasItem((int) id)))
                .andExpect(jsonPath("$.conteo.RETIRADO").value(1));

        mockMvc.perform(post("/api/productor/productos/{id}/publicar", id).cookie(nuevo))
                .andExpect(jsonPath("$.estado").value("PUBLICADO"));
        mockMvc.perform(get("/api/productos/{id}", id)).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT accion FROM producto_auditoria WHERE producto_id = ? ORDER BY id",
                String.class, id)).containsSubsequence("CREAR", "PUBLICAR", "RETIRAR", "REPUBLICAR");
    }

    @Test
    void retirarConservaLaOfertaYElHistoricoDelPedido() throws Exception {
        long id = productoPublicado();
        long productorId = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, id);
        long consumidorId = jdbc.queryForObject("SELECT id FROM usuario WHERE correo = 'consumidor@ccpip.test'", Long.class);
        long pedidoId = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total)
                VALUES ('MC-CCPIP315', ?, 'RECOJO', ?, 42.50) RETURNING id""", Long.class,
                consumidorId, acopioLima);
        jdbc.update("""
                INSERT INTO item_pedido (pedido_id, producto_id, productor_id, cantidad, precio_unitario, subtotal)
                VALUES (?, ?, ?, 1, 42.50, 42.50)""", pedidoId, id, productorId);

        mockMvc.perform(post("/api/productor/productos/{id}/retirar", id).cookie(nuevo))
                .andExpect(jsonPath("$.estado").value("RETIRADO"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM producto WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item_pedido WHERE pedido_id = ? AND producto_id = ?",
                Integer.class, pedidoId, id)).isEqualTo(1);
    }

    @Test
    void republicarVuelveAValidarLasCondicionesActuales() throws Exception {
        long id = productoPublicado();
        mockMvc.perform(post("/api/productor/productos/{id}/retirar", id).cookie(nuevo))
                .andExpect(jsonPath("$.estado").value("RETIRADO"));
        jdbc.update("UPDATE lote SET cantidad_actual = 0 WHERE producto_id = ?", id);

        mockMvc.perform(post("/api/productor/productos/{id}/publicar", id).cookie(nuevo))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_PUBLICABLE"));
        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, id)).isEqualTo("RETIRADO");
    }

    @Test
    void republicarRechazaUnaAutorizacionRevocada() throws Exception {
        long id = productoPublicado();
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT productor_id, id, 'APROBADA', now() FROM producto WHERE id = ?""", id);
        mockMvc.perform(post("/api/productor/productos/{id}/retirar", id).cookie(nuevo))
                .andExpect(jsonPath("$.estado").value("RETIRADO"));
        jdbc.update("UPDATE autorizacion_producto SET estado = 'REVOCADA' WHERE producto_id = ?", id);

        mockMvc.perform(post("/api/productor/productos/{id}/publicar", id).cookie(nuevo))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_PUBLICABLE"));
        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, id)).isEqualTo("RETIRADO");
    }

    @Test
    void noExisteBorrarProductos() throws Exception {
        long id = crearProducto(datosAceite());
        mockMvc.perform(delete("/api/productor/productos/{id}", id).cookie(nuevo))
                .andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM producto WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    // ---------- Reglas ----------

    @Test
    void validaLosDatosDelProducto() throws Exception {
        mockMvc.perform(post("/api/productor/productos").cookie(nuevo).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "", "modalidad", "MINORISTA", "precio", -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nombre").value("Escribe el nombre del producto"))
                .andExpect(jsonPath("$.campos.precio").exists());

        Map<String, Object> mayoristaCaro = datosAceite();
        mayoristaCaro.put("precioMayorista", 50);
        mockMvc.perform(post("/api/productor/productos").cookie(nuevo).contentType(MediaType.APPLICATION_JSON)
                        .content(json(mayoristaCaro)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRECIO_MAYORISTA_INVALIDO"));

        Map<String, Object> unidadInventada = datosAceite();
        unidadInventada.put("unidad", "TONEL");
        mockMvc.perform(post("/api/productor/productos").cookie(nuevo).contentType(MediaType.APPLICATION_JSON)
                        .content(json(unidadInventada)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNIDAD_INVALIDA"));
    }

    @Test
    void soloFijaPreciosOrdinariosSinDescuentosNiPromociones() throws Exception {
        // CCPIP-87 (RF-23): un campo de promocion se rechaza, no se ignora en silencio
        Integer antes = jdbc.queryForObject("SELECT count(*) FROM producto", Integer.class);
        Map<String, Object> conDescuento = datosAceite();
        conDescuento.put("descuento", 15);
        mockMvc.perform(post("/api/productor/productos").cookie(nuevo).contentType(MediaType.APPLICATION_JSON)
                        .content(json(conDescuento)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMPO_NO_PERMITIDO"))
                .andExpect(jsonPath("$.error").value(containsString("no existen descuentos ni precios promocionales")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM producto", Integer.class)).isEqualTo(antes);

        long id = crearProducto(datosAceite());
        Map<String, Object> promocion = datosAceite();
        promocion.put("precio", 20.00);
        promocion.put("precioPromocional", 9.90);
        mockMvc.perform(put("/api/productor/productos/{id}", id).cookie(nuevo).contentType(MediaType.APPLICATION_JSON)
                        .content(json(promocion)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CAMPO_NO_PERMITIDO"));
        assertThat(jdbc.queryForObject("SELECT precio FROM producto WHERE id = ?", Double.class, id)).isEqualTo(42.50);

        // Un precio 0 no es un precio ordinario
        Map<String, Object> gratis = datosAceite();
        gratis.put("precio", 0);
        gratis.put("precioMayorista", 0);
        mockMvc.perform(post("/api/productor/productos").cookie(nuevo).contentType(MediaType.APPLICATION_JSON)
                        .content(json(gratis)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.precio").exists())
                .andExpect(jsonPath("$.campos.precioMayorista").exists());

        // Doble tarifa no es promocion: el mayorista debe ser menor, nunca igual al minorista
        Map<String, Object> igual = datosAceite();
        igual.put("precioMayorista", 42.50);
        mockMvc.perform(post("/api/productor/productos").cookie(nuevo).contentType(MediaType.APPLICATION_JSON)
                        .content(json(igual)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PRECIO_MAYORISTA_INVALIDO"));

        // Ni el producto ni la linea de pedido tienen donde guardar un descuento
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name IN ('producto', 'item_pedido')
                  AND (column_name LIKE '%descuento%' OR column_name LIKE '%promo%')""", Integer.class)).isZero();
    }

    @Test
    void validaLosLotes() throws Exception {
        long id = crearProducto(datosAceite());
        // Ayer en Lima, como lo valida el servidor: el CI corre en UTC
        registrarLote(id, "L-1", acopioLima, "10", LocalDate.now(ZoneId.of("America/Lima")).minusDays(1))
                .andExpect(jsonPath("$.code").value("VENCIMIENTO_PASADO"));
        registrarLote(id, "L-1", acopioLima, "10", null)
                .andExpect(jsonPath("$.code").value("FALTA_VENCIMIENTO"));
        registrarLote(id, "L-1", acopioLima, "10").andExpect(status().isCreated());
        registrarLote(id, "l-1", acopioLima, "10").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOTE_REPETIDO"));

        long almacenAjeno = jdbc.queryForObject("""
                SELECT u.id FROM ubicacion u JOIN productor p ON p.id = u.productor_id JOIN usuario x ON x.id = p.usuario_id
                WHERE x.correo = 'productor@ccpip.test'""", Long.class);
        registrarLote(id, "L-2", almacenAjeno, "10").andExpect(jsonPath("$.code").value("UBICACION_INVALIDA"));
        registrarLote(id, "L-3", acopioLima, "0").andExpect(status().isBadRequest());
    }

    @Test
    void unaArtesaniaNoNecesitaVencimiento() throws Exception {
        Map<String, Object> canasta = datosAceite();
        canasta.put("nombre", "Canasta de chambira");
        canasta.put("lineas", List.of("ARTESANIA"));
        long id = crearProducto(canasta);
        registrarLote(id, "L-1", acopioLima, "5", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.requiereVencimiento").value(false));
    }

    @Test
    void unProductorNoVeNiTocaLoAjeno() throws Exception {
        long ajeno = jdbc.queryForObject("SELECT id FROM producto WHERE nombre = 'Aceite de ungurahui 250 ml'", Long.class);
        mockMvc.perform(get("/api/productor/productos/{id}", ajeno).cookie(nuevo)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/productor/productos/{id}", ajeno).cookie(nuevo)
                .contentType(MediaType.APPLICATION_JSON).content(json(datosAceite()))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/productor/productos/{id}/retirar", ajeno).cookie(nuevo)).andExpect(status().isNotFound());
        registrarLote(ajeno, "L-X", acopioLima, "5").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/productor/productos").param("estado", "PUBLICADO").cookie(nuevo))
                .andExpect(jsonPath("$.items[*].id").value(not(hasItem((int) ajeno))));
        assertThat(jdbc.queryForObject("SELECT estado FROM producto WHERE id = ?", String.class, ajeno)).isEqualTo("PUBLICADO");
    }

    @Test
    void unConsumidorNoEntraALaApiDelProductor() throws Exception {
        Cookie consumidor = loginAs("consumidor@ccpip.test");
        mockMvc.perform(get("/api/productor/panel").cookie(consumidor)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/productor/tienda")).andExpect(status().isUnauthorized());
    }

    @Test
    void elPanelResumeSuOperacion() throws Exception {
        long id = productoPublicado();
        // Un lote que vence en 10 dias dispara la alerta de 15 dias
        registrarLote(id, "L-PRONTO", acopioLima, "4", LocalDate.now(java.time.ZoneId.of("America/Lima")).plusDays(10)).andExpect(status().isCreated());
        mockMvc.perform(get("/api/productor/panel").cookie(nuevo))
                .andExpect(jsonPath("$.publicados").value(1))
                .andExpect(jsonPath("$.stockEnVenta").value(64))
                .andExpect(jsonPath("$.lotesPorVencer[0].codigo").value("L-PRONTO"));
        mockMvc.perform(get("/api/productor/productos").param("estado", "PUBLICADO").cookie(nuevo))
                .andExpect(jsonPath("$.items[0].alerta").value("LOTE_POR_VENCER"))
                .andExpect(jsonPath("$.items[0].stock[*].ubicacion").value(hasItem("Centro Nacional Lima")));
    }

    @Test
    void lasDescripcionesAsistidasSoloCuentanSiElProductorAceptoLaSugerencia() throws Exception {
        long id = crearProducto(datosAceite());
        Map<String, Object> datos = datosAceite();
        datos.put("descripcionAsistida", true);
        guardarProducto(id, datos).andExpect(jsonPath("$.descripcionAsistida").value(false));

        // CCPIP-49: un borrador pendiente, sin aceptar, no basta
        String sugerida = proponerDescripcion(id, null);
        assertThat(sugerida).contains("superfrutos");
        guardarProducto(id, datos)
                .andExpect(jsonPath("$.descripcionAsistida").value(false))
                .andExpect(jsonPath("$.descripcionPropuesta").value(sugerida));

        aceptarDescripcion(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcionSugerida").value(sugerida))
                .andExpect(jsonPath("$.descripcionPropuesta").doesNotExist());
        // El productor edita la sugerencia: se guardan la version final y, aparte, la sugerida
        datos.put("descripcion", sugerida + " Cosechado en canoa.");
        guardarProducto(id, datos)
                .andExpect(jsonPath("$.descripcionAsistida").value(true))
                .andExpect(jsonPath("$.descripcion").value(sugerida + " Cosechado en canoa."))
                .andExpect(jsonPath("$.descripcionSugerida").value(sugerida));
    }

    // ---------- Borrador de descripcion de la IA (CCPIP-49) ----------

    @Test
    void aceptarElBorradorRegistraQuienYCuandoSinPublicarNada() throws Exception {
        long id = productoPublicado();
        String sugerida = proponerDescripcion(id, null);

        // Solo sus productos
        aceptarDescripcion(id, loginAs("productor@ccpip.test")).andExpect(status().isNotFound());

        aceptarDescripcion(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PUBLICADO"))
                .andExpect(jsonPath("$.descripcion").value("Fruto deshidratado a baja temperatura."))
                .andExpect(jsonPath("$.descripcionAsistida").value(false));
        // Sin publicacion automatica: el comprador sigue viendo la descripcion que el productor guardo
        assertThat(jdbc.queryForObject("SELECT descripcion FROM producto WHERE id = ?", String.class, id))
                .isEqualTo("Fruto deshidratado a baja temperatura.");
        assertThat(jdbc.queryForObject("""
                SELECT descripcion_aceptada_en IS NOT NULL AND descripcion_aceptada_por = (SELECT id FROM usuario WHERE correo = ?)
                FROM producto WHERE id = ?""", Boolean.class, "productor_nuevo@ccpip.test", id)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT cambios ->> 'descripcionIaAceptada' FROM producto_auditoria
                WHERE producto_id = ? AND accion = 'EDITAR' AND cambios ->> 'descripcionIaAceptada' IS NOT NULL""",
                String.class, id)).isEqualTo(sugerida);

        // Ya no hay borrador pendiente que aceptar
        aceptarDescripcion(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIN_BORRADOR"));
    }

    @Test
    void pedirOtraVersionODescartarlaNoBorraLaSugerenciaAceptada() throws Exception {
        long id = crearProducto(datosAceite());
        String aceptada = proponerDescripcion(id, "Primera version.");
        aceptarDescripcion(id).andExpect(status().isOk());
        Map<String, Object> datos = datosAceite();
        datos.put("descripcion", aceptada);
        datos.put("descripcionAsistida", true);
        guardarProducto(id, datos).andExpect(jsonPath("$.descripcionAsistida").value(true));

        // Otra version que el productor descarta (CCPIP-280)
        proponerDescripcion(id, "Segunda version.");
        mockMvc.perform(post("/api/productor/productos/{id}/ia/rechazar", id).param("borrador", "DESCRIPCION").cookie(nuevo))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/productor/productos/{id}", id).cookie(nuevo))
                .andExpect(jsonPath("$.descripcionSugerida").value(aceptada))
                .andExpect(jsonPath("$.descripcionPropuesta").doesNotExist())
                .andExpect(jsonPath("$.descripcionAsistida").value(true));
        guardarProducto(id, datos).andExpect(jsonPath("$.descripcionAsistida").value(true));

        // Y otra que queda pendiente: no reemplaza a la aceptada hasta que el productor la acepte
        String pendiente = proponerDescripcion(id, "Tercera version.");
        mockMvc.perform(get("/api/productor/productos/{id}", id).cookie(nuevo))
                .andExpect(jsonPath("$.descripcionSugerida").value(aceptada))
                .andExpect(jsonPath("$.descripcionPropuesta").value(pendiente));
        aceptarDescripcion(id).andExpect(jsonPath("$.descripcionSugerida").value(pendiente));
        // Las dos aceptaciones quedan en la auditoria
        assertThat(jdbc.queryForList("""
                SELECT cambios ->> 'descripcionIaAceptada' FROM producto_auditoria
                WHERE producto_id = ? AND cambios ->> 'descripcionIaAceptada' IS NOT NULL ORDER BY id""", String.class, id))
                .containsExactly(aceptada, pendiente);
    }

    @Test
    void laBaseNoGuardaUnaSugerenciaSinQuienNiCuandoLaAcepto() throws Exception {
        long id = crearProducto(datosAceite());
        assertRechazaLaBase(() -> jdbc.update("UPDATE producto SET descripcion_sugerida = 'Texto' WHERE id = ?", id),
                "producto_descripcion_aceptada_completa");
        assertRechazaLaBase(() -> jdbc.update("UPDATE producto SET descripcion_aceptada_en = now() WHERE id = ?", id),
                "producto_descripcion_aceptada_completa");
    }

    @Test
    void rechazarElBorradorDeLaIaLoQuitaYQuedaRegistrado() throws Exception {
        long id = crearProducto(datosAceite());
        String rechazar = "/api/productor/productos/{id}/ia/rechazar";

        // Sin borrador pendiente no hay nada que rechazar
        mockMvc.perform(post(rechazar, id).param("borrador", "DESCRIPCION").cookie(nuevo))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SIN_BORRADOR"));

        // CCPIP-280, descripcion (PW-04)
        mockMvc.perform(post("/api/productor/ia/descripcion").cookie(nuevo).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("productoId", id, "nombre", "Camu camu en polvo", "lineas", List.of("SUPERFRUTOS")))))
                .andExpect(status().isOk());
        mockMvc.perform(post(rechazar, id).param("borrador", "DESCRIPCION").cookie(nuevo)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/productor/productos/{id}", id).cookie(nuevo))
                .andExpect(jsonPath("$.descripcionPropuesta").doesNotExist())
                .andExpect(jsonPath("$.descripcionAsistida").value(false));

        // Historia (PW-05): se rechaza la propuesta, se conservan las respuestas y no se publica nada
        mockMvc.perform(post("/api/productor/productos/{id}/historia/propuesta", id).cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("respuestas", respuestas()))))
                .andExpect(status().isOk());
        mockMvc.perform(post(rechazar, id).param("borrador", "HISTORIA").cookie(nuevo)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/productor/productos/{id}", id).cookie(nuevo))
                .andExpect(jsonPath("$.historia.textoPropuesto").doesNotExist())
                .andExpect(jsonPath("$.historia.aprobada").value(false))
                .andExpect(jsonPath("$.historia.quienes").value(respuestas().get("quienes")));
        mockMvc.perform(post(rechazar, id).param("borrador", "HISTORIA").cookie(nuevo)).andExpect(status().isConflict());

        // RNF-29: los dos rechazos quedan en la auditoria con el texto que propuso la IA
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM producto_auditoria
                WHERE producto_id = ? AND (cambios ->> 'descripcionIaRechazada' IS NOT NULL OR cambios ->> 'historiaIaRechazada' IS NOT NULL)""",
                Integer.class, id)).isEqualTo(2);

        // Solo sus productos y con un borrador valido
        mockMvc.perform(post(rechazar, id).param("borrador", "HISTORIA").cookie(loginAs("productor@ccpip.test")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(rechazar, id).param("borrador", "OTRO").cookie(nuevo)).andExpect(status().isBadRequest());
    }

    @Test
    void destacaHastaCuatroProductosPropios() throws Exception {
        long id = productoPublicado();
        Map<String, Object> tienda = new LinkedHashMap<>();
        tienda.put("nombreTienda", "Asoc. Kukama Nauta");
        tienda.put("destacados", List.of(id));
        mockMvc.perform(put("/api/productor/tienda").cookie(nuevo).contentType(MediaType.APPLICATION_JSON).content(json(tienda)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destacados[0]").value(id))
                .andExpect(jsonPath("$.productosPublicados[0].id").value(id));
        long tiendaId = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, id);
        mockMvc.perform(get("/api/tiendas/{id}", tiendaId)).andExpect(jsonPath("$.destacados[0]").value(id));

        tienda.put("destacados", List.of(1, 2, 3, 4, 5));
        mockMvc.perform(put("/api/productor/tienda").cookie(nuevo).contentType(MediaType.APPLICATION_JSON).content(json(tienda)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.destacados").value("Puedes destacar hasta 4 productos"));
        long ajeno = jdbc.queryForObject("SELECT id FROM producto WHERE nombre = 'Aceite de ungurahui 250 ml'", Long.class);
        tienda.put("destacados", List.of(ajeno));
        mockMvc.perform(put("/api/productor/tienda").cookie(nuevo).contentType(MediaType.APPLICATION_JSON).content(json(tienda)))
                .andExpect(status().isNotFound());
    }

    @Test
    void guardaLaHistoriaComoBorradorSinPublicarla() throws Exception {
        long id = productoPublicado();
        Map<String, Object> borrador = new LinkedHashMap<>(respuestas());
        borrador.put("titulo", "Borrador");
        borrador.put("textoFinal", "Texto aun sin aprobar por la comunidad.");
        mockMvc.perform(put("/api/productor/productos/{id}/historia", id).cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(borrador)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aprobada").value(false))
                .andExpect(jsonPath("$.quienes").value("doce familias kukama de Nauta"));
        mockMvc.perform(get("/api/productos/{id}", id)).andExpect(jsonPath("$.historia").doesNotExist());

        // Una historia demasiado corta no se aprueba
        mockMvc.perform(post("/api/productor/productos/{id}/historia/aprobar", id).cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("textoFinal", "Muy corta"))))
                .andExpect(status().isBadRequest());
        // Escrita a mano: no se marca como asistida aunque se pida
        mockMvc.perform(post("/api/productor/productos/{id}/historia/aprobar", id).cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "textoFinal", "Escrita a mano por la comunidad, con nuestras propias palabras.", "asistida", true))))
                .andExpect(jsonPath("$.asistida").value(false));
    }

    @Test
    void buscaEntreSusProductosYListaUbicaciones() throws Exception {
        long id = crearProducto(datosAceite());
        mockMvc.perform(get("/api/productor/productos").param("estado", "BORRADOR").param("q", "CAMU").cookie(nuevo))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].alerta").value("SIN_STOCK"));
        mockMvc.perform(get("/api/productor/productos").param("estado", "BORRADOR").param("q", "nada").cookie(nuevo))
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/productor/ubicaciones").cookie(nuevo))
                .andExpect(jsonPath("$[0].tipo").value("PUNTO_ACOPIO"))
                .andExpect(jsonPath("$[*].id").value(hasItem((int) almacenPropio)));
        mockMvc.perform(get("/api/productor/productos/{id}/lotes", id).cookie(nuevo))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.requiereVencimiento").value(true));
        mockMvc.perform(post("/api/productor/productos/{id}/retirar", id).cookie(nuevo))
                .andExpect(jsonPath("$.code").value("SOLO_PUBLICADOS"));
    }

    // ---------- Ayudas ----------

    private void guardarTienda() throws Exception {
        Map<String, Object> tienda = new LinkedHashMap<>();
        tienda.put("nombreTienda", "Asoc. Kukama Nauta");
        tienda.put("descripcionCorta", "Superfrutos del río Marañón, cosechados en canoa.");
        tienda.put("historia", "Somos dieciocho familias kukama de Nauta. Cosechamos camu camu y aguaje en las orillas inundables.");
        tienda.put("cita", "El río nos da todo.");
        tienda.put("pueblo", "Kukama-Kukamiria");
        tienda.put("localidad", "Nauta");
        tienda.put("socios", 18);
        mockMvc.perform(put("/api/productor/tienda").cookie(nuevo).contentType(MediaType.APPLICATION_JSON).content(json(tienda)))
                .andExpect(status().isOk());
    }

    private Map<String, Object> datosAceite() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("nombre", "Camu camu kukama en polvo");
        d.put("descripcion", "Fruto deshidratado a baja temperatura.");
        d.put("lineas", List.of("SUPERFRUTOS", "INFUSIONES"));
        d.put("unidad", "BOLSA");
        d.put("contenido", "150 g");
        d.put("modalidad", "AMBAS");
        d.put("precio", 42.50);
        d.put("precioMayorista", 30.00);
        d.put("cantidadMinimaMayorista", 12);
        return d;
    }

    private Map<String, Object> respuestas() {
        return Map.of("quienes", "doce familias kukama de Nauta", "como", "cosechamos en canoa y secamos al sol",
                "significado", "nos permite vivir del río sin talar", "cuidado", "solo cosechamos fruto maduro");
    }

    private long crearProducto(Map<String, Object> datos) throws Exception {
        String body = mockMvc.perform(post("/api/productor/productos").cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(datos)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("BORRADOR"))
                .andReturn().getResponse().getContentAsString();
        return idDe(body);
    }

    private ResultActions guardarProducto(long id, Map<String, Object> datos) throws Exception {
        return mockMvc.perform(put("/api/productor/productos/{id}", id).cookie(nuevo)
                .contentType(MediaType.APPLICATION_JSON).content(json(datos)));
    }

    /** Pide a la IA (simulada) un borrador de descripcion del producto y devuelve su texto; {@code notas} lo distingue. */
    private String proponerDescripcion(long id, String notas) throws Exception {
        Map<String, Object> pedido = new LinkedHashMap<>();
        pedido.put("productoId", id);
        pedido.put("nombre", "Camu camu en polvo");
        pedido.put("lineas", List.of("SUPERFRUTOS"));
        pedido.put("contenido", "150 g");
        pedido.put("notas", notas);
        String body = mockMvc.perform(post("/api/productor/ia/descripcion").cookie(nuevo)
                        .contentType(MediaType.APPLICATION_JSON).content(json(pedido)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("texto").asText();
    }

    private ResultActions aceptarDescripcion(long id) throws Exception {
        return aceptarDescripcion(id, nuevo);
    }

    private ResultActions aceptarDescripcion(long id, Cookie sesion) throws Exception {
        return mockMvc.perform(post("/api/productor/productos/{id}/ia/descripcion/aceptar", id).cookie(sesion));
    }

    private long productoPublicado() throws Exception {
        guardarTienda();
        mockMvc.perform(post("/api/productor/tienda/publicar").cookie(nuevo)).andExpect(status().isOk());
        long id = crearProducto(datosAceite());
        registrarLote(id, "L-0001", acopioLima, "60").andExpect(status().isCreated());
        mockMvc.perform(post("/api/productor/productos/{id}/publicar", id).cookie(nuevo)).andExpect(status().isOk());
        return id;
    }

    private ResultActions registrarLote(long productoId, String codigo, long ubicacion, String cantidad) throws Exception {
        return registrarLote(productoId, codigo, ubicacion, cantidad, LocalDate.now(java.time.ZoneId.of("America/Lima")).plusMonths(8));
    }

    private ResultActions registrarLote(long productoId, String codigo, long ubicacion, String cantidad, LocalDate vence)
            throws Exception {
        Map<String, Object> lote = new LinkedHashMap<>();
        lote.put("codigo", codigo);
        lote.put("cantidad", cantidad);
        lote.put("fechaProduccion", LocalDate.now(java.time.ZoneId.of("America/Lima")).minusDays(5).toString());
        lote.put("fechaVencimiento", vence == null ? null : vence.toString());
        lote.put("ubicacionId", ubicacion);
        return mockMvc.perform(post("/api/productor/productos/{id}/lotes", productoId).cookie(nuevo)
                .contentType(MediaType.APPLICATION_JSON).content(json(lote)));
    }

    private long idDe(String json) {
        try {
            JsonNode nodo = objectMapper.readTree(json);
            return nodo.get("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
