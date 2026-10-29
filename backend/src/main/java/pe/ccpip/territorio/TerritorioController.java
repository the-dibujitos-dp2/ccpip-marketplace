package pe.ccpip.territorio;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.entrega.EntregaModelos.Distrito;

/**
 * CCPIP-61: departamentos, provincias y distritos para elegir el destino de una entrega a domicilio. Cada
 * nivel pide la eleccion del anterior; sin ella no hay nada que listar. El administrador del punto de acopio
 * lo usa para declarar su cobertura (CCPIP-149).
 */
@RolesPermitidos({Rol.CONSUMIDOR, Rol.ADMIN_PUNTO})
@RestController
@RequestMapping("/api/territorio")
public class TerritorioController {

    private final TerritorioService territorio;

    public TerritorioController(TerritorioService territorio) {
        this.territorio = territorio;
    }

    @GetMapping("/departamentos")
    public List<String> departamentos() {
        return territorio.departamentos();
    }

    @GetMapping("/provincias")
    public List<String> provincias(@RequestParam(required = false) String departamento) {
        return territorio.provincias(exigir(departamento, "Elige el departamento."));
    }

    @GetMapping("/distritos")
    public List<Distrito> distritos(@RequestParam(required = false) String departamento,
                                    @RequestParam(required = false) String provincia) {
        return territorio.distritos(exigir(departamento, "Elige el departamento."), exigir(provincia, "Elige la provincia."));
    }

    private static String exigir(String valor, String mensaje) {
        if (valor == null || valor.isBlank()) {
            throw ErrorNegocio.invalido("TERRITORIO_INCOMPLETO", mensaje);
        }
        return valor.strip();
    }
}
