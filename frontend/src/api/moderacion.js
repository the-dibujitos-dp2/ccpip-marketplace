// CCPIP-46, CCPIP-331 y CCPIP-332: moderacion de la publicacion de ofertas. Solo el sub administrador;
// el servidor valida el rol en cada peticion (RNF-04).
import { apiFetch, idRuta } from './http.js'

/** CCPIP-46: contadores y solicitudes. estado: PENDIENTE (por defecto), APROBADA o RECHAZADA. */
export const bandejaModeracion = (estado = 'PENDIENTE') => apiFetch(`/api/admin/moderacion?${new URLSearchParams({ estado })}`)

/** CCPIP-46: la oferta, su autorizacion de venta con sus evidencias y el historial de solicitudes. */
export const expedienteModeracion = async (id) => apiFetch(`/api/admin/moderacion/${idRuta(id)}`)

/** CCPIP-331: aprobar con motivo; la oferta pasa al catalogo. Devuelve el expediente actualizado. */
export const aprobarModeracion = async (id, motivo) =>
  apiFetch(`/api/admin/moderacion/${idRuta(id)}/aprobar`, { method: 'POST', body: { motivo } })

/** CCPIP-332: rechazar con motivo; la oferta vuelve a su estado anterior. Devuelve el expediente actualizado. */
export const rechazarModeracion = async (id, motivo) =>
  apiFetch(`/api/admin/moderacion/${idRuta(id)}/rechazar`, { method: 'POST', body: { motivo } })

// CCPIP-118: los videos de las tiendas pasan por la misma revision.
/** Contadores y videos. estado: PENDIENTE (por defecto), APROBADA o RECHAZADA. */
export const bandejaVideos = (estado = 'PENDIENTE') => apiFetch(`/api/admin/moderacion/videos?${new URLSearchParams({ estado })}`)

/** El video para revisar, con el que reemplaza si es un reemplazo. */
export const expedienteVideo = async (id) => apiFetch(`/api/admin/moderacion/videos/${idRuta(id)}`)

/** Aprobar con motivo: el video se ve en la tienda. Devuelve el expediente actualizado. */
export const aprobarVideo = async (id, motivo) =>
  apiFetch(`/api/admin/moderacion/videos/${idRuta(id)}/aprobar`, { method: 'POST', body: { motivo } })

/** Rechazar con motivo: el productor lo ve. Devuelve el expediente actualizado. */
export const rechazarVideo = async (id, motivo) =>
  apiFetch(`/api/admin/moderacion/videos/${idRuta(id)}/rechazar`, { method: 'POST', body: { motivo } })
