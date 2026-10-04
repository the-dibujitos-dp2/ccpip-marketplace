package pe.ccpip.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;
import pe.ccpip.auth.config.SessionCookies;
import pe.ccpip.auth.controller.AuthController;

/**
 * Base de las pruebas contra PostgreSQL real (Testcontainers, ver pom.xml) con las migraciones
 * y cuentas sembradas, y S3Mock como bucket de imagenes. Un solo contexto para toda la suite;
 * cada prueba limpia sesiones y bloqueos.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    /** Un solo S3Mock para toda la suite; Testcontainers lo apaga al terminar. */
    static final S3MockContainer S3 = new S3MockContainer("5.2.3");

    static {
        S3.start();
        // El SDK de AWS exige credenciales aunque S3Mock no las valide
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
    }

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        registry.add("ccpip.imagenes.endpoint", S3::getHttpEndpoint);
        registry.add("ccpip.imagenes.bucket", () -> "ccpip-test");
        registry.add("ccpip.imagenes.crear-bucket", () -> "true");
        registry.add("ccpip.ia.proveedor", () -> "simulado");
    }

    /** Clave comun de las cuentas de demostracion sembradas en R__02 (publica a proposito, CCPIP-23). */
    protected static final String DEMO_PASSWORD = "Demo1234!"; // NOSONAR java:S2068 credencial de demo intencional

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetAuthState() {
        jdbc.update("DELETE FROM sesion");
        jdbc.update("UPDATE usuario SET intentos_fallidos = 0, bloqueado_hasta = NULL");
    }

    protected RequestBuilder login(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new AuthController.LoginRequest(email, password));
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** JSON de un objeto para el cuerpo de una peticion. */
    protected String json(Object cuerpo) throws Exception {
        return objectMapper.writeValueAsString(cuerpo);
    }

    /** Inserta un producto publicado con su linea y, si se pide, un lote. Para casos borde del catalogo. */
    protected long productoConLote(String nombre, String estado, String tipoUbicacion, java.time.LocalDate vence) {
        Long id = jdbc.queryForObject("""
                INSERT INTO producto (productor_id, nombre, descripcion, linea_id, unidad_medida_id, precio, estado, publicado_en)
                SELECT (SELECT p.id FROM productor p JOIN usuario u ON u.id = p.usuario_id WHERE u.correo = 'productor@ccpip.test'),
                       ?, 'Producto de prueba', (SELECT id FROM linea_producto WHERE codigo = 'SUPERFRUTOS'),
                       (SELECT id FROM unidad_medida WHERE codigo = 'G'), 20.00, ?, now()
                RETURNING id""", Long.class, nombre, estado);
        jdbc.update("INSERT INTO producto_linea (producto_id, linea_id) SELECT ?, id FROM linea_producto WHERE codigo = 'SUPERFRUTOS'", id);
        if (tipoUbicacion != null) {
            jdbc.update("""
                    INSERT INTO lote (producto_id, ubicacion_id, codigo, cantidad_inicial, cantidad_actual,
                                      fecha_produccion, fecha_vencimiento)
                    SELECT ?, u.id, 'L-T' || ?, 5, 5, ?::date - 60, ?
                    FROM ubicacion u
                    WHERE u.tipo = ? AND (u.tipo = 'PUNTO_ACOPIO' AND u.nombre = 'Centro Nacional Lima'
                          OR u.productor_id = (SELECT productor_id FROM producto WHERE id = ?))""",
                    id, id, vence, vence, tipoUbicacion, id);
        }
        return id;
    }

    /** Inicia sesion y devuelve la cookie para usarla en las siguientes peticiones. */
    protected Cookie loginAs(String email) throws Exception {
        Cookie cookie = mockMvc.perform(login(email, DEMO_PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SessionCookies.NAME);
        return new Cookie(cookie.getName(), cookie.getValue());
    }
}
