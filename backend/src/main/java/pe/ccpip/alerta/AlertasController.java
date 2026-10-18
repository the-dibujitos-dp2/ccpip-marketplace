package pe.ccpip.alerta;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import pe.ccpip.alerta.AlertaModelos.Bandeja;
import pe.ccpip.alerta.AlertaModelos.NuevasPreferencias;
import pe.ccpip.alerta.AlertaModelos.Preferencias;
import pe.ccpip.alerta.AlertaModelos.PreferenciasPunto;
import pe.ccpip.alerta.AlertaModelos.Tipo;
import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.filter.SessionAuthFilter;
import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.productor.ProductorActual;

/**
 * Alertas del productor (PW-21) y del punto de acopio (A-08). Cada uno ve y configura solo lo suyo:
 * el productor, desde su sesion; el administrador, los puntos que tiene asignados (RNF-05).
 */
@RestController
public class AlertasController {

    private final AlertasService alertas;
    private final PreferenciasAlerta preferencias;
    private final ProductorActual productorActual;

    public AlertasController(AlertasService alertas, PreferenciasAlerta preferencias, ProductorActual productorActual) {
        this.alertas = alertas;
        this.preferencias = preferencias;
        this.productorActual = productorActual;
    }

    // ---------- Productor, PW-21 ----------

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/alertas")
    public Bandeja productor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                             @RequestParam(required = false) Tipo tipo) {
        return alertas.deProductor(productorActual.id(s), tipo);
    }

    /** Marca una alerta como leida. */
    @RolesPermitidos(Rol.PRODUCTOR)
    @PostMapping("/api/productor/alertas/{id}/leida")
    public ResponseEntity<Void> leidaProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                               @PathVariable long id) {
        alertas.marcarLeidaProductor(productorActual.id(s), id);
        return ResponseEntity.noContent().build();
    }

    /** Marca todas como leidas. */
    @RolesPermitidos(Rol.PRODUCTOR)
    @PostMapping("/api/productor/alertas/leidas")
    public ResponseEntity<Void> todasLeidasProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        alertas.marcarLeidaProductor(productorActual.id(s), null);
        return ResponseEntity.noContent().build();
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @GetMapping("/api/productor/alertas/preferencias")
    public Preferencias preferenciasProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return preferencias.deProductor(productorActual.id(s));
    }

    @RolesPermitidos(Rol.PRODUCTOR)
    @PutMapping("/api/productor/alertas/preferencias")
    public Preferencias guardarProductor(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                         @Valid @RequestBody NuevasPreferencias nuevas) {
        return preferencias.guardarProductor(productorActual.id(s), s.userId(), nuevas);
    }

    // ---------- Punto de acopio, A-08 ----------

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @GetMapping("/api/acopio/alertas")
    public Bandeja punto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                         @RequestParam(required = false) Tipo tipo) {
        return alertas.dePuntos(s.userId(), tipo);
    }

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @PostMapping("/api/acopio/alertas/{id}/leida")
    public ResponseEntity<Void> leidaPunto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                           @PathVariable long id) {
        alertas.marcarLeidaPuntos(s.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @PostMapping("/api/acopio/alertas/leidas")
    public ResponseEntity<Void> todasLeidasPunto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        alertas.marcarLeidaPuntos(s.userId(), null);
        return ResponseEntity.noContent().build();
    }

    /** La configuracion de cada punto que administra. */
    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @GetMapping("/api/acopio/alertas/preferencias")
    public List<PreferenciasPunto> preferenciasPuntos(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s) {
        return preferencias.dePuntos(s.userId());
    }

    @RolesPermitidos(Rol.ADMIN_PUNTO)
    @PutMapping("/api/acopio/alertas/preferencias/{ubicacionId}")
    public PreferenciasPunto guardarPunto(@RequestAttribute(SessionAuthFilter.SESSION_ATTRIBUTE) AuthenticatedSession s,
                                          @PathVariable long ubicacionId, @Valid @RequestBody NuevasPreferencias nuevas) {
        return preferencias.guardarPunto(s.userId(), ubicacionId, nuevas);
    }
}
