package pe.ccpip.administracion;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.administracion.TableroModelos.Comision;
import pe.ccpip.administracion.TableroModelos.Tablero;
import pe.ccpip.administracion.TableroModelos.Ventas;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comision.ComisionService;

/**
 * CCPIP-111 (RF-76): el superadministrador supervisa la operacion con informacion agregada (SA-01).
 * Es de solo lectura: no edita tiendas, productos, pedidos, datos de consumidores ni existencias,
 * y esos endpoints le responden 403 (RNF-04, ver SuperAdministradorTest).
 * Calificaciones, merma y reclamos se suman cuando existan sus historias (Sprint 2).
 */
@RestController
@RequestMapping("/api/admin")
public class TableroController {

    static final int PRODUCTORES_TOP = 5;

    private final TableroRepository repositorio;
    private final ComisionService comision;

    public TableroController(TableroRepository repositorio, ComisionService comision) {
        this.repositorio = repositorio;
        this.comision = comision;
    }

    @RolesPermitidos(Rol.SUPER_ADMIN)
    @GetMapping("/tablero")
    public Tablero tablero() {
        Ventas ventas = repositorio.ventas();
        // Estimada con el porcentaje vigente (CCPIP-90); sin comision configurada no se estima
        BigDecimal porcentaje = comision.porcentajeVigente();
        BigDecimal estimada = porcentaje == null ? null
                : ventas.mes().multiply(porcentaje).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Tablero(repositorio.periodo(), ventas, new Comision(porcentaje, estimada), repositorio.productores(),
                repositorio.ventasPorLinea(), repositorio.productoresTop(PRODUCTORES_TOP), repositorio.puntos());
    }
}
