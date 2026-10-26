// Videos de la tienda del productor (acordado el 26/10, RNF-25): MP4 o WebM propios, de hasta 50 MB y
// 3 minutos, hasta 5 por tienda. Cada uno pasa por la revision de la Camara antes de verse (CCPIP-118).
import { apiFetch } from './http.js'
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

/** Ocupa un lugar en la tienda: no esta rechazado ni es un reemplazo en revision (CCPIP-391). */
export const ocupaLugar = (v) => v.estado !== 'RECHAZADO' && !(v.reemplazaA && v.estado === 'EN_REVISION')
