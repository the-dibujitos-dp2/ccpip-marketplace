package pe.ccpip.gobernanza;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.gobernanza.GobernanzaModelos.Historial;
import pe.ccpip.gobernanza.GobernanzaModelos.Tipo;

/**
 * CCPIP-126 (RF-85): el subadministrador consulta sus propias acciones de gobernanza (Figma S-05). El
 * autor sale de la sesion, nunca de la peticion: no hay forma de pedir el historial de otro (RNF-05).
 * Es solo lectura.
 */
@RolesPermitidos(Rol.SUB_ADMIN)
@RestController
public class MiHistorialController {

    private final HistorialGobernanzaService service;

    public MiHistorialController(HistorialGobernanzaService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/mi-historial")
    public Historial miHistorial(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                                 @RequestParam(required = false) Tipo tipo) {
        return service.acciones(s.userId(), desde, hasta, tipo);
    }
}
