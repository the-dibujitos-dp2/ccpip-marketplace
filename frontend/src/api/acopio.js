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

/**
 * CCPIP-71: entrega un pedido listo a quien lo recoge. `{ persona, celular }` se validan en el
 * servidor contra el comprador o la persona autorizada; devuelve el pedido ya entregado.
 */
export const registrarRecojo = (numero, datos) =>
  apiFetch(`/api/acopio/pedidos/${encodeURIComponent(numero)}/recojo`, { method: 'POST', body: datos })

/** CCPIP-278: lo que entra o sale de sus puntos y lo que espera su recepcion. */
export const listarTransferencias = () => apiFetch('/api/acopio/transferencias')

/** CCPIP-278: el detalle con su historial de estados. */
export const obtenerTransferencia = (id) => apiFetch(`/api/acopio/transferencias/${encodeURIComponent(id)}`)

/** CCPIP-278: el punto de destino registra la recepcion; las unidades entran a un lote del punto. */
export const recibirTransferencia = (id) => apiFetch(`/api/acopio/transferencias/${encodeURIComponent(id)}/recibir`, { method: 'POST' })
