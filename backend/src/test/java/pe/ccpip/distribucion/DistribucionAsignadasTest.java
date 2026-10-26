package pe.ccpip.distribucion;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.Cookie;
import pe.ccpip.support.IntegrationTest;

/**
 * CCPIP-317 (RF-199): el distribuidor consulta sus entregas asignadas. Solo las suyas, con origen, destino y estado; las
 * de otro distribuidor y las que nadie asumio no aparecen.
 */
@Transactional
class DistribucionAsignadasTest extends IntegrationTest {

    private static final String LIMA = "Centro Nacional Lima";

    private Cookie distribuidor;
    private int secuencia;

    @BeforeEach
    void sesion() throws Exception {
        distribuidor = loginAs("distribuidor@ccpip.test");
    }

    /** Un pedido a domicilio de Lima listo para despacho, con su entrega disponible y un destino reconocible. */
    private long aDomicilio(String numero) {
        long producto = productoConLote("Producto " + numero + secuencia++, "PUBLICADO", "PUNTO_ACOPIO",
                LocalDate.now(ZoneId.of("America/Lima")).plusMonths(6));
        long id = pedidoConfirmado(numero, LIMA, producto, 1);
        jdbc.update("UPDATE pedido SET modalidad = 'ENVIO', estado = 'LISTO_PARA_DESPACHO' WHERE id = ?", id);
        jdbc.update("INSERT INTO pedido_estado_historial (pedido_id, estado) VALUES (?, 'LISTO_PARA_DESPACHO')", id);
        jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, contacto_nombre, contacto_celular)
                VALUES (?, '150122', 'Calle Secreta 999', 'Persona Reservada', '999888777')""", id);
        return id;
    }

    /** Un pedido a domicilio ya asumido por el distribuidor del seed, hace {@code minutos}. */
    private long asumido(String numero, int minutos) {
        long id = aDomicilio(numero);
        jdbc.update("""
                UPDATE entrega SET estado = 'ASIGNADA', asignada_en = now() - make_interval(mins => ?),
                       distribuidor_id = (SELECT id FROM usuario WHERE correo = 'distribuidor@ccpip.test')
                WHERE pedido_id = ?""", minutos, id);
        return id;
    }

    /** Un segundo distribuidor, con la misma clave de demostracion que el del seed. */
    private Cookie otroDistribuidor() throws Exception {
        jdbc.update("""
                INSERT INTO usuario (correo, contrasena_hash, nombre, rol_id, correo_confirmado)
                SELECT 'distribuidor2@ccpip.test', contrasena_hash, 'Otro Distribuidor', rol_id, true
                FROM usuario WHERE correo = 'distribuidor@ccpip.test'""");
        return loginAs("distribuidor2@ccpip.test");
    }

    private ResultActions asignadas(Cookie sesion) throws Exception {
        var peticion = get("/api/distribuidor/pedidos");
        return mockMvc.perform(sesion == null ? peticion : peticion.cookie(sesion));
    }

    @Test
    void sinEntregasAsumidasLaListaEstaVacia() throws Exception {
        aDomicilio("ASG-0001");
        asignadas(distribuidor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void laEntregaQueAsumeApareceConSuOrigenDestinoYEstado() throws Exception {
        aDomicilio("ASG-0002");
        mockMvc.perform(post("/api/distribuidor/pedidos/ASG-0002/asumir").cookie(distribuidor)).andExpect(status().isOk());

        asignadas(distribuidor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].numero").value("ASG-0002"))
                .andExpect(jsonPath("$[0].origen.nombre").value(LIMA))
                .andExpect(jsonPath("$[0].origen.direccion").isNotEmpty())
                .andExpect(jsonPath("$[0].destino.ubigeo").value("150122"))
                .andExpect(jsonPath("$[0].destino.distrito").value("Miraflores"))
                .andExpect(jsonPath("$[0].estado").value("ASIGNADA"))
                .andExpect(jsonPath("$[0].asignadaEn").isNotEmpty())
                .andExpect(jsonPath("$[0].retiradaEn").isEmpty())
                // La direccion exacta y el contacto llegan con CCPIP-110; el comprador nunca (RNF-05)
                .andExpect(content().string(not(containsString("Calle Secreta"))))
                .andExpect(content().string(not(containsString("999888777"))))
                .andExpect(content().string(not(containsString("Consumidor de Prueba"))));
    }

    @Test
    void noMuestraLasDeOtroDistribuidorNiLasQueNadieAsumio() throws Exception {
        asumido("ASG-0003", 10);
        aDomicilio("ASG-0004");
        Cookie otro = otroDistribuidor();
        aDomicilio("ASG-0005");
        mockMvc.perform(post("/api/distribuidor/pedidos/ASG-0005/asumir").cookie(otro)).andExpect(status().isOk());

        asignadas(distribuidor)
                .andExpect(jsonPath("$[*].numero", contains("ASG-0003")));
        asignadas(otro)
                .andExpect(jsonPath("$[*].numero", contains("ASG-0005")));
    }

    @Test
    void lasEnCursoVanPrimeroLaQueAsumioAntesPrimero() throws Exception {
        asumido("ASG-0006", 5);
        long retirada = asumido("ASG-0007", 30);
        jdbc.update("UPDATE entrega SET estado = 'RETIRADA', retirada_en = now() WHERE pedido_id = ?", retirada);
        asumido("ASG-0008", 15);

        asignadas(distribuidor)
                .andExpect(jsonPath("$[*].numero", contains("ASG-0007", "ASG-0008", "ASG-0006")))
                .andExpect(jsonPath("$[0].estado").value("RETIRADA"))
                .andExpect(jsonPath("$[0].retiradaEn").isNotEmpty());
    }

    @Test
    void lasEntregadasSoloSeMuestranElDiaQueSeEntregaronYDespuesDeLasEnCurso() throws Exception {
        asumido("ASG-0009", 60);
        long hoy = asumido("ASG-0010", 120);
        long ayer = asumido("ASG-0011", 3000);
        jdbc.update("""
                UPDATE entrega SET estado = 'ENTREGADA', retirada_en = now(), entregada_en = now()
                WHERE pedido_id = ?""", hoy);
        jdbc.update("""
                UPDATE entrega SET estado = 'ENTREGADA', retirada_en = now() - interval '2 days',
                       entregada_en = now() - interval '2 days'
                WHERE pedido_id = ?""", ayer);

        asignadas(distribuidor)
                .andExpect(jsonPath("$[*].numero", contains("ASG-0009", "ASG-0010")))
                .andExpect(jsonPath("$[1].estado").value("ENTREGADA"));
    }

    @Test
    void soloElDistribuidorConsultaSusEntregas() throws Exception {
        asignadas(null).andExpect(status().isUnauthorized());
        for (String correo : new String[] {"consumidor@ccpip.test", "productor@ccpip.test", "admin_acopio@ccpip.test",
                "sub_admin@ccpip.test", "sudo@ccpip.test"}) {
            asignadas(loginAs(correo)).andExpect(status().isForbidden());
        }
    }
}
