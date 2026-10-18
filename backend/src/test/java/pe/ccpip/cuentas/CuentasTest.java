package pe.ccpip.cuentas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;
import pe.ccpip.aviso.Correo;
import pe.ccpip.support.IntegrationTest;

/**
 * Cuentas de la administracion (SA-03, SA-04): CCPIP-95, 124, 125 y 301 a 308.
 * Transaccional: las cuentas creadas, suspendidas o desactivadas no llegan a las demas pruebas.
 */
@Transactional
class CuentasTest extends IntegrationTest {

    private static final String SUPER = "sudo@ccpip.test";
    private static final String SUB = "sub_admin@ccpip.test";
    private static final String ACOPIO = "admin_acopio@ccpip.test";
    private static final String CONSUMIDOR = "consumidor@ccpip.test";
    private static final String NUEVA_CLAVE = "Bienvenida2026"; // NOSONAR java:S2068 clave de prueba
    private static final Pattern ENLACE = Pattern.compile("restablecer-contrasena\\?token=([A-Za-z0-9_-]+)");
    private static final String BASE = "/api/admin/cuentas";

    // --- CCPIP-301 y CCPIP-304: alta con acceso seguro ---

    @Test
    void elSuperadministradorCreaUnSubadministradorQueEligeSuContrasenaConUnEnlace() throws Exception {
        Cookie sesion = loginAs(SUPER);
        JsonNode creada = leer(crear(sesion, nueva("Rosa Huaman", "rosa@cuentas.test", "SUB_ADMIN", List.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.avisoEnviado").value(true))
                .andExpect(jsonPath("$.cuenta.rol").value("SUB_ADMIN"))
                .andExpect(jsonPath("$.cuenta.estado").value("ACTIVA"))
                .andExpect(jsonPath("$.cuenta.accesoPendiente").value(true))
                .andExpect(jsonPath("$.cuenta.historial[0].accion").value("CREAR"))
                .andExpect(jsonPath("$.cuenta.historial[0].realizadoPor").value("Super Administrador de Prueba"))
                // Ni contrasena ni token en la respuesta
                .andExpect(jsonPath("$..token").doesNotExist())
                .andExpect(jsonPath("$..password").doesNotExist())
                .andExpect(jsonPath("$..contrasenaHash").doesNotExist()));
        long id = creada.at("/cuenta/id").asLong();

        // Sin elegir la contrasena no puede ingresar con ninguna
        mockMvc.perform(login("rosa@cuentas.test", DEMO_PASSWORD)).andExpect(status().isUnauthorized());

        Correo bienvenida = correos.ultimoPara("rosa@cuentas.test").orElseThrow();
        assertThat(bienvenida.asunto()).isEqualTo("Tu cuenta de Mercado CCPIP");
        assertThat(bienvenida.texto()).contains("Rosa Huaman").contains("72 horas");
        restablecer(token(bienvenida), NUEVA_CLAVE).andExpect(status().isOk());

        mockMvc.perform(login("rosa@cuentas.test", NUEVA_CLAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("SUB_ADMIN"));
        mockMvc.perform(get(BASE + "/" + id).cookie(sesion))
                .andExpect(jsonPath("$.accesoPendiente").value(false))
                .andExpect(jsonPath("$.acciones.reenviarAcceso").value(false));
    }

    @Test
    void elAdministradorDePuntoNaceConSusPuntosDeAcopioActivos() throws Exception {
        Cookie sesion = loginAs(SUPER);
        long cusco = punto("Centro Regional Cusco");
        long loreto = punto("Centro Regional Loreto");
        crear(sesion, nueva("Pedro Quispe", "pedro@cuentas.test", "ADMIN_PUNTO", List.of(cusco, loreto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cuenta.puntos[0].nombre").value("Centro Regional Cusco"))
                .andExpect(jsonPath("$.cuenta.puntos[1].nombre").value("Centro Regional Loreto"))
                .andExpect(jsonPath("$.cuenta.historial[0].cambios.puntos[0]").value("Centro Regional Cusco"));

        // Solo puntos de acopio activos de la Camara: ni almacenes de productor ni puntos inexistentes
        long almacen = jdbc.queryForObject("SELECT id FROM ubicacion WHERE tipo = 'ALMACEN_PRODUCTOR' LIMIT 1", Long.class);
        crear(sesion, nueva("Otro", "otro@cuentas.test", "ADMIN_PUNTO", List.of(almacen)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PUNTO_INVALIDO"));
        crear(sesion, nueva("Otro", "otro@cuentas.test", "ADMIN_PUNTO", List.of()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PUNTOS_REQUERIDOS"));
        crear(sesion, nueva("Otro", "otro@cuentas.test", "SUB_ADMIN", List.of(cusco)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PUNTOS_NO_APLICAN"));

        mockMvc.perform(get(BASE + "/puntos").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].nombre").value("Centro Nacional Lima"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPER_ADMIN", "CONSUMIDOR", "PRODUCTOR", "DISTRIBUIDOR"})
    void soloSeCreanCuentasAdministrativasSinEscalarPrivilegios(String rol) throws Exception {
        crear(loginAs(SUPER), nueva("Alguien", "alguien@cuentas.test", rol, List.of()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROL_NO_PERMITIDO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usuario WHERE correo = 'alguien@cuentas.test'", Integer.class))
                .isZero();
    }

    @Test
    void elCorreoEsUnicoYLosDatosSeValidan() throws Exception {
        Cookie sesion = loginAs(SUPER);
        crear(sesion, nueva("Duplicado", "SUB_ADMIN@ccpip.test", "SUB_ADMIN", List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CORREO_EN_USO"));
        crear(sesion, nueva("", "no-es-correo", "SUB_ADMIN", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nombre").exists())
                .andExpect(jsonPath("$.campos.correo").exists());
    }

    @Test
    void siElCorreoNoSaleLaCuentaSeCreaYSePuedeReenviarElEnlace() throws Exception {
        Cookie sesion = loginAs(SUPER);
        correos.fallar(true);
        long id = leer(crear(sesion, nueva("Sin Correo", "sincorreo@cuentas.test", "SUB_ADMIN", List.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.avisoEnviado").value(false))).at("/cuenta/id").asLong();
        correos.fallar(false);

        mockMvc.perform(post(BASE + "/" + id + "/reenviar-acceso").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avisoEnviado").value(true))
                .andExpect(jsonPath("$.cuenta.historial[0].accion").value("REENVIAR_ACCESO"));
        String token = token(correos.ultimoPara("sincorreo@cuentas.test").orElseThrow());
        restablecer(token, NUEVA_CLAVE).andExpect(status().isOk());

        mockMvc.perform(post(BASE + "/" + id + "/reenviar-acceso").cookie(sesion))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCESO_YA_CONFIGURADO"));
    }

    @Test
    void reenviarElAccesoAnulaElEnlaceAnterior() throws Exception {
        Cookie sesion = loginAs(SUPER);
        long id = leer(crear(sesion, nueva("Dos Enlaces", "dos@cuentas.test", "SUB_ADMIN", List.of())))
                .at("/cuenta/id").asLong();
        String primero = token(correos.ultimoPara("dos@cuentas.test").orElseThrow());
        mockMvc.perform(post(BASE + "/" + id + "/reenviar-acceso").cookie(sesion)).andExpect(status().isOk());
        String segundo = token(correos.ultimoPara("dos@cuentas.test").orElseThrow());
        assertThat(segundo).isNotEqualTo(primero);
        restablecer(primero, NUEVA_CLAVE).andExpect(status().isBadRequest());
        restablecer(segundo, NUEVA_CLAVE).andExpect(status().isOk());
    }

    // --- RNF-05: el rol y el alcance se validan en el servidor ---

    @ParameterizedTest
    @ValueSource(strings = {SUB, ACOPIO, CONSUMIDOR, "productor@ccpip.test", "distribuidor@ccpip.test"})
    void soloElSuperadministradorCreaModificaYDesactiva(String correo) throws Exception {
        Cookie sesion = loginAs(correo);
        long sub = usuario(SUB);
        crear(sesion, nueva("Intruso", "intruso@cuentas.test", "SUB_ADMIN", List.of()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        mockMvc.perform(put(BASE + "/" + sub).cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "Cambiado"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/" + sub + "/desactivar").cookie(sesion).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("motivo", "x"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/puntos").cookie(sesion)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {ACOPIO, CONSUMIDOR, "productor@ccpip.test", "distribuidor@ccpip.test"})
    void lasCuentasSoloLasConsultaLaAdministracion(String correo) throws Exception {
        Cookie sesion = loginAs(correo);
        mockMvc.perform(get(BASE).cookie(sesion)).andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/" + usuario(CONSUMIDOR) + "/suspender").cookie(sesion)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", "x"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinSesionNoHayAcceso() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    // --- CCPIP-124 y CCPIP-125: consultar cuentas administrativas ---

    @Test
    void elSuperadministradorConsultaLosSubadministradores() throws Exception {
        JsonNode r = leer(mockMvc.perform(get(BASE).param("rol", "SUB_ADMIN").cookie(loginAs(SUPER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.correo == 'sub_admin@ccpip.test')].estado").value("ACTIVA"))
                // Minima exposicion: ni telefono, ni hash, ni intentos fallidos
                .andExpect(jsonPath("$..telefono").doesNotExist())
                .andExpect(jsonPath("$..contrasenaHash").doesNotExist())
                .andExpect(jsonPath("$..intentosFallidos").doesNotExist()));
        assertThat(roles(r)).containsOnly("SUB_ADMIN");
        assertThat(r.at("/roles").toString()).contains("SUB_ADMIN", "ADMIN_PUNTO").doesNotContain("SUPER_ADMIN");
    }

    @Test
    void elSuperadministradorConsultaLosAdministradoresDePuntoConSusPuntos() throws Exception {
        Cookie sesion = loginAs(SUPER);
        JsonNode r = leer(mockMvc.perform(get(BASE).param("rol", "ADMIN_PUNTO").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.correo == 'admin_acopio@ccpip.test')].puntos[0]").value("Centro Nacional Lima")));
        assertThat(roles(r)).containsOnly("ADMIN_PUNTO");

        mockMvc.perform(get(BASE + "/" + usuario(ACOPIO)).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.puntos[0].nombre").value("Centro Nacional Lima"))
                .andExpect(jsonPath("$.acciones.editar").value(true))
                .andExpect(jsonPath("$.acciones.desactivar").value(true))
                .andExpect(jsonPath("$.acciones.suspender").value(true));
    }

    // --- CCPIP-95: buscar segun el alcance del rol ---

    @Test
    void elSuperadministradorBuscaPorNombreOCorreoSinVerOtrosSuperadministradores() throws Exception {
        Cookie sesion = loginAs(SUPER);
        mockMvc.perform(get(BASE).param("q", "SHIPIBO").cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].correo").value("productor@ccpip.test"))
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get(BASE).param("q", "sudo@").cookie(sesion))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get(BASE).param("rol", "SUPER_ADMIN").cookie(sesion))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROL_FUERA_DE_ALCANCE"));
        // % y _ se buscan literales, no como comodines
        mockMvc.perform(get(BASE).param("q", "%").cookie(sesion)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void elSubadministradorSoloBuscaCuentasDeLaOperacion() throws Exception {
        Cookie sesion = loginAs(SUB);
        JsonNode r = leer(mockMvc.perform(get(BASE).cookie(sesion)).andExpect(status().isOk()));
        assertThat(roles(r)).isNotEmpty().allMatch(rol -> List.of("CONSUMIDOR", "PRODUCTOR", "DISTRIBUIDOR").contains(rol));
        assertThat(r.at("/roles").toString()).isEqualTo("[\"CONSUMIDOR\",\"PRODUCTOR\",\"DISTRIBUIDOR\"]");

        mockMvc.perform(get(BASE).param("q", "administrador").cookie(sesion)).andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get(BASE).param("rol", "ADMIN_PUNTO").cookie(sesion))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROL_FUERA_DE_ALCANCE"));
        // Una cuenta fuera de su alcance responde como inexistente
        mockMvc.perform(get(BASE + "/" + usuario(ACOPIO)).cookie(sesion))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CUENTA_NO_ENCONTRADA"));
        mockMvc.perform(get(BASE + "/" + usuario(SUPER)).cookie(sesion)).andExpect(status().isNotFound());

        mockMvc.perform(get(BASE + "/" + usuario(CONSUMIDOR)).cookie(sesion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acciones.suspender").value(true))
                .andExpect(jsonPath("$.acciones.editar").value(false))
                .andExpect(jsonPath("$.acciones.desactivar").value(false))
                .andExpect(jsonPath("$.telefono").doesNotExist());
    }

    @Test
    void elBuscadorFiltraPorEstadoYPagina() throws Exception {
        Cookie sesion = loginAs(SUPER);
        suspender(sesion, usuario(CONSUMIDOR), "Revisión de pagos").andExpect(status().isOk());
        mockMvc.perform(get(BASE).param("estado", "SUSPENDIDA").cookie(sesion))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].correo").value(CONSUMIDOR));
        mockMvc.perform(get(BASE).param("pagina", "0").cookie(sesion))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamano").value(CuentasService.TAMANO_PAGINA))
                .andExpect(jsonPath("$.totalPaginas").isNumber());
    }

    // --- CCPIP-302 y CCPIP-305: modificar datos autorizados ---

    @Test
    void elSuperadministradorModificaLosDatosYLosPuntosQuedandoEnElHistorial() throws Exception {
        Cookie sesion = loginAs(SUPER);
        long id = usuario(ACOPIO);
        long cusco = punto("Centro Regional Cusco");
        Map<String, Object> cambio = new HashMap<>();
        cambio.put("nombre", "Administrador del Cusco");
        cambio.put("telefono", "+51 984 111 222");
        cambio.put("puntos", List.of(cusco));
        cambio.put("correo", "otro@cuentas.test");
        cambio.put("rol", "SUPER_ADMIN");
        mockMvc.perform(put(BASE + "/" + id).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cambio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Administrador del Cusco"))
                .andExpect(jsonPath("$.telefono").value("+51 984 111 222"))
                // El correo y el rol no se modifican
                .andExpect(jsonPath("$.correo").value(ACOPIO))
                .andExpect(jsonPath("$.rol").value("ADMIN_PUNTO"))
                .andExpect(jsonPath("$.puntos.length()").value(1))
                .andExpect(jsonPath("$.puntos[0].nombre").value("Centro Regional Cusco"))
                .andExpect(jsonPath("$.historial[0].accion").value("MODIFICAR"))
                .andExpect(jsonPath("$.historial[0].cambios.nombre.antes").value("Administrador de Acopio de Prueba"))
                .andExpect(jsonPath("$.historial[0].cambios.nombre.despues").value("Administrador del Cusco"))
                .andExpect(jsonPath("$.historial[0].cambios.puntos.antes[0]").value("Centro Nacional Lima"))
                .andExpect(jsonPath("$.historial[0].cambios.puntos.despues[0]").value("Centro Regional Cusco"));

        // El punto asignado es el que filtra su operacion (RNF-05)
        assertThat(jdbc.queryForList("""
                SELECT ub.nombre FROM usuario_ubicacion uu JOIN ubicacion ub ON ub.id = uu.ubicacion_id
                WHERE uu.usuario_id = ?""", String.class, id)).containsExactly("Centro Regional Cusco");

        // Sin cambios no hay evento nuevo
        int eventos = eventos(id);
        cambio.remove("correo");
        cambio.remove("rol");
        mockMvc.perform(put(BASE + "/" + id).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cambio)))
                .andExpect(status().isOk());
        assertThat(eventos(id)).isEqualTo(eventos);

        cambio.put("puntos", List.of());
        mockMvc.perform(put(BASE + "/" + id).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cambio)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PUNTOS_REQUERIDOS"));
    }

    @Test
    void lasCuentasDeLaOperacionNoSeModificanDesdeAqui() throws Exception {
        mockMvc.perform(put(BASE + "/" + usuario(CONSUMIDOR)).cookie(loginAs(SUPER)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "Cambiado"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    // --- CCPIP-303 y CCPIP-306: desactivar conservando el historial ---

    @Test
    void desactivarConservaLaCuentaYSuHistorialYCierraSusSesiones() throws Exception {
        Cookie admin = loginAs(SUPER);
        Cookie suya = loginAs(SUB);
        long id = usuario(SUB);

        desactivar(admin, id, "  ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.campos.motivo").exists());
        desactivar(admin, id, "Terminó su contrato con la Cámara")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avisoEnviado").value(true))
                .andExpect(jsonPath("$.cuenta.estado").value("DESACTIVADA"))
                .andExpect(jsonPath("$.cuenta.acciones.editar").value(false))
                .andExpect(jsonPath("$.cuenta.acciones.reactivar").value(false))
                .andExpect(jsonPath("$.cuenta.historial[0].accion").value("DESACTIVAR"))
                .andExpect(jsonPath("$.cuenta.historial[0].motivo").value("Terminó su contrato con la Cámara"))
                .andExpect(jsonPath("$.cuenta.historial[0].cambios.estado.antes").value("ACTIVA"));

        // Sus sesiones abiertas caen y ya no ingresa; la fila sigue en la base
        mockMvc.perform(get("/api/auth/me").cookie(suya)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sesion WHERE usuario_id = ?", Integer.class, id)).isZero();
        mockMvc.perform(login(SUB, DEMO_PASSWORD)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUENTA_INACTIVA"));
        assertThat(jdbc.queryForObject("SELECT activo FROM usuario WHERE id = ?", Boolean.class, id)).isFalse();

        Correo aviso = correos.ultimoPara(SUB).orElseThrow();
        assertThat(aviso.asunto()).contains("desactivada");
        assertThat(aviso.texto()).contains("Terminó su contrato con la Cámara");

        // Es definitiva: no se reactiva, no se modifica, no se desactiva de nuevo
        reactivar(admin, id, "Volvió").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ESTADO_NO_PERMITE"));
        desactivar(admin, id, "Otra vez").andExpect(status().isConflict());
        mockMvc.perform(put(BASE + "/" + id).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nombre", "Otro"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CUENTA_DESACTIVADA"));

        // Sigue apareciendo en la consulta, con su estado
        mockMvc.perform(get(BASE).param("rol", "SUB_ADMIN").param("estado", "DESACTIVADA").cookie(admin))
                .andExpect(jsonPath("$.items[0].correo").value(SUB));
    }

    @Test
    void elSuperadministradorNoDesactivaCuentasDeLaOperacion() throws Exception {
        desactivar(loginAs(SUPER), usuario(CONSUMIDOR), "No corresponde")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void elHistorialNoSeEditaNiSeBorra() throws Exception {
        long id = usuario(SUB);
        suspender(loginAs(SUPER), id, "Revisión").andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("UPDATE cuenta_historial SET motivo = 'otro' WHERE usuario_id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void elHistorialNoSeBorra() throws Exception {
        long id = usuario(SUB);
        suspender(loginAs(SUPER), id, "Revisión").andExpect(status().isOk());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM cuenta_historial WHERE usuario_id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    // --- CCPIP-307 y CCPIP-308: suspender y reactivar con motivo ---

    @Test
    void elSubadministradorSuspendeYReactivaUnaCuentaConMotivoYAviso() throws Exception {
        Cookie admin = loginAs(SUB);
        Cookie suya = loginAs(CONSUMIDOR);
        long id = usuario(CONSUMIDOR);

        suspender(admin, id, "Reportes de pagos no reconocidos")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuenta.estado").value("SUSPENDIDA"))
                .andExpect(jsonPath("$.cuenta.acciones.reactivar").value(true))
                .andExpect(jsonPath("$.cuenta.acciones.suspender").value(false))
                .andExpect(jsonPath("$.cuenta.historial[0].accion").value("SUSPENDER"))
                .andExpect(jsonPath("$.cuenta.historial[0].realizadoPor").value("Sub-Administrador de Prueba"));
        mockMvc.perform(get("/api/auth/me").cookie(suya)).andExpect(status().isUnauthorized());
        mockMvc.perform(login(CONSUMIDOR, DEMO_PASSWORD)).andExpect(status().isForbidden());
        assertThat(correos.ultimoPara(CONSUMIDOR).orElseThrow().texto()).contains("Reportes de pagos no reconocidos");

        // Una cuenta suspendida no recupera el acceso con un enlace de recuperacion
        mockMvc.perform(post("/api/auth/recuperar-contrasena").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", CONSUMIDOR)))).andExpect(status().isAccepted());
        assertThat(correos.ultimoPara(CONSUMIDOR).orElseThrow().asunto()).contains("suspendida");

        suspender(admin, id, "Otra vez").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ESTADO_NO_PERMITE"));
        reactivar(admin, id, "").andExpect(status().isBadRequest());
        reactivar(admin, id, "Se aclararon los pagos")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuenta.estado").value("ACTIVA"))
                .andExpect(jsonPath("$.cuenta.historial[0].accion").value("REACTIVAR"))
                .andExpect(jsonPath("$.cuenta.historial[0].motivo").value("Se aclararon los pagos"))
                .andExpect(jsonPath("$.cuenta.historial[1].motivo").value("Reportes de pagos no reconocidos"));
        assertThat(correos.ultimoPara(CONSUMIDOR).orElseThrow().asunto()).contains("reactivada");
        mockMvc.perform(login(CONSUMIDOR, DEMO_PASSWORD)).andExpect(status().isOk());
        reactivar(admin, id, "Ya activa").andExpect(status().isConflict());
    }

    @Test
    void elAvisoSaleEnElIdiomaDeLaCuenta() throws Exception {
        long id = usuario(CONSUMIDOR);
        jdbc.update("UPDATE usuario SET idioma = 'en' WHERE id = ?", id);
        suspender(loginAs(SUB), id, "Payment review").andExpect(status().isOk());
        Correo aviso = correos.ultimoPara(CONSUMIDOR).orElseThrow();
        assertThat(aviso.asunto()).isEqualTo("Your Mercado CCPIP account was suspended");
        assertThat(aviso.texto()).contains("Reason: Payment review");
    }

    @Test
    void nadieSeSuspendeASiMismoNiAOtroAdministradorDeSuNivel() throws Exception {
        Cookie sub = loginAs(SUB);
        suspender(sub, usuario(SUB), "Me voy").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUENTA_PROPIA"));
        suspender(loginAs(SUPER), usuario(SUPER), "Me voy").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CUENTA_PROPIA"));
        // El sub administrador no suspende cuentas administrativas
        suspender(sub, usuario(ACOPIO), "No").andExpect(status().isNotFound());
        suspender(sub, usuario(SUPER), "No").andExpect(status().isNotFound());
        suspender(sub, 999_999_999L, "No").andExpect(status().isNotFound());
    }

    @Test
    void elSuperadministradorSuspendeUnaCuentaAdministrativaYAlReactivarlaConservaSusPuntos() throws Exception {
        Cookie admin = loginAs(SUPER);
        long id = usuario(ACOPIO);
        suspender(admin, id, "Auditoría de inventario").andExpect(status().isOk());
        mockMvc.perform(login(ACOPIO, DEMO_PASSWORD)).andExpect(status().isForbidden());
        reactivar(admin, id, "Auditoría cerrada").andExpect(status().isOk())
                .andExpect(jsonPath("$.cuenta.puntos[0].nombre").value("Centro Nacional Lima"));
        mockMvc.perform(login(ACOPIO, DEMO_PASSWORD)).andExpect(status().isOk());
    }

    // --- Apoyo ---

    private ResultActions crear(Cookie sesion, Map<String, Object> cuerpo) throws Exception {
        return mockMvc.perform(post(BASE).cookie(sesion).contentType(MediaType.APPLICATION_JSON).content(json(cuerpo)));
    }

    private ResultActions suspender(Cookie sesion, long id, String motivo) throws Exception {
        return conMotivo(sesion, id, "suspender", motivo);
    }

    private ResultActions reactivar(Cookie sesion, long id, String motivo) throws Exception {
        return conMotivo(sesion, id, "reactivar", motivo);
    }

    private ResultActions desactivar(Cookie sesion, long id, String motivo) throws Exception {
        return conMotivo(sesion, id, "desactivar", motivo);
    }

    private ResultActions conMotivo(Cookie sesion, long id, String accion, String motivo) throws Exception {
        return mockMvc.perform(post(BASE + "/" + id + "/" + accion).cookie(sesion)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("motivo", motivo))));
    }

    private ResultActions restablecer(String token, String clave) throws Exception {
        return mockMvc.perform(post("/api/auth/restablecer-contrasena").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "password", clave))));
    }

    private static Map<String, Object> nueva(String nombre, String correo, String rol, List<Long> puntos) {
        return Map.of("nombre", nombre, "correo", correo, "rol", rol, "puntos", puntos);
    }

    private static String token(Correo correo) {
        Matcher m = ENLACE.matcher(correo.texto());
        assertThat(m.find()).as("enlace en el correo:%n%s", correo.texto()).isTrue();
        return m.group(1);
    }

    private JsonNode leer(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static List<String> roles(JsonNode busqueda) {
        return StreamSupport.stream(busqueda.at("/items").spliterator(), false).map(i -> i.get("rol").asText()).toList();
    }

    private long usuario(String correo) {
        return jdbc.queryForObject("SELECT id FROM usuario WHERE correo = ?", Long.class, correo);
    }

    private long punto(String nombre) {
        return jdbc.queryForObject("SELECT id FROM ubicacion WHERE nombre = ? AND tipo = 'PUNTO_ACOPIO'", Long.class, nombre);
    }

    private int eventos(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM cuenta_historial WHERE usuario_id = ?", Integer.class, id);
    }
}
