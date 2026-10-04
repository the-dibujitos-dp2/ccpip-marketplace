// API del productor (PW-01 a PW-08). El servidor filtra todo por el productor de la sesion.
import { ApiError, apiFetch, idRuta, restartIdle } from './http.js'

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
export const obtenerProducto = async (id) => apiFetch(`${base}/productos/${idRuta(id)}`)
export const crearProducto = (datos) => apiFetch(`${base}/productos`, { method: 'POST', body: datos })
export const editarProducto = async (id, datos) => apiFetch(`${base}/productos/${idRuta(id)}`, { method: 'PUT', body: datos })
export const publicarProducto = async (id) => apiFetch(`${base}/productos/${idRuta(id)}/publicar`, { method: 'POST' })
export const retirarProducto = async (id) => apiFetch(`${base}/productos/${idRuta(id)}/retirar`, { method: 'POST' })

export const guardarHistoria = async (id, datos) => apiFetch(`${base}/productos/${idRuta(id)}/historia`, { method: 'PUT', body: datos })
export const proponerHistoria = async (id, respuestas, variante = 'NORMAL') =>
  apiFetch(`${base}/productos/${idRuta(id)}/historia/propuesta`, { method: 'POST', body: { respuestas, variante } })
export const aprobarHistoria = async (id, datos) => apiFetch(`${base}/productos/${idRuta(id)}/historia/aprobar`, { method: 'POST', body: datos })
export const proponerDescripcion = (datos) => apiFetch(`${base}/ia/descripcion`, { method: 'POST', body: datos })

export const listarUbicaciones = () => apiFetch(`${base}/ubicaciones`)
export const listarLotes = async (id) => apiFetch(`${base}/productos/${idRuta(id)}/lotes`)
export const registrarLote = async (id, datos) => apiFetch(`${base}/productos/${idRuta(id)}/lotes`, { method: 'POST', body: datos })

export const marcarPrincipal = async (imagenId) => apiFetch(`${base}/imagenes/${idRuta(imagenId)}/principal`, { method: 'PUT' })
export const eliminarImagen = async (imagenId) => apiFetch(`${base}/imagenes/${idRuta(imagenId)}`, { method: 'DELETE' })

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
