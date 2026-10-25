// Merma (CCPIP-75, CCPIP-76; Figma A-04 y A-07) y catalogo de causas (CCPIP-77, 298, 299 y 300).
// El servidor limita al punto a sus lotes y al productor a su almacen (RNF-05).
import { apiFetch, idRuta } from './http.js'
import i18n from '../i18n/index.js'

/** Las causas activas que ofrece "Marcar como merma": [{ id, nombre, nombreEn, requiereDetalle }]. */
export const listarCausas = () => apiFetch('/api/merma/causas')

function rutaReporte(base, sufijo = '', pagina = null, filtros = {}) {
  const p = new URLSearchParams()
  if (pagina != null) p.set('pagina', String(pagina))
  for (const campo of ['desde', 'hasta', 'lote', 'producto', 'ubicacion']) {
    if (filtros[campo]?.trim()) p.set(campo, filtros[campo].trim())
  }
  // Una descarga por enlace no permite añadir Accept-Language; el único idioma admitido viaja explícitamente como `lang`.
  if (sufijo) p.set('lang', i18n.resolvedLanguage === 'en' ? 'en' : 'es')
  return `${base}${sufijo}${p.size ? `?${p}` : ''}`
}

/** La merma de los puntos del administrador, manual y automatica, la mas reciente primero. */
export const listarMermaPunto = (n, filtros) => apiFetch(rutaReporte('/api/acopio/merma', '', n, filtros))
export const urlReporteMermaPunto = (formato, filtros) => rutaReporte('/api/acopio/merma', `/${formato}`, null, filtros)

/**
 * `datos`: { loteId, cantidad, motivoId, detalle, clave, confirmada }. Sin `confirmada` el servidor
 * no marca nada; con la misma `clave` no descuenta dos veces.
 */
export const marcarMermaPunto = (datos) => apiFetch('/api/acopio/merma', { method: 'POST', body: datos })

/** La merma de los lotes del productor, en su almacen y en los puntos. */
export const listarMermaProductor = (n, filtros) => apiFetch(rutaReporte('/api/productor/merma', '', n, filtros))
export const urlReporteMermaProductor = (formato, filtros) => rutaReporte('/api/productor/merma', `/${formato}`, null, filtros)

export const marcarMermaAlmacen = (datos) => apiFetch('/api/productor/merma', { method: 'POST', body: datos })

// ---------- Catalogo (superadministrador) ----------

const catalogo = '/api/admin/motivos-merma'

export const listarMotivos = () => apiFetch(catalogo)

/** `datos`: { nombre, nombreEn, requiereDetalle }. */
export const crearMotivo = (datos) => apiFetch(catalogo, { method: 'POST', body: datos })

export const modificarMotivo = (id, datos) => apiFetch(`${catalogo}/${idRuta(id)}`, { method: 'PUT', body: datos })

export const desactivarMotivo = (id) => apiFetch(`${catalogo}/${idRuta(id)}/desactivar`, { method: 'POST' })

export const activarMotivo = (id) => apiFetch(`${catalogo}/${idRuta(id)}/activar`, { method: 'POST' })
