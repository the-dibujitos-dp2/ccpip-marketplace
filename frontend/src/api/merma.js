// Merma (CCPIP-75, CCPIP-76; Figma A-04 y A-07) y catalogo de causas (CCPIP-77, 298, 299 y 300).
// El servidor limita al punto a sus lotes y al productor a su almacen (RNF-05).
import { apiFetch, idRuta } from './http.js'

/** Las causas activas que ofrece "Marcar como merma": [{ id, nombre, nombreEn, requiereDetalle }]. */
export const listarCausas = () => apiFetch('/api/merma/causas')

// CCPIP-422: los filtros vacios no se envian
const pagina = (base, n = 0, filtros = {}) => {
  const q = new URLSearchParams({ pagina: String(n) })
  Object.entries(filtros).forEach(([clave, valor]) => valor && q.set(clave, valor))
  return apiFetch(`${base}?${q}`)
}

/**
 * La merma de los puntos del administrador, manual y automatica, la mas reciente primero. CCPIP-422:
 * `filtros` { causa, producto, productor, desde, hasta }, todos opcionales y combinables.
 */
export const listarMermaPunto = (n, filtros) => pagina('/api/acopio/merma', n, filtros)

/**
 * `datos`: { loteId, cantidad, motivoId, detalle, clave, confirmada }. Sin `confirmada` el servidor
 * no marca nada; con la misma `clave` no descuenta dos veces.
 */
export const marcarMermaPunto = (datos) => apiFetch('/api/acopio/merma', { method: 'POST', body: datos })

/** La merma de los lotes del productor, en su almacen y en los puntos. */
export const listarMermaProductor = (n) => pagina('/api/productor/merma', n)

export const marcarMermaAlmacen = (datos) => apiFetch('/api/productor/merma', { method: 'POST', body: datos })

// ---------- Catalogo (superadministrador) ----------

const catalogo = '/api/admin/motivos-merma'

export const listarMotivos = () => apiFetch(catalogo)

/** `datos`: { nombre, nombreEn, requiereDetalle }. */
export const crearMotivo = (datos) => apiFetch(catalogo, { method: 'POST', body: datos })

export const modificarMotivo = (id, datos) => apiFetch(`${catalogo}/${idRuta(id)}`, { method: 'PUT', body: datos })

export const desactivarMotivo = (id) => apiFetch(`${catalogo}/${idRuta(id)}/desactivar`, { method: 'POST' })

export const activarMotivo = (id) => apiFetch(`${catalogo}/${idRuta(id)}/activar`, { method: 'POST' })
