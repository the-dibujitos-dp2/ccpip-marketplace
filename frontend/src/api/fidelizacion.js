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

// --- CCPIP-88/89: codigos de campana, niveles y cupones (sub administrador) ---

/** Los codigos de descuento de una campana, con sus usos. */
export const codigosCampana = (id) => apiFetch(`${BASE}/campanas/${idRuta(id)}/codigos`)

/** Crea un codigo: { codigo, beneficio: { tipo, valor, montoMinimo, topeDescuento }, usosMaximos, usosPorCliente }. */
export const crearCodigo = (id, codigo) => apiFetch(`${BASE}/campanas/${idRuta(id)}/codigos`, { method: 'POST', body: codigo })

/** Desactiva un codigo desde ahora. */
export const desactivarCodigo = (id, codigoId) =>
  apiFetch(`${BASE}/campanas/${idRuta(id)}/codigos/${idRuta(codigoId)}/desactivar`, { method: 'POST' })

/** Niveles por recurrencia y catalogo de cupones. */
export const obtenerBeneficios = () => apiFetch(`${BASE}/beneficios`)

export const crearNivel = (nivel) => apiFetch(`${BASE}/niveles`, { method: 'POST', body: nivel })
export const editarNivel = (id, nivel) => apiFetch(`${BASE}/niveles/${idRuta(id)}`, { method: 'PUT', body: nivel })
export const retirarNivel = (id) => apiFetch(`${BASE}/niveles/${idRuta(id)}`, { method: 'DELETE' })

export const crearCupon = (cupon) => apiFetch(`${BASE}/cupones`, { method: 'POST', body: cupon })
export const editarCupon = (id, cupon) => apiFetch(`${BASE}/cupones/${idRuta(id)}`, { method: 'PUT', body: cupon })
export const activarCupon = (id, activo) => apiFetch(`${BASE}/cupones/${idRuta(id)}/activo`, { method: 'PUT', body: { activo } })

// --- El comprador (CCPIP-173, 374, 88, 89) ---

/** Saldo, nivel, movimientos, cupones para canjear y los ya canjeados. */
export const misPuntos = () => apiFetch('/api/puntos')

/** Canjea puntos por un cupon del catalogo. */
export const canjearCupon = (id) => apiFetch(`/api/puntos/cupones/${idRuta(id)}/canjear`, { method: 'POST' })

/** Cuanto descuenta un cupon ({ cuponId }) o un codigo ({ codigo }) sobre el carrito actual. */
export const previsualizarDescuento = (solicitud) => apiFetch('/api/compra/descuento', { method: 'POST', body: solicitud })

/** Cuantos puntos ganaria con una compra de `monto` soles en productos, con su nivel y la campana de hoy. */
export const estimacionPuntos = (monto) => apiFetch(`/api/puntos/estimacion?monto=${encodeURIComponent(monto)}`)
