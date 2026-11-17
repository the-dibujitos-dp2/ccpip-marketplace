// CCPIP-86: la seccion Fidelizacion del sub administrador. El servidor valida el rol y las reglas de cada campana.
import { apiFetch, idRuta } from './http.js'

const BASE = '/api/admin/fidelizacion'

/** La regla base, lo acumulado por los compradores y todas las campanas con su resumen. */
export const obtenerFidelizacion = () => apiFetch(BASE)

/** Programa una campana: { nombre, descripcion, desde, hasta, puntos, cadaSoles, montoMinimo, topePuntos }. */
export const crearCampana = (campana) => apiFetch(`${BASE}/campanas`, { method: 'POST', body: campana })

/** Cambia una campana que aun no empieza. */
export const editarCampana = (id, campana) => apiFetch(`${BASE}/campanas/${idRuta(id)}`, { method: 'PUT', body: campana })

/** Finaliza una campana activa desde ahora, o cancela una programada. */
export const finalizarCampana = (id) => apiFetch(`${BASE}/campanas/${idRuta(id)}/finalizar`, { method: 'POST' })

/** Pedidos, ventas y puntos de una campana frente al periodo anterior de igual duracion. */
export const metricasCampana = (id) => apiFetch(`${BASE}/campanas/${idRuta(id)}/metricas`)
