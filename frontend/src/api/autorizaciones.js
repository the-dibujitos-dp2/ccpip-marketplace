// CCPIP-36, CCPIP-50, CCPIP-174 y CCPIP-284: evidencias por producto y su revision. El servidor
// filtra por el productor de la sesion (RNF-05) y valida el rol en cada peticion (RNF-04).
import { ApiError, apiFetch, idRuta, restartIdle } from './http.js'

// --- Productor (PW-14 adaptada) ---

/** La solicitud de autorizacion del producto y sus evidencias vigentes. */
export const listarEvidencias = async (productoId) => apiFetch(`/api/productor/productos/${idRuta(productoId)}/evidencias`)

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

/** Retiro logico: deja de sustentar la solicitud, pero se conserva. */
export const retirarEvidencia = async (productoId, evidenciaId) =>
  apiFetch(`/api/productor/productos/${idRuta(productoId)}/evidencias/${idRuta(evidenciaId)}`, { method: 'DELETE' })

// --- Subadministrador (S-01, S-02) ---

/** S-01: contadores y solicitudes. estado: SOLICITADA (pendientes), APROBADA o RECHAZADA. */
export const bandejaAutorizaciones = (estado = 'SOLICITADA') =>
  apiFetch(`/api/admin/autorizaciones?${new URLSearchParams({ estado })}`)

/** S-02: producto, productor, evidencias, ultimo analisis y decisiones. */
export const expedienteAutorizacion = async (id) => apiFetch(`/api/admin/autorizaciones/${idRuta(id)}`)

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
