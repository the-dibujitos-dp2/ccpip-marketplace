// CCPIP-105 (CW-11): interes de compra del consumidor en una oferta agotada o pausada. El servidor
// toma al comprador de la sesion; no hay reserva ni cobro.
import { apiFetch, idRuta } from './http.js'

const ruta = (productoId) => `/api/productos/${idRuta(productoId)}/interes`

/** El interes pendiente en la oferta, o null si no tiene (204). */
export const consultarInteres = (productoId) => apiFetch(ruta(productoId))

/** Registra el interes o cambia la cantidad. */
export const registrarInteres = (productoId, cantidad) => apiFetch(ruta(productoId), { method: 'PUT', body: { cantidad } })

export const retirarInteres = (productoId) => apiFetch(ruta(productoId), { method: 'DELETE' })
