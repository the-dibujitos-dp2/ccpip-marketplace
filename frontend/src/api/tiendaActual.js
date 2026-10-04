// Nombre de la tienda del productor para la barra lateral y las migas (PW).
import { useEffect, useState } from 'react'
import { obtenerTienda } from './productor.js'

let cache = null

/** Llamar despues de guardar la tienda para que la barra lateral muestre el nombre nuevo. */
export function invalidarTiendaActual() {
  cache = null
}

export function useTiendaActual() {
  const [tienda, setTienda] = useState(cache)
  useEffect(() => {
    if (cache) return
    obtenerTienda()
      .then((t) => {
        cache = t
        setTienda(t)
      })
      .catch(() => {})
  }, [])
  return tienda
}
