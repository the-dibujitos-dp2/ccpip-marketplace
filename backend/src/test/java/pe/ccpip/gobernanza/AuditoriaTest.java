package pe.ccpip.gobernanza;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-97 (RF-84): el superadministrador consulta y filtra la auditoria de operaciones sensibles, con
 * usuario, rol, accion, entidad, fecha y resultado. Los registros son inmutables y la consulta es solo del
 * superadministrador. Transaccional: nada llega a otras pruebas.
 */
@Transactional
class AuditoriaTest extends IntegrationTest {

    private static final String AUDITORIA = "/api/admin/auditoria";

    private Cookie sudo;
    private long miel;
    private String nombreMiel;
    private String numeroPedido;
    private long edicion;
    private long pagoRechazado;
    private long decision;
    private long cambioCatalogo;

    @BeforeEach
    void preparar() {
        nombreMiel = "Miel auditada " + UUID.randomUUID();
        miel = productoConLote(nombreMiel, "PUBLICADO", "PUNTO_ACOPIO", hoy().plusMonths(6));
        long productor = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, miel);

        // Producto (CCPIP-47): el productor lo edito
        edicion = jdbc.queryForObject("""
                INSERT INTO producto_auditoria (producto_id, usuario_id, accion, cambios)
                VALUES (?, ?, 'EDITAR', '{"precio": [20, 22]}'::jsonb) RETURNING id""",
                Long.class, miel, usuario("productor@ccpip.test"));
        // Pago (CCPIP-163): un intento rechazado tambien queda
        numeroPedido = "AUD-" + UUID.randomUUID().toString().substring(0, 8);
        long pedido = jdbc.queryForObject("""
                INSERT INTO pedido (numero, usuario_id, modalidad, ubicacion_origen_id, total, estado, confirmado_en)
                SELECT ?, u.id, 'RECOJO', l.id, 20.00, 'PENDIENTE_PAGO', NULL
                FROM usuario u, ubicacion l WHERE u.correo = 'consumidor@ccpip.test' AND l.nombre = 'Centro Nacional Lima'
                RETURNING id""", Long.class, numeroPedido);
        pagoRechazado = jdbc.queryForObject("""
                INSERT INTO pago (pedido_id, clave_idempotencia, metodo, monto, estado, pasarela, marca_tarjeta,
                                  codigo_respuesta, resuelto_en)
                SELECT id, ?, 'TARJETA', total, 'RECHAZADO', 'SIMULADA', 'VISA', 'FONDOS_INSUFICIENTES', now()
                FROM pedido WHERE id = ? RETURNING id""", Long.class, UUID.randomUUID(), pedido);
        // Gobernanza (CCPIP-126): el subadministrador habilito la tienda
        decision = decisionSobreProductor(productor, usuario("sub_admin@ccpip.test"), 0);
        // Catalogo maestro: el superadministrador edito una linea
        cambioCatalogo = jdbc.queryForObject("""
                INSERT INTO catalogo_maestro_auditoria (entidad, entidad_id, usuario_id, accion)
                SELECT 'LINEA', id, ?, 'EDITAR' FROM linea_producto WHERE codigo = 'SUPERFRUTOS' RETURNING id""",
                Long.class, usuario("sudo@ccpip.test"));
    }

    @Test
    void reuneLasOperacionesDeCadaModuloConUsuarioRolEntidadYResultado() throws Exception {
        sudo = loginAs("sudo@ccpip.test");
        String nombreLinea = jdbc.queryForObject("SELECT nombre FROM linea_producto WHERE codigo = 'SUPERFRUTOS'", String.class);

        auditoria("?usuario=productor@ccpip.test&accion=PRODUCTO_EDITAR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desde").value(hoy().minusDays(29).toString()))
                .andExpect(jsonPath("$.hasta").value(hoy().toString()))
                .andExpect(campo("PA-" + edicion, "rol").value(hasItem("PRODUCTOR")))
                .andExpect(campo("PA-" + edicion, "correo").value(hasItem("productor@ccpip.test")))
                .andExpect(campo("PA-" + edicion, "entidad").value(hasItem("PRODUCTO")))
                .andExpect(campo("PA-" + edicion, "entidadId").value(hasItem((int) miel)))
                .andExpect(campo("PA-" + edicion, "detalle").value(hasItem(nombreMiel)))
                .andExpect(campo("PA-" + edicion, "resultado").value(hasItem("EXITOSA")));

        auditoria("?accion=PAGO&resultado=RECHAZADO&usuario=consumidor")
                .andExpect(campo("PG-" + pagoRechazado, "rol").value(hasItem("CONSUMIDOR")))
                .andExpect(campo("PG-" + pagoRechazado, "entidad").value(hasItem("PEDIDO")))
                .andExpect(campo("PG-" + pagoRechazado, "detalle").value(hasItem(numeroPedido)))
                .andExpect(campo("PG-" + pagoRechazado, "resultado").value(hasItem("RECHAZADO")));

        auditoria("?entidad=PRODUCTOR&rol=SUB_ADMIN")
                .andExpect(campo("PD-" + decision, "accion").value(hasItem("HABILITAR_TIENDA")))
                .andExpect(campo("PD-" + decision, "rol").value(hasItem("SUB_ADMIN")))
                .andExpect(campo("PD-" + decision, "resultado").value(hasItem("EXITOSA")));

        auditoria("?entidad=LINEA_MAESTRA&rol=SUPER_ADMIN")
                .andExpect(campo("CM-" + cambioCatalogo, "accion").value(hasItem("CATALOGO_EDITAR")))
                .andExpect(campo("CM-" + cambioCatalogo, "detalle").value(hasItem(nombreLinea)));
    }

    @Test
    void losFiltrosSeCombinanYExcluyenLoQueNoCumple() throws Exception {
        sudo = loginAs("sudo@ccpip.test");
        auditoria("?accion=PAGO&resultado=APROBADO")
                .andExpect(jsonPath("$.registros[*].clave", not(hasItem("PG-" + pagoRechazado))));
        auditoria("?rol=CONSUMIDOR")
                .andExpect(jsonPath("$.registros[*].clave", not(hasItem("PA-" + edicion))));
        auditoria("?usuario=nadie-con-este-nombre")
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.totalPaginas").value(1));
        // Lo escrito se busca tal cual: % no es comodin
        auditoria("?usuario=%25").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void filtraPorPeriodo() throws Exception {
        sudo = loginAs("sudo@ccpip.test");
        long productor = jdbc.queryForObject("SELECT productor_id FROM producto WHERE id = ?", Long.class, miel);
        long antigua = decisionSobreProductor(productor, usuario("sub_admin@ccpip.test"), 40);

        // Por defecto, los ultimos 30 dias
        auditoria("?entidad=PRODUCTOR").andExpect(jsonPath("$.registros[*].clave", not(hasItem("PD-" + antigua))));
        auditoria("?entidad=PRODUCTOR&desde=" + hoy().minusDays(45) + "&hasta=" + hoy().minusDays(35))
                .andExpect(jsonPath("$.registros[*].clave", hasItem("PD-" + antigua)))
                .andExpect(jsonPath("$.registros[*].clave", not(hasItem("PD-" + decision))));
    }

    @Test
    void ofreceLasAccionesDelPeriodoYPagina() throws Exception {
        sudo = loginAs("sudo@ccpip.test");
        auditoria("")
                .andExpect(jsonPath("$.acciones", hasItems("PAGO", "PRODUCTO_EDITAR", "HABILITAR_TIENDA", "CATALOGO_EDITAR")))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamano").value(AuditoriaService.TAMANO))
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(4)));
        auditoria("?pagina=-1").andExpect(status().isBadRequest());
    }

    @Test
    void elPeriodoYLosFiltrosSeValidan() throws Exception {
        sudo = loginAs("sudo@ccpip.test");
        auditoria("?desde=" + hoy() + "&hasta=" + hoy().minusDays(1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RANGO_INVALIDO"));
        auditoria("?desde=" + hoy().minusYears(2)).andExpect(status().isUnprocessableEntity());
        auditoria("?rol=OTRO").andExpect(status().isBadRequest());
        auditoria("?entidad=OTRA").andExpect(status().isBadRequest());
        auditoria("?resultado=QUIZAS").andExpect(status().isBadRequest());
    }

    @Test
    void losRegistrosSonInmutables() {
        assertRechazaLaBase(() -> jdbc.update("UPDATE producto_auditoria SET accion = 'CREAR' WHERE id = ?", edicion),
                "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM producto_auditoria WHERE id = ?", edicion), "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM catalogo_maestro_auditoria WHERE id = ?", cambioCatalogo),
                "solo insercion");
        assertRechazaLaBase(() -> jdbc.update("DELETE FROM pago WHERE id = ?", pagoRechazado), "no se borra");
    }

    // --- Permisos (RNF-04, RNF-05) ---

    @ParameterizedTest
    @ValueSource(strings = {"sub_admin@ccpip.test", "productor@ccpip.test", "consumidor@ccpip.test", "admin_acopio@ccpip.test"})
    void esSoloDelSuperadministrador(String correo) throws Exception {
        mockMvc.perform(get(AUDITORIA).cookie(loginAs(correo))).andExpect(status().isForbidden());
    }

    @Test
    void sinSesionPideIngresar() throws Exception {
        mockMvc.perform(get(AUDITORIA)).andExpect(status().isUnauthorized());
    }

    // --- Apoyo ---

    private ResultActions auditoria(String consulta) throws Exception {
        return mockMvc.perform(get(AUDITORIA + consulta).cookie(sudo));
    }

    private static org.springframework.test.web.servlet.result.JsonPathResultMatchers campo(String clave, String nombre) {
        return jsonPath("$.registros[?(@.clave == '" + clave + "')]." + nombre);
    }

    /** Una decision sobre el productor tomada hace {@code dias} dias (la tabla es de solo insercion). */
    private long decisionSobreProductor(long productor, long autor, int dias) {
        return jdbc.queryForObject("""
                INSERT INTO productor_decision (productor_id, decision, motivo, decidido_por, decidido_en)
                VALUES (?, 'HABILITAR_TIENDA', 'Prueba de auditoría', ?, now() - make_interval(days => ?))
                RETURNING id""", Long.class, productor, autor, dias);
    }

    private long usuario(String correo) {
        return jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, correo);
    }

    private static LocalDate hoy() {
        return LocalDate.now(ZoneId.of("America/Lima"));
    }
}
