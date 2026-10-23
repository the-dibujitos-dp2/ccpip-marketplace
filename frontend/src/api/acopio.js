// API del administrador de punto de acopio. El servidor filtra todo por los puntos de la sesion (RNF-05).
import { apiFetch } from './http.js'

/** CCPIP-158 (A-03): `tipo` es la pestana (ENTRADAS, SALIDAS o AJUSTES); `pedido`, un numero exacto. */
export function consultarMovimientos({ tipo, pedido, pagina = 0 } = {}) {
  const p = new URLSearchParams({ pagina: String(pagina) })
  if (tipo) p.set('tipo', tipo)
  if (pedido?.trim()) p.set('pedido', pedido.trim())
  return apiFetch(`/api/acopio/movimientos?${p}`)
}

/** CCPIP-159 (A-05): los pedidos del punto, por columna: confirmados, en preparacion, listos y entregados hoy. */
export const listarPedidos = () => apiFetch('/api/acopio/pedidos')

/** CCPIP-159: pasa un pedido en preparación a listo para recojo y devuelve el pedido actualizado. */
export const marcarListo = (numero) => apiFetch(`/api/acopio/pedidos/${encodeURIComponent(numero)}/listo`, { method: 'POST' })

/** CCPIP-68: registra que un pedido confirmado está en preparación. */
export const marcarPreparacion = (numero) => apiFetch(`/api/acopio/pedidos/${encodeURIComponent(numero)}/preparacion`, { method: 'POST' })

/**
 * CCPIP-71: entrega un pedido listo a quien lo recoge. `{ persona, celular }` se validan en el
 * servidor contra el comprador o la persona autorizada; devuelve el pedido ya entregado.
 */
export const registrarRecojo = (numero, datos) =>
  apiFetch(`/api/acopio/pedidos/${encodeURIComponent(numero)}/recojo`, { method: 'POST', body: datos })

/**
 * CCPIP-162 (A-02): los lotes de sus puntos por producto, productor y estado. `estado`: ACTIVOS (por
 * defecto), POR_VENCER (CCPIP-145, segun la anticipacion del punto), AGOTADOS o TODOS; `productor`, un
 * id; `q`, producto o codigo de lote.
 */
export function consultarInventario({ q, productor, estado, pagina = 0 } = {}) {
  const p = new URLSearchParams({ pagina: String(pagina) })
  if (q?.trim()) p.set('q', q.trim())
  if (productor) p.set('productor', String(productor))
  if (estado && estado !== 'ACTIVOS') p.set('estado', estado)
  return apiFetch(`/api/acopio/inventario?${p}`)
}

/** CCPIP-121 y CCPIP-287: todos los lotes de sus puntos, aun sin saldo, para el formulario de movimiento. */
export const listarLotesPunto = () => apiFetch('/api/acopio/inventario/lotes')

/**
 * CCPIP-121 y CCPIP-287: registra una entrada o salida en un lote de su punto.
 * `{ tipo, loteId, cantidad, motivo, observacion, clave }`; devuelve `{ movimiento, saldoLote, repetido }`.
 */
export const registrarMovimiento = (datos) => apiFetch('/api/acopio/movimientos', { method: 'POST', body: datos })

/** CCPIP-278: lo que entra o sale de sus puntos y lo que espera su recepcion. */
export const listarTransferencias = () => apiFetch('/api/acopio/transferencias')

/** CCPIP-278: el detalle con su historial de estados. */
export const obtenerTransferencia = (id) => apiFetch(`/api/acopio/transferencias/${encodeURIComponent(id)}`)

/** CCPIP-278: el punto de destino registra la recepcion; las unidades entran a un lote del punto. */
export const recibirTransferencia = (id) => apiFetch(`/api/acopio/transferencias/${encodeURIComponent(id)}/recibir`, { method: 'POST' })
