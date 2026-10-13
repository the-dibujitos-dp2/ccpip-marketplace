// API del administrador de punto de acopio. El servidor filtra todo por los puntos de la sesion (RNF-05).
import { apiFetch } from './http.js'

/** CCPIP-158 (A-03): `tipo` es la pestana (ENTRADAS, SALIDAS o AJUSTES); `pedido`, un numero exacto. */
export function consultarMovimientos({ tipo, pedido, pagina = 0 } = {}) {
  const p = new URLSearchParams({ pagina: String(pagina) })
  if (tipo) p.set('tipo', tipo)
  if (pedido?.trim()) p.set('pedido', pedido.trim())
  return apiFetch(`/api/acopio/movimientos?${p}`)
}

/** CCPIP-159 (A-05): los pedidos del punto, por columna: confirmados, listos para recojo y entregados hoy. */
export const listarPedidos = () => apiFetch('/api/acopio/pedidos')

/** CCPIP-159: marca un pedido confirmado como listo para recojo y devuelve el pedido actualizado. */
export const marcarListo = (numero) => apiFetch(`/api/acopio/pedidos/${encodeURIComponent(numero)}/listo`, { method: 'POST' })
