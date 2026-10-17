// CCPIP-98: los errores del servidor en el idioma elegido. La API responde { code, error } con el
// mensaje en espanol; en ingles se traduce por el code, y un code sin traduccion cae en un
// mensaje generico para no mostrar espanol a medias.
import { ApiError } from '../api/http.js'
import i18n from './index.js'

/**
 * Mensaje para mostrar de un error. `respaldo`: clave para errores de red o codes desconocidos.
 * En espanol se respeta el mensaje del servidor, que suele ser mas especifico.
 */
export function mensajeError(error, respaldo = 'errores.generico') {
  const delServidor = error instanceof ApiError && error.body?.error
  if (delServidor && i18n.resolvedLanguage === 'es') return error.message
  if (error?.code && i18n.exists(`errores.${error.code}`)) return i18n.t(`errores.${error.code}`)
  return i18n.t(respaldo)
}

/**
 * Los errores por campo de una validacion (`body.campos`). Llegan en espanol: en ingles se marca
 * cada campo con el mensaje del code.
 */
export function erroresPorCampo(error) {
  const campos = error.body.campos
  if (i18n.resolvedLanguage === 'es') return campos
  return Object.fromEntries(Object.keys(campos).map((c) => [c, mensajeError(error)]))
}

/**
 * Lo que falta para publicar un producto o una tienda llega del servidor como texto en espanol
 * (publicable.faltan y detalles de PRODUCTO_NO_PUBLICABLE, PRODUCTO_INCOMPLETO, TIENDA_INCOMPLETA).
 * Es un conjunto cerrado: se reconoce y se traduce; un texto nuevo se muestra tal cual.
 */
const FALTANTES = {
  'Al menos una línea de producto': 'linea',
  'La unidad de medida': 'unidad',
  'El precio minorista': 'precioMinorista',
  'El precio mayorista y su cantidad mínima': 'precioMayorista',
  'Un lote vigente con stock en un punto de acopio': 'lote',
  'Publicar tu tienda (Mi tienda)': 'tienda',
  'El nombre de la tienda': 'nombreTienda',
  'La descripción de la tienda': 'descripcionTienda',
  'La historia de tu comunidad': 'historiaComunidad',
  'El pueblo de pertenencia': 'pueblo',
}

export function traducirFaltante(texto) {
  const clave = FALTANTES[texto]
  return clave ? i18n.t(`faltantes.${clave}`) : texto
}

/** Si lo que falta tiene que ver con la tienda, para ofrecer el atajo a "Mi tienda". Se compara el texto original. */
export const esFaltanteDeTienda = (texto) => texto.toLowerCase().includes('tienda')
