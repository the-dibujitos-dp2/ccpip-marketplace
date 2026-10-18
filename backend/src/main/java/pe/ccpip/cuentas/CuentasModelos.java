package pe.ccpip.cuentas;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import pe.ccpip.auth.model.Rol;

/** Lo que viaja entre la pantalla y el servidor en la gestion de cuentas (SA-03, SA-04). */
public final class CuentasModelos {

    private CuentasModelos() {
    }

    /** Estado de la cuenta (usuario.estado). DESACTIVADA es definitiva; SUSPENDIDA se puede reactivar. */
    public enum EstadoCuenta { ACTIVA, SUSPENDIDA, DESACTIVADA }

    /** Valores de cuenta_historial.accion. */
    public enum Accion { CREAR, MODIFICAR, SUSPENDER, REACTIVAR, DESACTIVAR, REENVIAR_ACCESO }

    /** Punto de acopio asignable a un administrador de punto (CCPIP-304, 305). */
    public record Punto(long id, String nombre, String departamento) {
    }

    /**
     * Una fila del buscador (CCPIP-95, 124, 125). Minima exposicion: sin telefono, hash ni intentos.
     * {@code accesoPendiente}: la persona aun no eligio su contrasena con el enlace de bienvenida.
     */
    public record CuentaResumen(long id, String nombre, String correo, Rol rol, String rolNombre, EstadoCuenta estado,
                                boolean accesoPendiente, List<String> puntos, Instant creadaEn) {
    }

    /** {@code roles}: los roles que quien consulta puede ver, para el filtro de la pantalla. */
    public record Busqueda(List<CuentaResumen> items, int pagina, int tamano, long total, int totalPaginas,
                           List<Rol> roles) {
    }

    /** Lo que quien consulta puede hacer con la cuenta; el servidor lo vuelve a validar en cada accion. */
    public record Acciones(boolean editar, boolean suspender, boolean reactivar, boolean desactivar,
                           boolean reenviarAcceso) {
    }

    /** Un evento del historial. {@code cambios}: antes y despues de lo que se modifico. */
    public record Evento(long id, Accion accion, String motivo, Map<String, Object> cambios, String realizadoPor,
                         Instant realizadoEn) {
    }

    public record CuentaDetalle(long id, String nombre, String correo, String telefono, Rol rol, String rolNombre,
                                EstadoCuenta estado, boolean accesoPendiente, List<Punto> puntos, Instant creadaEn,
                                Acciones acciones, List<Evento> historial) {
    }

    /** Resultado de una accion que avisa por correo. {@code avisoEnviado} false: el correo no salio. */
    public record Resultado(CuentaDetalle cuenta, boolean avisoEnviado) {
    }

    /** CCPIP-301, 304: datos de la cuenta nueva. {@code puntos} solo para ADMIN_PUNTO. */
    public record NuevaCuenta(
            @NotBlank(message = "Escribe el nombre") @Size(max = 160, message = "Usa hasta 160 caracteres") String nombre,
            @NotBlank(message = "Escribe el correo") @Email(message = "Escribe un correo válido")
            @Size(max = 255, message = "Usa hasta 255 caracteres") String correo,
            @Pattern(regexp = "^\\+?[0-9 ]{6,19}$", message = "Escribe un teléfono válido") String telefono,
            @NotNull(message = "Elige el rol") Rol rol,
            List<Long> puntos) {
    }

    /** CCPIP-302, 305: datos autorizados que se pueden modificar. El correo y el rol no cambian. */
    public record CambioCuenta(
            @NotBlank(message = "Escribe el nombre") @Size(max = 160, message = "Usa hasta 160 caracteres") String nombre,
            @Pattern(regexp = "^\\+?[0-9 ]{6,19}$", message = "Escribe un teléfono válido") String telefono,
            List<Long> puntos) {
    }

    /** CCPIP-303, 306, 307, 308: toda suspension, reactivacion o desactivacion lleva motivo. */
    public record Motivo(
            @NotBlank(message = "Escribe el motivo") @Size(max = 300, message = "Usa hasta 300 caracteres") String motivo) {
    }
}
