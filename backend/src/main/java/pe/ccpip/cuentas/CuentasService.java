package pe.ccpip.cuentas;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.auth.model.AuthenticatedSession;
import pe.ccpip.auth.model.Rol;
import pe.ccpip.auth.service.RecuperacionService;
import pe.ccpip.auth.service.RegistroStore;
import pe.ccpip.auth.service.RegistroStore.Proposito;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.cuentas.CorreoDeCuenta.Aviso;
import pe.ccpip.cuentas.CuentasModelos.Accion;
import pe.ccpip.cuentas.CuentasModelos.Acciones;
import pe.ccpip.cuentas.CuentasModelos.Busqueda;
import pe.ccpip.cuentas.CuentasModelos.CambioCuenta;
import pe.ccpip.cuentas.CuentasModelos.CuentaDetalle;
import pe.ccpip.cuentas.CuentasModelos.EstadoCuenta;
import pe.ccpip.cuentas.CuentasModelos.NuevaCuenta;
import pe.ccpip.cuentas.CuentasModelos.Punto;
import pe.ccpip.cuentas.CuentasRepository.Cuenta;
import pe.ccpip.cuentas.CuentasRepository.Filtro;

/**
 * Gestion de cuentas de la administracion (SA-03, SA-04).
 * <ul>
 *   <li>CCPIP-95, 124, 125: buscar y consultar cuentas segun el alcance del rol ({@link AlcanceCuentas}).</li>
 *   <li>CCPIP-301, 304: el superadministrador crea cuentas de sub administrador y de administrador de punto.
 *   La cuenta nace sin contrasena utilizable: la persona la elige con un enlace de un solo uso que le llega
 *   por correo. Nadie mas la conoce, ni se responde ni se registra.</li>
 *   <li>CCPIP-302, 305: modifica sus datos autorizados (nombre, telefono y puntos asignados). El correo y el
 *   rol no cambian, para que nadie gane privilegios editando una cuenta.</li>
 *   <li>CCPIP-303, 306: la desactiva con motivo; es definitiva y conserva la cuenta y su historial.</li>
 *   <li>CCPIP-307, 308: un administrador autorizado suspende y reactiva cuentas con motivo.</li>
 * </ul>
 * Cada cambio queda en cuenta_historial con quien, cuando y por que. Una cuenta que deja de estar activa
 * pierde sus sesiones y sus enlaces sin usar, y nadie actua sobre su propia cuenta.
 */
@Service
public class CuentasService {

    static final int TAMANO_PAGINA = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Resultado de crear o reenviar el acceso: el aviso solo sale despues de confirmar la transaccion. */
    public record ConAviso(CuentaDetalle cuenta, Aviso aviso) {
    }

    private final CuentasRepository repo;
    private final RecuperacionService recuperacion;
    private final RegistroStore enlaces;
    private final PasswordEncoder passwordEncoder;
    private final Duration vigenciaAcceso;

    public CuentasService(CuentasRepository repo, RecuperacionService recuperacion, RegistroStore enlaces,
                          PasswordEncoder passwordEncoder,
                          @Value("${ccpip.cuentas.enlace-acceso-vigencia:72h}") Duration vigenciaAcceso) {
        this.repo = repo;
        this.recuperacion = recuperacion;
        this.enlaces = enlaces;
        this.passwordEncoder = passwordEncoder;
        this.vigenciaAcceso = vigenciaAcceso;
    }

    // --- Consulta (CCPIP-95, 124, 125) ---

    @Transactional(readOnly = true)
    public Busqueda buscar(AuthenticatedSession actor, String texto, Rol rol, EstadoCuenta estado, int pagina) {
        List<Rol> visibles = AlcanceCuentas.listaVisibles(actor.role());
        if (rol != null && !visibles.contains(rol)) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "ROL_FUERA_DE_ALCANCE",
                    "Tu rol no permite consultar cuentas de ese tipo.");
        }
        String limpio = texto == null || texto.isBlank() ? null : texto.strip();
        Filtro f = new Filtro(rol == null ? visibles : List.of(rol), limpio, estado, Math.max(pagina, 0), TAMANO_PAGINA);
        long total = repo.contar(f);
        return new Busqueda(repo.buscar(f), f.pagina(), f.tamano(), total,
                (int) ((total + f.tamano() - 1) / f.tamano()), visibles);
    }

    @Transactional(readOnly = true)
    public CuentaDetalle detalle(AuthenticatedSession actor, long id) {
        return detalle(actor, visible(actor, id));
    }

    @Transactional(readOnly = true)
    public List<Punto> puntosAsignables() {
        return repo.puntosAsignables();
    }

    // --- Alta y datos autorizados (CCPIP-301, 302, 304, 305) ---

    @Transactional
    public ConAviso crear(AuthenticatedSession actor, NuevaCuenta nueva) {
        if (!AlcanceCuentas.administra(actor.role(), nueva.rol())) {
            throw ErrorNegocio.invalido("ROL_NO_PERMITIDO",
                    "Solo se crean cuentas de sub administrador o de administrador de punto de acopio.");
        }
        Set<Long> puntos = puntosValidos(nueva.rol(), nueva.puntos());
        String correo = nueva.correo().strip();
        String nombre = nueva.nombre().strip();
        String telefono = textoOpcional(nueva.telefono());
        if (repo.correoEnUso(correo)) {
            throw correoEnUso();
        }
        long id;
        try {
            id = repo.crear(nombre, correo, telefono, nueva.rol(), contrasenaInutilizable());
        } catch (DuplicateKeyException e) {
            // Dos altas simultaneas con el mismo correo: la base rechaza la segunda (RNF-40)
            throw correoEnUso();
        }
        if (!puntos.isEmpty()) {
            repo.asignarPuntos(id, puntos);
        }
        Map<String, Object> cambios = new LinkedHashMap<>();
        cambios.put("rol", nueva.rol().name());
        cambios.put("nombre", nombre);
        if (!puntos.isEmpty()) {
            cambios.put("puntos", nombres(repo.puntosDe(id)));
        }
        repo.registrar(id, Accion.CREAR, null, cambios, actor.userId());
        return conEnlace(actor, repo.cuenta(id).orElseThrow(), Accion.CREAR);
    }

    /** Un nuevo enlace de bienvenida si la persona aun no eligio su contrasena (el anterior vencio o se perdio). */
    @Transactional
    public ConAviso reenviarAcceso(AuthenticatedSession actor, long id) {
        Cuenta c = administrada(actor, id);
        if (c.estado() != EstadoCuenta.ACTIVA) {
            throw estadoNoPermite();
        }
        if (c.correoConfirmado()) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "ACCESO_YA_CONFIGURADO",
                    "La persona ya eligió su contraseña. Si la olvidó, puede recuperarla desde Ingresar.");
        }
        repo.registrar(id, Accion.REENVIAR_ACCESO, null, null, actor.userId());
        return conEnlace(actor, c, Accion.REENVIAR_ACCESO);
    }

    @Transactional
    public CuentaDetalle modificar(AuthenticatedSession actor, long id, CambioCuenta cambio) {
        Cuenta c = administrada(actor, id);
        if (c.estado() == EstadoCuenta.DESACTIVADA) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CUENTA_DESACTIVADA",
                    "Una cuenta desactivada ya no se modifica; se conserva con su historial.");
        }
        Set<Long> puntos = puntosValidos(c.rol(), cambio.puntos());
        String nombre = cambio.nombre().strip();
        String telefono = textoOpcional(cambio.telefono());

        Map<String, Object> cambios = new LinkedHashMap<>();
        if (!nombre.equals(c.nombre())) {
            cambios.put("nombre", antesDespues(c.nombre(), nombre));
        }
        if (!Objects.equals(telefono, c.telefono())) {
            cambios.put("telefono", antesDespues(c.telefono(), telefono));
        }
        if (cambios.isEmpty() && (c.rol() != Rol.ADMIN_PUNTO || puntos.equals(ids(repo.puntosDe(id))))) {
            return detalle(actor, c);
        }
        repo.actualizarDatos(id, nombre, telefono);
        if (c.rol() == Rol.ADMIN_PUNTO) {
            List<String> antes = nombres(repo.puntosDe(id));
            repo.asignarPuntos(id, puntos);
            List<String> despues = nombres(repo.puntosDe(id));
            if (!antes.equals(despues)) {
                cambios.put("puntos", antesDespues(antes, despues));
            }
        }
        repo.registrar(id, Accion.MODIFICAR, null, cambios, actor.userId());
        return detalle(actor, repo.cuenta(id).orElseThrow());
    }

    // --- Estados (CCPIP-303, 306, 307, 308) ---

    @Transactional
    public ConAviso desactivar(AuthenticatedSession actor, long id, String motivo) {
        Cuenta c = administrada(actor, id);
        if (c.estado() == EstadoCuenta.DESACTIVADA) {
            throw estadoNoPermite();
        }
        return cambiarEstado(actor, c, c.estado(), EstadoCuenta.DESACTIVADA, Accion.DESACTIVAR, motivo);
    }

    @Transactional
    public ConAviso suspender(AuthenticatedSession actor, long id, String motivo) {
        Cuenta c = visible(actor, id);
        return cambiarEstado(actor, c, EstadoCuenta.ACTIVA, EstadoCuenta.SUSPENDIDA, Accion.SUSPENDER, motivo);
    }

    @Transactional
    public ConAviso reactivar(AuthenticatedSession actor, long id, String motivo) {
        Cuenta c = visible(actor, id);
        return cambiarEstado(actor, c, EstadoCuenta.SUSPENDIDA, EstadoCuenta.ACTIVA, Accion.REACTIVAR, motivo);
    }

    private ConAviso cambiarEstado(AuthenticatedSession actor, Cuenta c, EstadoCuenta desde, EstadoCuenta hacia,
                                   Accion accion, String motivo) {
        if (c.estado() != desde || !repo.cambiarEstado(c.id(), desde, hacia)) {
            throw estadoNoPermite();
        }
        if (hacia != EstadoCuenta.ACTIVA) {
            repo.cerrarSesiones(c.id());
            // Un enlace de bienvenida o de recuperacion pendiente tampoco debe abrir la cuenta
            enlaces.descartarEnlacesSinUsar(c.id(), Proposito.RECUPERAR_CLAVE);
        }
        String limpio = motivo.strip();
        repo.registrar(c.id(), accion, limpio, Map.of("estado", antesDespues(desde.name(), hacia.name())), actor.userId());
        Cuenta actual = repo.cuenta(c.id()).orElseThrow();
        return new ConAviso(detalle(actor, actual),
                new Aviso(actual.correo(), actual.nombre(), actual.idioma(), accion, limpio, null));
    }

    // --- Alcance ---

    /** La cuenta, si quien pide puede verla. Fuera de su alcance responde como si no existiera. */
    private Cuenta visible(AuthenticatedSession actor, long id) {
        if (actor.userId() == id) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "CUENTA_PROPIA",
                    "No puedes gestionar tu propia cuenta desde la administración.");
        }
        Optional<Cuenta> c = repo.cuenta(id);
        if (c.isEmpty() || !AlcanceCuentas.visibles(actor.role()).contains(c.get().rol())) {
            throw ErrorNegocio.noEncontrado("CUENTA_NO_ENCONTRADA", "No encontramos esa cuenta.");
        }
        return c.get();
    }

    /** Solo las cuentas que quien pide crea, modifica y desactiva (CCPIP-301 a 306). */
    private Cuenta administrada(AuthenticatedSession actor, long id) {
        Cuenta c = visible(actor, id);
        if (!AlcanceCuentas.administra(actor.role(), c.rol())) {
            throw new ErrorNegocio(HttpStatus.FORBIDDEN, "SIN_PERMISO", "Tu rol no permite modificar esta cuenta.");
        }
        return c;
    }

    private CuentaDetalle detalle(AuthenticatedSession actor, Cuenta c) {
        boolean administra = AlcanceCuentas.administra(actor.role(), c.rol());
        boolean activa = c.estado() == EstadoCuenta.ACTIVA;
        Acciones acciones = new Acciones(
                administra && c.estado() != EstadoCuenta.DESACTIVADA,
                activa,
                c.estado() == EstadoCuenta.SUSPENDIDA,
                administra && c.estado() != EstadoCuenta.DESACTIVADA,
                administra && activa && !c.correoConfirmado());
        // Minima exposicion (CCPIP-95): el telefono solo lo ve quien administra la cuenta
        return new CuentaDetalle(c.id(), c.nombre(), c.correo(), administra ? c.telefono() : null, c.rol(), c.rolNombre(), c.estado(),
                !c.correoConfirmado(), repo.puntosDe(c.id()), c.creadaEn(), acciones, repo.historial(c.id()));
    }

    // --- Apoyo ---

    private ConAviso conEnlace(AuthenticatedSession actor, Cuenta c, Accion accion) {
        String token = recuperacion.emitirEnlaceDeAcceso(c.id(), vigenciaAcceso);
        return new ConAviso(detalle(actor, c), new Aviso(c.correo(), c.nombre(), c.idioma(), accion, null, token));
    }

    /**
     * El administrador de punto opera al menos un punto de acopio activo; el sub administrador no tiene
     * puntos ("asignar solo ubicacion pertinente", CCPIP-304, 305).
     */
    private Set<Long> puntosValidos(Rol rol, List<Long> pedidos) {
        Set<Long> puntos = new LinkedHashSet<>(pedidos == null ? List.of() : pedidos);
        puntos.remove(null);
        if (rol != Rol.ADMIN_PUNTO) {
            if (!puntos.isEmpty()) {
                throw ErrorNegocio.invalido("PUNTOS_NO_APLICAN", "Solo el administrador de punto de acopio tiene puntos asignados.");
            }
            return puntos;
        }
        if (puntos.isEmpty()) {
            throw ErrorNegocio.invalido("PUNTOS_REQUERIDOS", "Asigna al menos un punto de acopio.");
        }
        if (!ids(repo.puntosAsignables()).containsAll(puntos)) {
            throw ErrorNegocio.invalido("PUNTO_INVALIDO", "Solo se asignan puntos de acopio activos de la Cámara.");
        }
        return puntos;
    }

    /** Hash de una clave aleatoria que nadie conoce: la cuenta no sirve hasta elegir la suya con el enlace. */
    private String contrasenaInutilizable() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return passwordEncoder.encode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    private static ErrorNegocio correoEnUso() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "CORREO_EN_USO", "Ya hay una cuenta con ese correo.");
    }

    private static ErrorNegocio estadoNoPermite() {
        return new ErrorNegocio(HttpStatus.CONFLICT, "ESTADO_NO_PERMITE",
                "El estado actual de la cuenta no permite esta acción. Actualiza la pantalla.");
    }

    private static String textoOpcional(String texto) {
        return texto == null || texto.isBlank() ? null : texto.strip();
    }

    private static Map<String, Object> antesDespues(Object antes, Object despues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("antes", antes);
        m.put("despues", despues);
        return m;
    }

    private static List<String> nombres(List<Punto> puntos) {
        return puntos.stream().map(Punto::nombre).toList();
    }

    private static Set<Long> ids(List<Punto> puntos) {
        Set<Long> ids = new LinkedHashSet<>();
        puntos.forEach(p -> ids.add(p.id()));
        return ids;
    }
}
