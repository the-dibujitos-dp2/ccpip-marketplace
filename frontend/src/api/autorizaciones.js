// CCPIP-36, CCPIP-50, CCPIP-174 y CCPIP-284: evidencias por producto y su revision. El servidor
// filtra por el productor de la sesion (RNF-05) y valida el rol en cada peticion (RNF-04).
import { ApiError, apiFetch, idRuta, restartIdle } from './http.js'

// --- Productor (PW-14 adaptada) ---

/** La solicitud de autorizacion del producto y sus evidencias vigentes. */
export const listarEvidencias = async (productoId) => apiFetch(`/api/productor/productos/${idRuta(productoId)}/evidencias`)

/** CCPIP-336: lee el vínculo persistido (204 equivale a null). */
export const leerLoteEvidencia = async (productoId, evidenciaId) => {
  try { return await apiFetch(`/api/productor/productos/${idRuta(productoId)}/evidencias/${idRuta(evidenciaId)}/lote`) }
  catch (e) { if (e.status === 204) return null; throw e }
}
export const vincularEvidenciaLote = (productoId, evidenciaId, loteId) =>
  apiFetch(`/api/productor/productos/${idRuta(productoId)}/evidencias/${idRuta(evidenciaId)}/lote`, { method: 'POST', body: { loteId: Number(loteId) } })

/**
 * Sube UNA evidencia con lo que la identifica: tipo, titulo, emisor y vigencia (RF-13). El
 * servidor reconoce el formato por el contenido del archivo, no por su extension.
 */
export async function subirEvidencia(productoId, archivo, { tipo, titulo, emisor, vigenteDesde, vigenteHasta }) {
  const datos = new FormData()
  datos.append('archivo', archivo)
  datos.append('tipo', tipo)
  datos.append('titulo', titulo)
  if (emisor) datos.append('emisor', emisor)
  if (vigenteDesde) datos.append('vigenteDesde', vigenteDesde)
  if (vigenteHasta) datos.append('vigenteHasta', vigenteHasta)
  const res = await fetch(`/api/productor/productos/${idRuta(productoId)}/evidencias`, {
    method: 'POST',
    body: datos,
    credentials: 'same-origin',
  })
  restartIdle()
  const cuerpo = await res.json().catch(() => ({}))
  if (!res.ok) throw new ApiError(res.status, cuerpo)
  return cuerpo
}

/** CCPIP-29: envia la solicitud de autorizacion del producto a la Camara. Devuelve la vista actualizada. */
export const solicitarAutorizacion = async (productoId) =>
  apiFetch(`/api/productor/productos/${idRuta(productoId)}/autorizacion/solicitar`, { method: 'POST' })

/** Retiro logico: deja de sustentar la solicitud, pero se conserva. */
export const retirarEvidencia = async (productoId, evidenciaId) =>
  apiFetch(`/api/productor/productos/${idRuta(productoId)}/evidencias/${idRuta(evidenciaId)}`, { method: 'DELETE' })

/** CCPIP-34: catalogo de reglas de la Camara y lo declarado para el producto (vigente e historial). */
export const consultarCumplimiento = async (productoId) => apiFetch(`/api/productor/productos/${idRuta(productoId)}/cumplimiento`)

/** CCPIP-34: declara las reglas que cumple el producto (puede ser ninguna). Devuelve la vista actualizada. */
export const declararCumplimiento = async (productoId, { reglas, observaciones }) =>
  apiFetch(`/api/productor/productos/${idRuta(productoId)}/cumplimiento`, {
    method: 'POST',
    body: { reglas, observaciones: observaciones || null },
  })

// --- Subadministrador (S-01, S-02) ---

/** S-01: contadores y solicitudes. estado: SOLICITADA (pendientes), APROBADA o RECHAZADA. */
export const bandejaAutorizaciones = (estado = 'SOLICITADA') =>
  apiFetch(`/api/admin/autorizaciones?${new URLSearchParams({ estado })}`)

/** CCPIP-184: certificados de productos autorizados. estado: POR_VENCER, VENCIDO o VIGENTE; dias: 30, 60 o 90. */
export const vigenciaCertificados = (estado = 'POR_VENCER', dias = 30) =>
  apiFetch(`/api/admin/certificados?${new URLSearchParams({ estado, dias: String(dias) })}`)

/** S-02: producto, productor, evidencias, ultimo analisis y decisiones. */
export const expedienteAutorizacion = async (id) => apiFetch(`/api/admin/autorizaciones/${idRuta(id)}`)

/** CCPIP-321: solicitudes de renovacion de certificados de la autorizacion (S-02). */
export const renovacionesAutorizacion = async (id) => apiFetch(`/api/admin/autorizaciones/${idRuta(id)}/renovaciones`)

/** CCPIP-321: pide renovar un certificado; `plazo` es opcional (por defecto, su vencimiento). */
export const solicitarRenovacion = async (id, { evidenciaId, motivo, plazo }) =>
  apiFetch(`/api/admin/autorizaciones/${idRuta(id)}/renovaciones`, { method: 'POST', body: { evidenciaId, motivo, plazo: plazo || null } })

/** CCPIP-321: las solicitudes de renovacion de un producto propio (PW-14). */
export const renovacionesDeProducto = async (productoId) => apiFetch(`/api/productor/productos/${idRuta(productoId)}/renovaciones`)

/** CCPIP-35 y CCPIP-329: evidencias fisicas de la solicitud (S-02), la mas reciente primero. */
export const evidenciasFisicas = async (id) => apiFetch(`/api/admin/autorizaciones/${idRuta(id)}/evidencias-fisicas`)

/** CCPIP-35: registra el envio de una evidencia fisica. `referencia` y `observaciones` son opcionales. */
export const registrarEnvioFisica = async (id, { descripcion, referencia, enviadaEl, observaciones }) =>
  apiFetch(`/api/admin/autorizaciones/${idRuta(id)}/evidencias-fisicas`, {
    method: 'POST',
    body: { descripcion, referencia: referencia || null, enviadaEl, observaciones: observaciones || null },
  })

/** CCPIP-329: registra la recepcion de una evidencia fisica con su resultado, una sola vez. */
export const registrarRecepcionFisica = async (id, fisicaId, { recibidaEl, resultado, observaciones }) =>
  apiFetch(`/api/admin/autorizaciones/${idRuta(id)}/evidencias-fisicas/${idRuta(fisicaId)}/recepcion`, {
    method: 'POST',
    body: { recibidaEl, resultado, observaciones: observaciones || null },
  })

/** CCPIP-50: analisis preliminar. 503 IA_NO_DISPONIBLE si no hay servicio: se revisa a mano. */
export const analizarEvidencias = async (id) => apiFetch(`/api/admin/autorizaciones/${idRuta(id)}/analisis`, { method: 'POST' })

/** CCPIP-174: aprobar con motivo. Devuelve el expediente actualizado. */
export const aprobarAutorizacion = async (id, motivo) =>
  apiFetch(`/api/admin/autorizaciones/${idRuta(id)}/aprobar`, { method: 'POST', body: { motivo } })

/** CCPIP-284: rechazar con motivo. Devuelve el expediente actualizado. */
export const rechazarAutorizacion = async (id, motivo) =>
  apiFetch(`/api/admin/autorizaciones/${idRuta(id)}/rechazar`, { method: 'POST', body: { motivo } })

/** CCPIP-39: revocar con motivo una autorizacion APROBADA. Devuelve el expediente actualizado. */
export const revocarAutorizacion = async (id, motivo) =>
  apiFetch(`/api/admin/autorizaciones/${idRuta(id)}/revocar`, { method: 'POST', body: { motivo } })

// --- Productores y los productos que pueden vender (CCPIP-292, CCPIP-291, CCPIP-330) ---

/** Productores con su tienda, su venta y el resumen de sus autorizaciones. */
export const listarProductores = async () => apiFetch('/api/admin/productores')

/** Ficha: cada producto con su autorizacion y lineas autorizadas, y el historial de decisiones. */
export const fichaProductor = async (id) => apiFetch(`/api/admin/productores/${idRuta(id)}`)

/**
 * Decision sobre el productor completo, siempre con motivo: 'habilitar-tienda' (CCPIP-291),
 * 'revocar' o 'restituir' (CCPIP-330), 'bloquear' o 'desbloquear' (CCPIP-85, CCPIP-322).
 * Devuelve la ficha actualizada.
 */
export const decidirProductor = async (id, accion, motivo) =>
  apiFetch(`/api/admin/productores/${idRuta(id)}/${accion}`, { method: 'POST', body: { motivo } })
