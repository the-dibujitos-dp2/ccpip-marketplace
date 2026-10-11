// Carrito del comprador (CCPIP-58). Requiere sesion de consumidor.
import { apiFetch } from './http.js'

/** Agrega una oferta especifica con su cantidad. Devuelve { productoId, cantidad, articulos }. */
export const agregarAlCarrito = (productoId, cantidad) =>
  apiFetch('/api/carrito/items', { method: 'POST', body: { productoId: Number(productoId), cantidad } })
