// Videos de la tienda del productor (acordado el 26/10, RNF-25): MP4 o WebM propios, de hasta 50 MB y
// 3 minutos, hasta 5 por tienda. Cada uno pasa por la revision de la Camara antes de verse (CCPIP-118).
import { apiFetch, idRuta } from './http.js'
import { enviarArchivo } from './productor.js'

export const MAX_VIDEOS = 5
export const MAX_MB = 50
export const MAX_MINUTOS = 3
export const TIPOS_VIDEO = ['video/mp4', 'video/webm']

/** CCPIP-118: sube un video propio; queda en revision. */
export async function subirVideo(archivo, titulo) {
  const datos = new FormData()
  datos.append('titulo', titulo)
  datos.append('archivo', archivo)
  return enviarArchivo('/api/productor/videos', datos)
}

/** CCPIP-390: los videos vigentes de su tienda, con su estado y el motivo si se rechazaron. */
export const misVideos = () => apiFetch('/api/productor/videos')

/**
 * Ocupa un lugar en la tienda: no esta rechazado ni es un reemplazo en revision de un video que sigue en la
 * lista (CCPIP-391). `lista` son los videos vigentes.
 */
export const ocupaLugar = (v, lista) =>
  v.estado !== 'RECHAZADO' && !(v.reemplazaA && v.estado === 'EN_REVISION' && lista.some((o) => o.id === v.reemplazaA))

/** CCPIP-391: otro video en lugar de uno propio; el nuevo pasa por la revision. `titulo` es opcional. */
export async function reemplazarVideo(id, archivo, titulo) {
  const datos = new FormData()
  if (titulo) datos.append('titulo', titulo)
  datos.append('archivo', archivo)
  return enviarArchivo(`/api/productor/videos/${idRuta(id)}/reemplazo`, datos)
}

/** CCPIP-392: retiro logico; deja de verse en la tienda, pero no se borra. */
export const retirarVideo = (id) => apiFetch(`/api/productor/videos/${idRuta(id)}`, { method: 'DELETE' })
