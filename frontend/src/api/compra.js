// Compra del comprador: recojo (CCPIP-157), pago (CCPIP-62, CCPIP-163) y confirmacion (CCPIP-64).
import { apiFetch, idRuta } from './http.js'

/** Puntos de acopio con existencias para todo el carrito: [{ id, nombre, departamento, provincia, distrito, direccion }]. */
export const consultarPuntosRecojo = () => apiFetch('/api/compra/puntos')

/**
 * CCPIP-59: distritos a los que se entrega a domicilio todo el carrito, cada uno con los puntos que lo
 * cubren: [{ distrito: { ubigeo, departamento, provincia, distrito }, puntos: [punto] }].
 */
export const consultarDistritosEntrega = () => apiFetch('/api/compra/distritos')

/**
 * CCPIP-295: los productos perecibles del carrito si sale del punto elegido, con hasta cuando se consumen:
 * [{ productoId, nombre, consumirAntesDe, diasRestantes }]. Lo que no vence no aparece.
 */
export const consultarAdvertencias = (puntoId) => apiFetch(`/api/compra/advertencias?punto=${idRuta(puntoId)}`)

/**
 * Crea el pedido en espera de pago. No descuenta stock hasta que el pago se aprueba. Con `entrega`
 * (CCPIP-59) es a domicilio: { ubigeo, direccion, referencia, contactoNombre, contactoCelular }; sin
 * ella es recojo, con persona autorizada y celular de contacto.
 */
export const confirmarCompra = ({ puntoId, personaAutorizada, celularContacto, entrega }) =>
  apiFetch('/api/pedidos', {
    method: 'POST',
    body: entrega
      ? { puntoId: Number(puntoId), modalidad: 'ENVIO', entrega }
      : { puntoId: Number(puntoId), personaAutorizada, celularContacto },
  })

/**
 * Paga el pedido. `claveIdempotencia` identifica el intento: reenviarla (corte de red, doble clic)
 * no vuelve a cobrar. La tarjeta solo viaja para cobrar; no se guarda.
 * Devuelve { estado, mensaje, pedido }.
 */
export const pagarPedido = (pedidoId, claveIdempotencia, tarjeta) =>
  apiFetch(`/api/pedidos/${idRuta(pedidoId)}/pagos`, { method: 'POST', body: { claveIdempotencia, tarjeta } })

/**
 * CCPIP-112: las compras del comprador, la mas reciente primero, con estado, total pagado, punto,
 * cuantos productos y comunidades y su historial de estados.
 */
export const listarMisPedidos = () => apiFetch('/api/pedidos')

/** Pedido con numero, lineas, importes, punto de recojo, intentos de pago e historial de estados. */
export const consultarPedido = (pedidoId) => apiFetch(`/api/pedidos/${idRuta(pedidoId)}`)
