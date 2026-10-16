// API de la administracion de la Camara. El servidor valida el rol (CCPIP-111, RNF-04).
import { apiFetch, idRuta } from './http.js'

/** CCPIP-111 (SA-01): ventas, comision, productores, lineas y puntos de acopio del mes en curso. */
export const obtenerTablero = () => apiFetch('/api/admin/tablero')

/** CCPIP-90 (SA-02): comision vigente, cambio programado e historial. Solo superadministrador. */
export const obtenerComision = () => apiFetch('/api/admin/comision')

/** CCPIP-90: { porcentaje, vigenteDesde: 'YYYY-MM-DD', motivo }. Devuelve el estado actualizado. */
export const programarComision = (cambio) => apiFetch('/api/admin/comision', { method: 'POST', body: cambio })

/** CCPIP-90: cancela un cambio que aun no entra en vigor. */
export const cancelarComision = (id) => apiFetch(`/api/admin/comision/${idRuta(id)}/cancelar`, { method: 'POST' })

/** CCPIP-91: pedidos confirmados con su comision y neto. Sin fechas, el mes en curso. */
export function comisionesPorPedido({ desde, hasta } = {}) {
  const q = new URLSearchParams()
  if (desde) q.set('desde', desde)
  if (hasta) q.set('hasta', hasta)
  const consulta = q.toString()
  return apiFetch(`/api/admin/comision/pedidos${consulta ? `?${consulta}` : ''}`)
}
