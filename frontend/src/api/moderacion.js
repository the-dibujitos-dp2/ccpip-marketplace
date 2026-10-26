// CCPIP-46, CCPIP-331 y CCPIP-332: moderacion de la publicacion de ofertas. Solo el sub administrador;
// el servidor valida el rol en cada peticion (RNF-04).
import { apiFetch, idRuta } from './http.js'

/** CCPIP-46: contadores y solicitudes. estado: PENDIENTE (por defecto), APROBADA o RECHAZADA. */
export const bandejaModeracion = (estado = 'PENDIENTE') => apiFetch(`/api/admin/moderacion?${new URLSearchParams({ estado })}`)

/** CCPIP-46: la oferta, su autorizacion de venta con sus evidencias y el historial de solicitudes. */
export const expedienteModeracion = async (id) => apiFetch(`/api/admin/moderacion/${idRuta(id)}`)
