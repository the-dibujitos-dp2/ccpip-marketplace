package pe.ccpip.productor;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuenta bancaria del productor (Figma PW-19 y PW-20): CCPIP-32 registrar, CCPIP-274 actualizar,
 * CCPIP-275 desactivar temporalmente y CCPIP-276 reactivar. Sin vencimiento, CVV ni tarjeta.
 */
public final class CuentaBancariaModelos {

    private CuentaBancariaModelos() {
    }

    /**
     * Bancos y cajas donde la Camara puede liquidar. Lista cerrada para que el CCI y el nombre del
     * banco no lleguen con variantes escritas a mano.
     */
    public static final List<String> BANCOS = List.of(
            "Banco de Crédito del Perú (BCP)", "BBVA Perú", "Interbank", "Scotiabank Perú", "Banco de la Nación",
            "BanBif", "Banco Pichincha", "Mibanco", "Caja Arequipa", "Caja Huancayo", "Caja Piura", "Caja Cusco");

    /** Datos que el productor escribe. {@code cci} y {@code numeroCuenta} admiten espacios y guiones. */
    public record Datos(
            @NotBlank(message = "Indica el titular de la cuenta") @Size(max = 160, message = "Máximo 160 caracteres") String titular,
            @NotBlank(message = "Indica el DNI o RUC del titular")
            @Pattern(regexp = "\\s*(\\d{8}|\\d{11})\\s*", message = "Escribe un DNI de 8 dígitos o un RUC de 11") String documento,
            @NotBlank(message = "Elige el banco") String banco,
            @NotNull(message = "Elige el tipo de cuenta") TipoCuenta tipoCuenta,
            @NotBlank(message = "Indica el número de cuenta")
            @Pattern(regexp = "[0-9 -]{8,30}", message = "Usa solo números y guiones") String numeroCuenta,
            @NotBlank(message = "Indica el CCI")
            @Pattern(regexp = "[0-9 -]{20,30}", message = "El CCI tiene 20 dígitos") String cci,
            @NotNull(message = "Elige la moneda") Moneda moneda,
            @Email(message = "Escribe un correo válido") @Size(max = 254) String correoAvisos,
            @Size(max = 20) String yapeCelular) {
    }

    /** CCPIP-275: la desactivacion exige un motivo y la confirmacion expresa del productor. */
    public record Desactivacion(@NotNull(message = "Elige el motivo") Motivo motivo,
                                @AssertTrue(message = "Confirma que quieres desactivar la cuenta") boolean confirmo) {
    }

    /** CCPIP-276: reactivar valida otra vez los datos y pide la contrasena del productor. */
    public record Reactivacion(@NotNull(message = "Revisa los datos de la cuenta") @jakarta.validation.Valid Datos datos,
                               @NotBlank(message = "Escribe tu contraseña") String contrasena) {
    }

    public enum TipoCuenta { AHORROS, CORRIENTE }

    public enum Moneda { PEN, USD }

    public enum Motivo { ROBO, BLOQUEO_BANCO, SEGURIDAD, OTRO }

    public enum Estado { ACTIVA, DESACTIVADA }

    /** La cuenta como la ve su productor (CCPIP-399): completa, porque es suya. */
    public record Cuenta(long id, Estado estado, String titular, String documento, String banco, TipoCuenta tipoCuenta,
                         String numeroCuenta, String cci, Moneda moneda, String correoAvisos, String yapeCelular,
                         Motivo motivoDesactivacion, Instant desactivadaEn, Instant actualizadaEn) {
    }
}
