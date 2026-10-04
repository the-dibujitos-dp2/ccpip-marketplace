// API del productor (PW-01 a PW-08). El servidor filtra todo por el productor de la sesion.
import { ApiError, apiFetch, restartIdle } from './http.js'

const base = '/api/productor'

export const obtenerPanel = () => apiFetch(`${base}/panel`)

export const obtenerTienda = () => apiFetch(`${base}/tienda`)
export const guardarTienda = (datos) => apiFetch(`${base}/tienda`, { method: 'PUT', body: datos })
export const publicarTienda = () => apiFetch(`${base}/tienda/publicar`, { method: 'POST' })

export function listarProductos({ estado = 'PUBLICADO', q } = {}) {
  const p = new URLSearchParams({ estado })
  if (q?.trim()) p.set('q', q.trim())
  return apiFetch(`${base}/productos?${p}`)
}
export const obtenerProducto = (id) => apiFetch(`${base}/productos/${id}`)
export const crearProducto = (datos) => apiFetch(`${base}/productos`, { method: 'POST', body: datos })
export const editarProducto = (id, datos) => apiFetch(`${base}/productos/${id}`, { method: 'PUT', body: datos })
export const publicarProducto = (id) => apiFetch(`${base}/productos/${id}/publicar`, { method: 'POST' })
export const retirarProducto = (id) => apiFetch(`${base}/productos/${id}/retirar`, { method: 'POST' })

export const guardarHistoria = (id, datos) => apiFetch(`${base}/productos/${id}/historia`, { method: 'PUT', body: datos })
export const proponerHistoria = (id, respuestas, variante = 'NORMAL') =>
  apiFetch(`${base}/productos/${id}/historia/propuesta`, { method: 'POST', body: { respuestas, variante } })
export const aprobarHistoria = (id, datos) => apiFetch(`${base}/productos/${id}/historia/aprobar`, { method: 'POST', body: datos })
export const proponerDescripcion = (datos) => apiFetch(`${base}/ia/descripcion`, { method: 'POST', body: datos })

export const listarUbicaciones = () => apiFetch(`${base}/ubicaciones`)
export const listarLotes = (id) => apiFetch(`${base}/productos/${id}/lotes`)
export const registrarLote = (id, datos) => apiFetch(`${base}/productos/${id}/lotes`, { method: 'POST', body: datos })

export const marcarPrincipal = (imagenId) => apiFetch(`${base}/imagenes/${imagenId}/principal`, { method: 'PUT' })
export const eliminarImagen = (imagenId) => apiFetch(`${base}/imagenes/${imagenId}`, { method: 'DELETE' })

/**
 * Sube UNA imagen (CCPIP-44). Cada archivo va en su propia peticion: si la conexion se corta,
 * solo se reintenta ese archivo y los ya subidos se conservan.
 */
export async function subirImagen(archivo, { tipo, categoria, productoId }) {
  const datos = new FormData()
  datos.append('archivo', archivo)
  datos.append('tipo', tipo)
  if (categoria) datos.append('categoria', categoria)
  if (productoId) datos.append('productoId', String(productoId))
  const res = await fetch(`${base}/imagenes`, { method: 'POST', body: datos, credentials: 'same-origin' })
  restartIdle()
  const cuerpo = await res.json().catch(() => ({}))
  if (!res.ok) throw new ApiError(res.status, cuerpo)
  return cuerpo
}
