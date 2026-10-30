package pe.ccpip.auth.access;

/**
 * CCPIP-296 (RF-172): permisos que el superadministrador puede conceder o revocar a un rol. Cada uno agrupa
 * acciones que el servidor ya protege por rol; se marcan con {@link PermisoConfigurable}. Las restricciones
 * de gobernanza (editar productos, tiendas, pedidos, datos de compradores o existencias) viven solo en la
 * base, porque ningun endpoint las ofrece a la administracion y nunca se conceden.
 */
public enum Permiso {
    /** Aprobar, rechazar o revocar autorizaciones, pedir renovaciones y registrar evidencias fisicas. */
    DECIDIR_AUTORIZACIONES,
    /** Aprobar o rechazar publicaciones y videos. */
    MODERAR_PUBLICACIONES,
    /** Habilitar tienda, revocar, restituir, bloquear o desbloquear la venta de un productor. */
    GESTIONAR_PRODUCTORES,
    /** Cambiar el estado de una denuncia. */
    GESTIONAR_DENUNCIAS,
    /** Suspender o reactivar cuentas. */
    SUSPENDER_CUENTAS
}
