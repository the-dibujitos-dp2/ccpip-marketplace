// Carrito del comprador (CCPIP-58, CCPIP-155). Requiere sesion de consumidor.
import { useEffect, useState } from 'react'
import { apiFetch } from './http.js'

/** Agrega una oferta especifica con su cantidad. Devuelve { productoId, cantidad, articulos }. */
export const agregarAlCarrito = (productoId, cantidad) =>
  apiFetch('/api/carrito/items', { method: 'POST', body: { productoId: Number(productoId), cantidad } })

/** Carrito con precio y stock revalidados: { items, unidades, comunidades, total }. */
export const consultarCarrito = () => apiFetch('/api/carrito')

// Unidades del carrito para el boton "Carrito · N" de la cabecera. Se comparten entre pantallas
// para no consultar el carrito en cada una; quien lo cambia avisa con recargarUnidadesCarrito().
let unidades = null
const oyentes = new Set()

function publicar(valor) {
  unidades = valor
  oyentes.forEach((oyente) => oyente(valor))
}

/** Vuelve a pedir el carrito y actualiza la cabecera. */
export function recargarUnidadesCarrito() {
  return consultarCarrito()
    .then((c) => publicar(Number(c.unidades)))
    .catch(() => {})
}

/** Quien ya consulto el carrito actualiza la cabecera sin volver a pedirlo. */
export function fijarUnidadesCarrito(valor) {
  publicar(Number(valor))
}

/** Al cerrar sesion: la siguiente sesion parte de cero. */
export function olvidarUnidadesCarrito() {
  publicar(null)
}

/** Unidades del carrito del comprador, o null si aun no se saben o no aplica. */
export function useUnidadesCarrito(activo) {
  const [valor, setValor] = useState(unidades)
  useEffect(() => {
    if (!activo) return undefined
    oyentes.add(setValor)
    if (unidades === null) recargarUnidadesCarrito()
    else setValor(unidades)
    return () => oyentes.delete(setValor)
  }, [activo])
  return activo ? valor : null
}
