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
