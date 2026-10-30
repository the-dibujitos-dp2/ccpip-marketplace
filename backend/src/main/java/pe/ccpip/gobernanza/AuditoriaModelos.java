package pe.ccpip.gobernanza;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import pe.ccpip.auth.model.Rol;

/**
 * CCPIP-97 (RF-84): la auditoria de operaciones sensibles. Cada registro ya existe, con su autor y su
 * fecha, en una tabla de solo insercion (o de un unico cambio de estado) de su modulo; la auditoria solo
 * los lee y los presenta juntos, asi que no hay nada que editar ni borrar desde aqui.
 */
public final class AuditoriaModelos {

    private AuditoriaModelos() {
    }

    /** Sobre que se hizo la operacion. */
    public enum Entidad {
        AUTORIZACION, MODERACION, VIDEO, PRODUCTOR, DENUNCIAS, CUENTA, PRODUCTO, LINEA_MAESTRA, PRODUCTO_MAESTRO, PEDIDO
    }

    /**
     * Como termino. Las acciones de gobernanza, de productos y del catalogo solo se registran cuando se
     * completan ({@code EXITOSA}); un pago registra tambien los intentos rechazados o con error.
     */
    public enum Resultado { EXITOSA, APROBADO, RECHAZADO, ERROR, PENDIENTE, ANULADO }

    /**
     * Un registro. {@code clave} es unica (tabla de origen e id). {@code usuario} y {@code rol} son de
     * quien lo hizo; nulos si lo hizo el sistema. {@code accion} es un codigo que la pantalla traduce;
     * {@code detalle}, el nombre de lo afectado (producto, numero de pedido, organizacion).
     */
    public record Registro(String clave, Instant fecha, Long usuarioId, String usuario, String correo, Rol rol,
                           String accion, Entidad entidad, long entidadId, String detalle, Resultado resultado) {
    }

    /** Filtros, todos opcionales y combinables. {@code usuario} busca en el nombre y el correo. */
    public record Filtro(LocalDate desde, LocalDate hasta, String usuario, Rol rol, String accion, Entidad entidad,
                         Resultado resultado) {
    }

    /** Una pagina de la auditoria del periodo (dias de Lima, inclusive), la mas reciente primero. */
    public record PaginaAuditoria(LocalDate desde, LocalDate hasta, List<Registro> registros, int pagina, int tamano,
                                  long total, int totalPaginas, List<String> acciones) {
    }
}
