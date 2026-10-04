// Catalogo publico (CCPIP-52, CCPIP-53, CCPIP-152): no requiere sesion.
import { apiFetch } from './http.js'

export function buscarProductos({ q = '', pagina = 0, tamano = 12 } = {}) {
  const params = new URLSearchParams({ pagina: String(pagina), tamano: String(tamano) })
  if (q.trim()) params.set('q', q.trim())
  return apiFetch(`/api/productos?${params}`, { sessionRequired: false })
}

export function obtenerProducto(id) {
  return apiFetch(`/api/productos/${id}`, { sessionRequired: false })
}
