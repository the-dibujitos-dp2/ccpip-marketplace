package pe.ccpip.carrito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
 * CCPIP-58, 155, 268 y 156: el comprador agrega una oferta especifica con su cantidad, consulta
 * su carrito, cambia cantidades y quita ofertas, siempre con precio y stock revalidados. Cada
 * producto de prueba nace con un lote de 5 unidades (ver {@link IntegrationTest#productoConLote}).
 */
@Transactional
class CarritoControllerTest extends IntegrationTest {

    private static final LocalDate EN_UN_MES = LocalDate.now(ZoneId.of("America/Lima")).plusDays(30);

    private Cookie consumidor;
    private long oferta;

    @BeforeEach
    void preparar() throws Exception {
        consumidor = loginAs("consumidor@ccpip.test");
        oferta = productoConLote("Sacha inchi tostado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
    }

    @Test
    void agregaUnaOfertaDisponibleSinReservarNiDescontarExistencias() throws Exception {
        agregar(consumidor, oferta, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productoId").value(oferta))
                .andExpect(jsonPath("$.cantidad").value(2))
                .andExpect(jsonPath("$.articulos").value(1));

        // RNF-35: el lote conserva sus 5 unidades y no hay movimientos de inventario nuevos
        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id = ?", Integer.class, oferta))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE producto_id = ?",
                Integer.class, oferta)).isZero();
        // El carrito queda persistido en la base, no en el navegador
        assertThat(jdbc.queryForObject("""
                SELECT i.cantidad FROM item_carrito i JOIN carrito c ON c.id = i.carrito_id
                JOIN usuario u ON u.id = c.usuario_id WHERE u.correo = 'consumidor@ccpip.test' AND i.producto_id = ?""",
                Integer.class, oferta)).isEqualTo(2);
    }

    @Test
    void agregarLaMismaOfertaSumaCantidadSinDuplicarla() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        agregar(consumidor, oferta, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(5))
                .andExpect(jsonPath("$.articulos").value(1));

        long otra = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        agregar(consumidor, otra, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulos").value(2));
    }

    @Test
    void noPermiteSuperarElStockVendibleContandoLoQueYaEstaEnElCarrito() throws Exception {
        agregar(consumidor, oferta, 6)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.error").value("Solo quedan 5 unidades disponibles."));

        agregar(consumidor, oferta, 4).andExpect(status().isOk());
        agregar(consumidor, oferta, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Solo quedan 5 unidades y ya tienes 4 en tu carrito."));
        // Lo rechazado no altera el carrito
        agregar(consumidor, oferta, 1).andExpect(jsonPath("$.cantidad").value(5));
    }

    @Test
    void laCantidadEsUnEnteroDeAlMenosUnaUnidad() throws Exception {
        agregar(consumidor, oferta, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_INVALIDOS"))
                .andExpect(jsonPath("$.campos.cantidad").value("La cantidad mínima es una unidad"));
        agregar(consumidor, oferta, -2).andExpect(status().isBadRequest());
        agregar(consumidor, oferta, 1.5)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.cantidad").value("La cantidad debe ser un número entero de unidades"));
        agregar(consumidor, oferta, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.cantidad").value("Indica la cantidad"));
    }

    @Test
    void soloSeAgreganOfertasVisiblesYVendibles() throws Exception {
        agregar(consumidor, 999_999_999L, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCTO_NO_ENCONTRADO"));

        long borrador = productoConLote("Borrador de aceite", "BORRADOR", "PUNTO_ACOPIO", EN_UN_MES);
        agregar(consumidor, borrador, 1).andExpect(status().isNotFound());

        // Un lote vencido o que sigue en el almacen del productor no se vende
        long vencido = productoConLote("Camu camu vencido", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES.minusDays(31));
        agregar(consumidor, vencido, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGOTADO"));
        long enAlmacen = productoConLote("Aguaje en almacen", "PUBLICADO", "ALMACEN_PRODUCTOR", EN_UN_MES);
        agregar(consumidor, enAlmacen, 1).andExpect(jsonPath("$.code").value("AGOTADO"));
    }

    @Test
    void unaOfertaSoloMayoristaAunNoSeAgrega() throws Exception {
        jdbc.update("""
                UPDATE producto SET modalidad = 'MAYORISTA', precio = NULL, precio_mayorista = 15,
                                    cantidad_minima_mayorista = 10 WHERE id = ?""", oferta);
        agregar(consumidor, oferta, 1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOLO_MAYORISTA"));
    }

    @Test
    void soloElCompradorConSesionUsaElCarrito() throws Exception {
        agregar(null, oferta, 1)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SIN_SESION"));
        agregar(loginAs("productor@ccpip.test"), oferta, 1)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item_carrito WHERE producto_id = ?", Integer.class, oferta))
                .isZero();
    }

    // ---------- Consultar el carrito (CCPIP-155) ----------

    @Test
    void unCarritoNuevoEstaVacio() throws Exception {
        consultar(consumidor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.unidades").value(0))
                .andExpect(jsonPath("$.comunidades").value(0))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void muestraArticulosCantidadesSubtotalesYTotal() throws Exception {
        long otra = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES, "productor2@ccpip.test");
        jdbc.update("UPDATE producto SET precio = 15.50 WHERE id = ?", otra);
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        agregar(consumidor, otra, 3).andExpect(status().isOk());

        consultar(consumidor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                // En el orden en que se agregaron
                .andExpect(jsonPath("$.items[0].productoId").value(oferta))
                .andExpect(jsonPath("$.items[0].nombre").value("Sacha inchi tostado"))
                .andExpect(jsonPath("$.items[0].unidad").value("Gramo"))
                .andExpect(jsonPath("$.items[0].linea").value("SUPERFRUTOS"))
                // La cosecha de su lote vendible, como en la ficha (Figma CW-05)
                .andExpect(jsonPath("$.items[0].cosecha").value(EN_UN_MES.minusDays(60).toString()))
                .andExpect(jsonPath("$.items[0].tienda.nombre").value("Asoc. Nii Joi"))
                .andExpect(jsonPath("$.items[0].precio").value(20))
                .andExpect(jsonPath("$.items[0].cantidad").value(2))
                .andExpect(jsonPath("$.items[0].subtotal").value(40))
                .andExpect(jsonPath("$.items[0].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.items[0].disponibles").doesNotExist())
                .andExpect(jsonPath("$.items[1].tienda.nombre").value("C.N. Yarina"))
                .andExpect(jsonPath("$.items[1].subtotal").value(46.5))
                .andExpect(jsonPath("$.unidades").value(5))
                .andExpect(jsonPath("$.comunidades").value(2))
                .andExpect(jsonPath("$.total").value(86.5));
    }

    @Test
    void elPrecioSeRevalidaEnCadaConsulta() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        jdbc.update("UPDATE producto SET precio = 25 WHERE id = ?", oferta);
        consultar(consumidor)
                .andExpect(jsonPath("$.items[0].precio").value(25))
                .andExpect(jsonPath("$.items[0].subtotal").value(50))
                .andExpect(jsonPath("$.total").value(50));
    }

    @Test
    void loQueYaNoSePuedeComprarSeMuestraPeroNoSumaAlTotal() throws Exception {
        long otra = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        long retirada = productoConLote("Aceite retirado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        agregar(consumidor, oferta, 4).andExpect(status().isOk());
        agregar(consumidor, otra, 1).andExpect(status().isOk());
        agregar(consumidor, retirada, 1).andExpect(status().isOk());

        // Otros compradores se llevaron unidades y el productor retiro una oferta
        jdbc.update("UPDATE lote SET cantidad_actual = 2 WHERE producto_id = ?", oferta);
        jdbc.update("UPDATE lote SET cantidad_actual = 0 WHERE producto_id = ?", otra);
        jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", retirada);

        consultar(consumidor)
                .andExpect(jsonPath("$.items[0].estado").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.items[0].disponibles").value(2))
                .andExpect(jsonPath("$.items[0].subtotal").doesNotExist())
                .andExpect(jsonPath("$.items[1].estado").value("AGOTADO"))
                .andExpect(jsonPath("$.items[2].estado").value("NO_DISPONIBLE"))
                .andExpect(jsonPath("$.items[2].nombre").value("Aceite retirado"))
                .andExpect(jsonPath("$.unidades").value(6))
                .andExpect(jsonPath("$.comunidades").value(0))
                .andExpect(jsonPath("$.total").value(0));

        // Consultar no reserva ni descuenta (RNF-35)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE producto_id IN (?, ?, ?)",
                Integer.class, oferta, otra, retirada)).isZero();
    }

    @Test
    void unaOfertaQuePasaASoloMayoristaQuedaMarcada() throws Exception {
        agregar(consumidor, oferta, 1).andExpect(status().isOk());
        jdbc.update("""
                UPDATE producto SET modalidad = 'MAYORISTA', precio = NULL, precio_mayorista = 15,
                                    cantidad_minima_mayorista = 10 WHERE id = ?""", oferta);
        consultar(consumidor)
                .andExpect(jsonPath("$.items[0].estado").value("SOLO_MAYORISTA"))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void elCarritoSobreviveAlCierreDeSesion() throws Exception {
        agregar(consumidor, oferta, 3).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/logout").cookie(consumidor)).andExpect(status().is2xxSuccessful());
        consultar(consumidor).andExpect(status().isUnauthorized());

        consultar(loginAs("consumidor@ccpip.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productoId").value(oferta))
                .andExpect(jsonPath("$.items[0].cantidad").value(3));
    }

    @Test
    void cadaCompradorVeSoloSuCarrito() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'otro_comprador@ccpip.test', contrasena_hash, 'Otro comprador', rol_id, true
                FROM usuario WHERE correo = 'consumidor@ccpip.test'""");
        agregar(consumidor, oferta, 2).andExpect(status().isOk());

        consultar(loginAs("otro_comprador@ccpip.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void soloElCompradorConSesionConsultaSuCarrito() throws Exception {
        consultar(null).andExpect(status().isUnauthorized());
        consultar(loginAs("productor@ccpip.test")).andExpect(status().isForbidden());
    }

    // ---------- Modificar la cantidad (CCPIP-268) ----------

    @Test
    void reemplazaLaCantidadYDevuelveElCarritoRecalculadoSinTocarExistencias() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());

        modificar(consumidor, oferta, 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].cantidad").value(4))
                .andExpect(jsonPath("$.items[0].subtotal").value(80))
                .andExpect(jsonPath("$.unidades").value(4))
                .andExpect(jsonPath("$.total").value(80));
        // Bajar tambien es reemplazar, no restar
        modificar(consumidor, oferta, 1)
                .andExpect(jsonPath("$.items[0].cantidad").value(1))
                .andExpect(jsonPath("$.total").value(20));

        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id = ?", Integer.class, oferta))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE producto_id = ?",
                Integer.class, oferta)).isZero();
    }

    @Test
    void alModificarRevalidaPrecioYStock() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        jdbc.update("UPDATE producto SET precio = 25 WHERE id = ?", oferta);

        modificar(consumidor, oferta, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].precio").value(25))
                .andExpect(jsonPath("$.items[0].subtotal").value(75));
        modificar(consumidor, oferta, 6)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.error").value("Solo quedan 5 unidades disponibles."));
        // Lo rechazado no cambia el carrito
        consultar(consumidor).andExpect(jsonPath("$.items[0].cantidad").value(3));
    }

    @Test
    void bajarLaCantidadResuelveElStockInsuficiente() throws Exception {
        agregar(consumidor, oferta, 4).andExpect(status().isOk());
        jdbc.update("UPDATE lote SET cantidad_actual = 2 WHERE producto_id = ?", oferta);
        consultar(consumidor).andExpect(jsonPath("$.items[0].estado").value("STOCK_INSUFICIENTE"));

        modificar(consumidor, oferta, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.total").value(40));
    }

    @Test
    void laNuevaCantidadEsUnEnteroDeAlMenosUnaUnidad() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        modificar(consumidor, oferta, 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.cantidad").value("La cantidad mínima es una unidad"));
        modificar(consumidor, oferta, 1.5)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.cantidad").value("La cantidad debe ser un número entero de unidades"));
        modificar(consumidor, oferta, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.cantidad").value("Indica la cantidad"));
        consultar(consumidor).andExpect(jsonPath("$.items[0].cantidad").value(2));
    }

    @Test
    void soloSeModificaLoQueEstaEnElCarritoYSePuedeComprar() throws Exception {
        modificar(consumidor, oferta, 1)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NO_ENCONTRADO"));

        long retirada = productoConLote("Aceite retirado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        long agotada = productoConLote("Cacao agotado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        agregar(consumidor, oferta, 1).andExpect(status().isOk());
        agregar(consumidor, retirada, 1).andExpect(status().isOk());
        agregar(consumidor, agotada, 1).andExpect(status().isOk());
        jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", retirada);
        jdbc.update("UPDATE lote SET cantidad_actual = 0 WHERE producto_id = ?", agotada);
        jdbc.update("""
                UPDATE producto SET modalidad = 'MAYORISTA', precio = NULL, precio_mayorista = 15,
                                    cantidad_minima_mayorista = 10 WHERE id = ?""", oferta);

        modificar(consumidor, retirada, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_DISPONIBLE"));
        modificar(consumidor, agotada, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGOTADO"));
        modificar(consumidor, oferta, 2)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOLO_MAYORISTA"));
    }

    @Test
    void soloElCompradorModificaSuPropioCarrito() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'otro_comprador@ccpip.test', contrasena_hash, 'Otro comprador', rol_id, true
                FROM usuario WHERE correo = 'consumidor@ccpip.test'""");

        // Otro comprador no puede tocar mi carrito: para el, esa oferta no esta en su carrito
        modificar(loginAs("otro_comprador@ccpip.test"), oferta, 1).andExpect(status().isNotFound());
        modificar(null, oferta, 1).andExpect(status().isUnauthorized());
        modificar(loginAs("productor@ccpip.test"), oferta, 1).andExpect(status().isForbidden());
        consultar(consumidor).andExpect(jsonPath("$.items[0].cantidad").value(2));
    }

    // ---------- Quitar del carrito (CCPIP-156) ----------

    @Test
    void quitaUnaOfertaYRecalculaSinMovimientoDeInventario() throws Exception {
        long otra = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES, "productor2@ccpip.test");
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        agregar(consumidor, otra, 1).andExpect(status().isOk());

        quitar(consumidor, oferta)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productoId").value(otra))
                .andExpect(jsonPath("$.unidades").value(1))
                .andExpect(jsonPath("$.comunidades").value(1))
                .andExpect(jsonPath("$.total").value(20));

        // RNF-35: el lote no cambia ni se registran movimientos
        assertThat(jdbc.queryForObject("SELECT cantidad_actual FROM lote WHERE producto_id = ?", Integer.class, oferta))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimiento_inventario WHERE producto_id IN (?, ?)",
                Integer.class, oferta, otra)).isZero();
    }

    @Test
    void quitarLaUltimaOfertaDejaElCarritoVacioYSePuedeVolverAAgregar() throws Exception {
        agregar(consumidor, oferta, 3).andExpect(status().isOk());
        quitar(consumidor, oferta)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.unidades").value(0))
                .andExpect(jsonPath("$.total").value(0));

        // Volver a agregar empieza de cero, no recupera la cantidad anterior
        agregar(consumidor, oferta, 1).andExpect(jsonPath("$.cantidad").value(1));
    }

    @Test
    void tambienSeQuitanLasOfertasQueYaNoSePuedenComprar() throws Exception {
        long retirada = productoConLote("Aceite retirado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        agregar(consumidor, oferta, 1).andExpect(status().isOk());
        agregar(consumidor, retirada, 1).andExpect(status().isOk());
        jdbc.update("UPDATE producto SET estado = 'RETIRADO' WHERE id = ?", retirada);
        jdbc.update("UPDATE lote SET cantidad_actual = 0 WHERE producto_id = ?", oferta);

        quitar(consumidor, retirada).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        quitar(consumidor, oferta).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void soloSeQuitaLoQueEstaEnElCarrito() throws Exception {
        quitar(consumidor, oferta)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NO_ENCONTRADO"));
        agregar(consumidor, oferta, 1).andExpect(status().isOk());
        quitar(consumidor, oferta).andExpect(status().isOk());
        // Quitar dos veces: la segunda ya no lo encuentra
        quitar(consumidor, oferta).andExpect(status().isNotFound());
    }

    @Test
    void soloElCompradorQuitaDeSuPropioCarrito() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'otro_comprador@ccpip.test', contrasena_hash, 'Otro comprador', rol_id, true
                FROM usuario WHERE correo = 'consumidor@ccpip.test'""");

        quitar(loginAs("otro_comprador@ccpip.test"), oferta).andExpect(status().isNotFound());
        quitar(null, oferta).andExpect(status().isUnauthorized());
        quitar(loginAs("productor@ccpip.test"), oferta).andExpect(status().isForbidden());
        consultar(consumidor).andExpect(jsonPath("$.items[0].cantidad").value(2));
    }

    // --- CCPIP-267: la sesion expira por inactividad, el carrito no ---

    @Test
    void alExpirarLaSesionElCarritoSeConservaParaLaProximaSesion() throws Exception {
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        jdbc.update("""
                UPDATE sesion SET ultimo_acceso = now() - interval '31 minutes'
                WHERE usuario_id = (SELECT id FROM usuario WHERE correo = 'consumidor@ccpip.test')""");

        // Con la sesion expirada no se ve ni se cambia el carrito...
        consultar(consumidor)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESION_EXPIRADA"));
        agregar(consumidor, oferta, 1).andExpect(status().isUnauthorized());

        // ...pero no se pierde: al volver a ingresar sigue igual
        consultar(loginAs("consumidor@ccpip.test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].cantidad").value(2));
    }

    // --- CCPIP-269: solo se compra lo autorizado a un productor con cuenta bancaria activa ---

    @Test
    void sinCuentaBancariaActivaLaOfertaNoSeAgregaNiSeModifica() throws Exception {
        agregar(consumidor, oferta, 1).andExpect(status().isOk());
        jdbc.update("UPDATE cuenta_bancaria SET estado = 'DESACTIVADA', motivo_desactivacion = 'OTRO', desactivada_en = now() WHERE productor_id = (SELECT productor_id FROM producto WHERE id = ?)",
                oferta);

        agregar(consumidor, oferta, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENTA_PAUSADA"));
        modificar(consumidor, oferta, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENTA_PAUSADA"));
        // Lo rechazado no altera el carrito, y se puede quitar
        consultar(consumidor).andExpect(jsonPath("$.items[0].cantidad").value(1));
        quitar(consumidor, oferta).andExpect(status().isOk());
    }

    @Test
    void unaAutorizacionNoAprobadaOVencidaPausaLaVenta() throws Exception {
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));
        for (String estado : new String[] {"SOLICITADA", "RECHAZADA", "REVOCADA"}) {
            long otra = productoConLote("Aceite " + estado, "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
            jdbc.update("UPDATE autorizacion_producto SET estado = ? WHERE producto_id = ?", estado, otra);
            agregar(consumidor, otra, 1).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VENTA_PAUSADA"));
        }

        long vencida = productoConLote("Aceite con autorizacion vencida", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        jdbc.update("UPDATE autorizacion_producto SET vigente_hasta = ? WHERE producto_id = ?", hoy.minusDays(1), vencida);
        agregar(consumidor, vencida, 1).andExpect(status().isConflict());

        // El ultimo dia de vigencia todavia se vende
        jdbc.update("UPDATE autorizacion_producto SET vigente_hasta = ? WHERE producto_id = ?", hoy, oferta);
        agregar(consumidor, oferta, 1).andExpect(status().isOk());

        jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?", vencida);
        agregar(consumidor, vencida, 1).andExpect(status().isConflict());
    }

    // --- CCPIP-270: la autorizacion es por producto y por productor ---

    @Test
    void autorizarUnProductoNoAutorizaLosDemasDeSuLinea() throws Exception {
        // Dos productos del mismo productor y de la misma linea (SUPERFRUTOS); solo uno queda autorizado
        long autorizado = productoConLote("Camu camu autorizado", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        long sinAutorizar = productoConLote("Aguaje sin autorizar", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?", sinAutorizar);

        agregar(consumidor, autorizado, 1).andExpect(status().isOk());
        agregar(consumidor, sinAutorizar, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENTA_PAUSADA"));
        mockMvc.perform(get("/api/productos/{id}", autorizado)).andExpect(jsonPath("$.comprable").value(true));
        mockMvc.perform(get("/api/productos/{id}", sinAutorizar)).andExpect(jsonPath("$.comprable").value(false));
    }

    @Test
    void laAutorizacionDeOtroProductorNoVale() throws Exception {
        long producto = productoConLote("Sacha inchi de productor@", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        // La unica autorizacion aprobada para este producto es de otro productor: no habilita esta oferta
        jdbc.update("DELETE FROM autorizacion_producto WHERE producto_id = ?", producto);
        jdbc.update("""
                INSERT INTO autorizacion_producto (productor_id, producto_id, estado, decidido_en)
                SELECT pr.id, ?, 'APROBADA', now() FROM productor pr
                WHERE pr.id <> (SELECT productor_id FROM producto WHERE id = ?) ORDER BY pr.id LIMIT 1""", producto, producto);

        agregar(consumidor, producto, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VENTA_PAUSADA"));
        mockMvc.perform(get("/api/productos/{id}", producto)).andExpect(jsonPath("$.comprable").value(false));
    }

    @Test
    void laVentaPausadaSeMuestraEnElCarritoPeroNoSumaAlTotal() throws Exception {
        long otra = productoConLote("Cacao en grano", "PUBLICADO", "PUNTO_ACOPIO", EN_UN_MES);
        agregar(consumidor, oferta, 2).andExpect(status().isOk());
        agregar(consumidor, otra, 1).andExpect(status().isOk());

        // RF-142: se revalida al consultar, no solo al agregar
        jdbc.update("UPDATE autorizacion_producto SET estado = 'REVOCADA', motivo = 'Certificado vencido' WHERE producto_id = ?",
                oferta);

        consultar(consumidor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].estado").value("VENTA_PAUSADA"))
                .andExpect(jsonPath("$.items[0].subtotal").doesNotExist())
                .andExpect(jsonPath("$.items[1].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.unidades").value(3))
                .andExpect(jsonPath("$.total").value(20));

        // Con la autorizacion aprobada de nuevo, vuelve a sumar
        jdbc.update("UPDATE autorizacion_producto SET estado = 'APROBADA' WHERE producto_id = ?", oferta);
        consultar(consumidor)
                .andExpect(jsonPath("$.items[0].estado").value("DISPONIBLE"))
                .andExpect(jsonPath("$.total").value(60));
    }

    private ResultActions quitar(Cookie sesion, long productoId) throws Exception {
        var peticion = delete("/api/carrito/items/{id}", productoId);
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private ResultActions modificar(Cookie sesion, long productoId, Number cantidad) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("cantidad", cantidad);
        var peticion = put("/api/carrito/items/{id}", productoId).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo));
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private ResultActions consultar(Cookie sesion) throws Exception {
        var peticion = get("/api/carrito");
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    private ResultActions agregar(Cookie sesion, long productoId, Number cantidad) throws Exception {
        Map<String, Object> cuerpo = new HashMap<>();
        cuerpo.put("productoId", productoId);
        cuerpo.put("cantidad", cantidad);
        var peticion = post("/api/carrito/items").contentType(MediaType.APPLICATION_JSON).content(json(cuerpo));
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }
}
