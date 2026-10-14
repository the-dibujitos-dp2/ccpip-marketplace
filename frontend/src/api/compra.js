// Compra del comprador: recojo (CCPIP-157), pago (CCPIP-62, CCPIP-163) y confirmacion (CCPIP-64).
import { apiFetch, idRuta } from './http.js'

/** Puntos de acopio con existencias para todo el carrito: [{ id, nombre, departamento, provincia, distrito, direccion }]. */
export const consultarPuntosRecojo = () => apiFetch('/api/compra/puntos')

/** Crea el pedido en espera de pago. No descuenta stock hasta que el pago se aprueba. */
export const confirmarCompra = ({ puntoId, personaAutorizada, celularContacto }) =>
  apiFetch('/api/pedidos', { method: 'POST', body: { puntoId: Number(puntoId), personaAutorizada, celularContacto } })

/**
 * Paga el pedido. `claveIdempotencia` identifica el intento: reenviarla (corte de red, doble clic)
 * no vuelve a cobrar. La tarjeta solo viaja para cobrar; no se guarda.
 * Devuelve { estado, mensaje, pedido }.
 */
export const pagarPedido = (pedidoId, claveIdempotencia, tarjeta) =>
  apiFetch(`/api/pedidos/${idRuta(pedidoId)}/pagos`, { method: 'POST', body: { claveIdempotencia, tarjeta } })

/** Pedido con numero, lineas, importes, punto de recojo e intentos de pago. */
export const consultarPedido = (pedidoId) => apiFetch(`/api/pedidos/${idRuta(pedidoId)}`)
