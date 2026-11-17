package pe.ccpip.fidelizacion;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import jakarta.validation.constraints.NotNull;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.fidelizacion.BeneficiosModelos.Codigo;
import pe.ccpip.fidelizacion.BeneficiosModelos.Cupon;
import pe.ccpip.fidelizacion.BeneficiosModelos.Nivel;
import pe.ccpip.fidelizacion.BeneficiosModelos.NuevoCodigo;
import pe.ccpip.fidelizacion.BeneficiosModelos.NuevoCupon;
import pe.ccpip.fidelizacion.BeneficiosModelos.NuevoNivel;
import pe.ccpip.fidelizacion.BeneficiosModelos.PanelBeneficios;
import pe.ccpip.fidelizacion.FidelizacionModelos.Campana;
import pe.ccpip.fidelizacion.FidelizacionModelos.Metricas;
import pe.ccpip.fidelizacion.FidelizacionModelos.NuevaCampana;
import pe.ccpip.fidelizacion.FidelizacionModelos.Panel;

/**
 * La seccion Fidelizacion del sub administrador: campanas de puntos y sus metricas (CCPIP-86), sus codigos de
 * descuento, los niveles por recurrencia (CCPIP-88) y el catalogo de cupones que se canjean con puntos (CCPIP-89).
 */
@RestController
@RequestMapping("/api/admin/fidelizacion")
@RolesPermitidos(Rol.SUB_ADMIN)
public class FidelizacionController {

    private final CampanasService campanas;
    private final MetricasCampanaService metricas;
    private final CodigosService codigos;
    private final NivelesService niveles;
    private final CuponesService cupones;

    public FidelizacionController(CampanasService campanas, MetricasCampanaService metricas, CodigosService codigos,
                                  NivelesService niveles, CuponesService cupones) {
        this.campanas = campanas;
        this.metricas = metricas;
        this.codigos = codigos;
        this.niveles = niveles;
        this.cupones = cupones;
    }

    /** Un cupon se muestra u oculta del catalogo. */
    public record Activacion(@NotNull(message = "Indica si queda activo") Boolean activo) {
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

    // --- Codigos de descuento de una campana ---

    @GetMapping("/campanas/{id}/codigos")
    public List<Codigo> codigos(@PathVariable long id) {
        return codigos.codigos(id);
    }

    @PostMapping("/campanas/{id}/codigos")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Codigo> crearCodigo(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                    @PathVariable long id, @Valid @RequestBody NuevoCodigo nuevo) {
        return codigos.crear(s.userId(), id, nuevo);
    }

    @PostMapping("/campanas/{id}/codigos/{codigoId}/desactivar")
    public List<Codigo> desactivarCodigo(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                         @PathVariable long id, @PathVariable long codigoId) {
        return codigos.desactivar(s.userId(), id, codigoId);
    }

    // --- Niveles (CCPIP-88) y cupones (CCPIP-89) ---

    @GetMapping("/beneficios")
    public PanelBeneficios beneficios() {
        return new PanelBeneficios(niveles.niveles(), cupones.catalogo());
    }

    @PostMapping("/niveles")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Nivel> crearNivel(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @Valid @RequestBody NuevoNivel nuevo) {
        return niveles.crear(s.userId(), nuevo);
    }

    @PutMapping("/niveles/{id}")
    public List<Nivel> editarNivel(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                   @PathVariable long id, @Valid @RequestBody NuevoNivel cambios) {
        return niveles.editar(s.userId(), id, cambios);
    }

    @DeleteMapping("/niveles/{id}")
    public List<Nivel> retirarNivel(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                    @PathVariable long id) {
        return niveles.retirar(s.userId(), id);
    }

    @PostMapping("/cupones")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Cupon> crearCupon(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                  @Valid @RequestBody NuevoCupon nuevo) {
        return cupones.crear(s.userId(), nuevo);
    }

    @PutMapping("/cupones/{id}")
    public List<Cupon> editarCupon(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                   @PathVariable long id, @Valid @RequestBody NuevoCupon cambios) {
        return cupones.editar(s.userId(), id, cambios);
    }

    @PutMapping("/cupones/{id}/activo")
    public List<Cupon> activarCupon(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                    @PathVariable long id, @Valid @RequestBody Activacion activacion) {
        return cupones.activar(s.userId(), id, activacion.activo());
    }
}
