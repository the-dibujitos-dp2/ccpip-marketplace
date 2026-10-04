// Catalogo publico (CW-01 a CW-04): no requiere sesion.
import { apiFetch } from './http.js'

const publico = { sessionRequired: false }

/** Filtros de CW-02. Las listas (linea, region) se envian repetidas: ?linea=A&linea=B */
export function buscarProductos({ q, lineas = [], regiones = [], modalidad, tienda, precioMin, precioMax, disponibles = true, orden, pagina = 0, tamano = 12 } = {}) {
  const p = new URLSearchParams()
  if (q?.trim()) p.set('q', q.trim())
  lineas.forEach((l) => p.append('linea', l))
  regiones.forEach((r) => p.append('region', r))
  if (modalidad) p.set('modalidad', modalidad)
  if (tienda) p.set('tienda', String(tienda))
  if (precioMin !== undefined && precioMin !== '') p.set('precioMin', String(precioMin))
  if (precioMax !== undefined && precioMax !== '') p.set('precioMax', String(precioMax))
  if (!disponibles) p.set('disponibles', 'false')
  if (orden) p.set('orden', orden)
  p.set('pagina', String(pagina))
  p.set('tamano', String(tamano))
  return apiFetch(`/api/productos?${p}`, publico)
}

export const obtenerProducto = (id) => apiFetch(`/api/productos/${id}`, publico)
export const obtenerInicio = () => apiFetch('/api/inicio', publico)
export const listarTiendas = () => apiFetch('/api/tiendas', publico)
export const obtenerTienda = (id) => apiFetch(`/api/tiendas/${id}`, publico)
export const obtenerCatalogos = () => apiFetch('/api/catalogos', publico)
