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
        AUTORIZACION, MODERACION, VIDEO, PRODUCTOR, DENUNCIAS, CUENTA, PRODUCTO, LINEA_MAESTRA, PRODUCTO_MAESTRO, PEDIDO,
        /** CCPIP-296: un permiso configurable de un rol. */
        PERMISO,
        /** CCPIP-482: la tarifa de envio a domicilio de un distrito. */
        TARIFA_ENVIO,
        /** CCPIP-484: la capacidad diaria de entregas a domicilio de un punto de acopio. */
        CAPACIDAD_ENTREGA,
        /** CCPIP-496, 498: una zona de cobertura (distrito de entrega) de un punto de acopio. */
        COBERTURA_ENTREGA,
        /** CCPIP-509: configuracion historica de comisiones. */
        COMISION,
        /** CCPIP-509: parametros configurables de la plataforma. */
        PARAMETRO,
        /** CCPIP-509: catalogo de causas de merma. */
        MOTIVO_MERMA,
        /** CCPIP-509: una unidad del catalogo de medidas. */
        UNIDAD,
        /** CCPIP-504: un movimiento de inventario que registro el administrador de un punto de acopio. */
        MOVIMIENTO, CUENTA_BANCARIA, TIENDA, DIRECCION, INTERES, SESION, SEGURIDAD, REPORTE, AVISO, CARRITO,
        TRADUCCION, IMAGEN, ANALISIS, CALIFICACION, TRANSFERENCIA, SOLICITUD_UNIDAD
    }

    /**
     * Como termino. Las acciones de gobernanza, de productos y del catalogo solo se registran cuando se
     * completan ({@code EXITOSA}); un pago registra tambien los intentos rechazados o con error.
     */
    public enum Resultado { EXITOSA, APROBADO, RECHAZADO, ERROR, PENDIENTE, ANULADO }
    public enum Actor { USUARIO, SISTEMA, ANONIMO }

    /**
     * Un registro. {@code clave} es unica (tabla de origen e id). {@code usuario} y {@code rol} son de
     * quien lo hizo; nulos si lo hizo el sistema. {@code accion} es un codigo que la pantalla traduce;
     * {@code detalle}, el nombre de lo afectado (producto, numero de pedido, organizacion).
     */
    public record Registro(String clave, Instant fecha, Long usuarioId, String usuario, String correo, Rol rol,
                           String accion, Entidad entidad, long entidadId, String detalle, Resultado resultado,
                           Actor actor, Long ubicacionId, String referencia, String correlacion, String decision,
                           boolean rolHistorico, String metadatos) {
    }

    /** Filtros, todos opcionales y combinables. {@code usuario} busca en el nombre y el correo. */
    public record Filtro(LocalDate desde, LocalDate hasta, String usuario, Rol rol, String accion, Entidad entidad,
                         Resultado resultado, Actor actor, Long ubicacion, String referencia) {
    }

    /** Una pagina de la auditoria del periodo (dias de Lima, inclusive), la mas reciente primero. */
    public record PaginaAuditoria(LocalDate desde, LocalDate hasta, List<Registro> registros, int pagina, int tamano,
                                  long total, int totalPaginas, List<String> acciones) {
    }
}
