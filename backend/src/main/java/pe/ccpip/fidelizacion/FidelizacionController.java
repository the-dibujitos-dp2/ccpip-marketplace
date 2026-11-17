package pe.ccpip.fidelizacion;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.fidelizacion.FidelizacionModelos.Campana;
import pe.ccpip.fidelizacion.FidelizacionModelos.Metricas;
import pe.ccpip.fidelizacion.FidelizacionModelos.NuevaCampana;
import pe.ccpip.fidelizacion.FidelizacionModelos.Panel;

/** CCPIP-86: la seccion Fidelizacion del sub administrador (campanas de puntos y sus metricas). */
@RestController
@RequestMapping("/api/admin/fidelizacion")
@RolesPermitidos(Rol.SUB_ADMIN)
public class FidelizacionController {

    private final CampanasService campanas;
    private final MetricasCampanaService metricas;

    public FidelizacionController(CampanasService campanas, MetricasCampanaService metricas) {
        this.campanas = campanas;
        this.metricas = metricas;
    }

    @GetMapping
    public Panel panel() {
        return campanas.panel();
    }

    @PostMapping("/campanas")
    @ResponseStatus(HttpStatus.CREATED)
    public Campana crear(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                         @Valid @RequestBody NuevaCampana nueva) {
        return campanas.crear(s.userId(), nueva);
    }

    @PutMapping("/campanas/{id}")
    public Campana editar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                          @PathVariable long id, @Valid @RequestBody NuevaCampana cambios) {
        return campanas.editar(s.userId(), id, cambios);
    }

    @PostMapping("/campanas/{id}/finalizar")
    public Campana finalizar(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @PathVariable long id) {
        return campanas.finalizar(s.userId(), id);
    }

    @GetMapping("/campanas/{id}/metricas")
    public Metricas metricas(@PathVariable long id) {
        return metricas.metricas(id);
    }
}
