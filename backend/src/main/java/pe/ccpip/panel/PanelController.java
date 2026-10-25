package pe.ccpip.panel;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;

/**
 * Pantalla de inicio de cada rol (CCPIP-27) y primer uso del control de acceso de CCPIP-151:
 * cada panel solo lo puede pedir su rol. Las historias de cada rol iran reemplazando el contenido.
 */
@RestController
@RequestMapping("/api/panel")
public class PanelController {

    public record Panel(String titulo, String descripcion) {
    }

    @RolesPermitidos(Rol.CONSUMIDOR)
    @GetMapping("/consumidor")
    public Panel consumidor() {
        return new Panel("Tu mercado", "Explora productos con historia, directo de las comunidades.");
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/productor")
    public Panel productor() {
        return new Panel("Tu comunidad", "Aqui gestionaras tus productos, tu stock y tus pedidos.");
    }

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @GetMapping("/acopio")
    public Panel acopio() {
        return new Panel("Tu punto de acopio", "Aqui veras los pedidos por preparar y registraras los recojos.");
    }

    @RolesPermitidos({Rol.SUB_ADMIN, Rol.SUPER_ADMIN})
    @GetMapping("/administracion")
    public Panel administracion() {
        return new Panel("Administracion CCPIP", "Aqui supervisaras productores, puntos de acopio y cuentas.");
    }

    @RolesPermitidos(Rol.DISTRIBUIDOR)
    @GetMapping("/distribuidor")
    public Panel distribuidor() {
        // CCPIP-107: el distribuidor es quien reparte los pedidos a domicilio, no un comprador mayorista
        return new Panel("Tus entregas", "Aqui veras los pedidos listos para repartir y los que asumas.");
    }
}
