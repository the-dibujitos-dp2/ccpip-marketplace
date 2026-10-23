// API de la administracion de la Camara. El servidor valida el rol (CCPIP-111, RNF-04).
import { apiFetch, idRuta } from './http.js'

/** CCPIP-111 (SA-01): ventas, comision, productores, lineas y puntos de acopio del mes en curso. */
export const obtenerTablero = () => apiFetch('/api/admin/tablero')

/** CCPIP-28: permisos declarados por rol, derivados de los accesos del servidor. Solo lectura. */
export const consultarPermisos = () => apiFetch('/api/admin/permisos')

/** SA-05 (CCPIP-345): una metrica acumulada por periodo; sin filtro, el año en curso por mes. */
export function consultarReporte(metrica, { desde, hasta, agrupacion } = {}) {
  const q = new URLSearchParams()
  if (desde) q.set('desde', desde)
  if (hasta) q.set('hasta', hasta)
  if (agrupacion) q.set('agrupacion', agrupacion)
  const consulta = q.toString()
  return apiFetch(`/api/admin/reportes/${encodeURIComponent(metrica)}${consulta ? `?${consulta}` : ''}`)
}

/** CCPIP-354 (RF-244): existencias agregadas de los puntos de acopio. Solo superadministrador, solo lectura. */
export const existenciasAgregadas = () => apiFetch('/api/admin/reportes/existencias')

/** CCPIP-90 (SA-02): comision vigente, cambio programado e historial. Solo superadministrador. */
export const obtenerComision = () => apiFetch('/api/admin/comision')

/** CCPIP-90: { porcentaje, vigenteDesde: 'YYYY-MM-DD', motivo }. Devuelve el estado actualizado. */
export const programarComision = (cambio) => apiFetch('/api/admin/comision', { method: 'POST', body: cambio })

/** CCPIP-90: cancela un cambio que aun no entra en vigor. */
export const cancelarComision = (id) => apiFetch(`/api/admin/comision/${idRuta(id)}/cancelar`, { method: 'POST' })

/** CCPIP-91: pedidos confirmados con su comision y neto. Sin fechas, el mes en curso. */
export function comisionesPorPedido({ desde, hasta } = {}) {
  const q = new URLSearchParams()
  if (desde) q.set('desde', desde)
  if (hasta) q.set('hasta', hasta)
  const consulta = q.toString()
  return apiFetch(`/api/admin/comision/pedidos${consulta ? `?${consulta}` : ''}`)
}

/** CCPIP-142 (SA-02): cuantas horas se guarda el carrito desde su ultimo cambio. Solo superadministrador. */
export const obtenerVigenciaCarrito = () => apiFetch('/api/admin/parametros/vigencia-carrito')

/** Rige de inmediato, tambien para los carritos ya guardados. Horas enteras de 1 a 720. */
export const configurarVigenciaCarrito = (horas) => apiFetch('/api/admin/parametros/vigencia-carrito', { method: 'PUT', body: { horas } })
/** Query string con los filtros que tienen valor ('' si no hay ninguno). */
function consulta(filtros) {
  const q = new URLSearchParams()
  Object.entries(filtros).forEach(([clave, valor]) => valor && q.set(clave, valor))
  const texto = q.toString()
  return texto ? `?${texto}` : ''
}

/**
 * Catalogo maestro (CCPIP-96, 309-314, 351): lineas o productos de referencia. Solo superadministrador.
 * listar({ estado: 'TODOS'|'ACTIVOS'|'INACTIVOS', q, linea }) · crear(datos) · modificar(id, datos) ·
 * cambiarEstado(id, activar): desactivar no borra, se conservan las ofertas y el historial.
 */
function recursoCatalogo(recurso) {
  const base = `/api/admin/catalogo/${recurso}`
  return {
    listar: (filtros = {}) => apiFetch(`${base}${consulta(filtros)}`),
    crear: (datos) => apiFetch(base, { method: 'POST', body: datos }),
    modificar: (id, datos) => apiFetch(`${base}/${idRuta(id)}`, { method: 'PUT', body: datos }),
    cambiarEstado: (id, activar) => apiFetch(`${base}/${idRuta(id)}/${activar ? 'reactivar' : 'desactivar'}`, { method: 'POST' }),
  }
}

export const lineasMaestras = recursoCatalogo('lineas')
export const productosMaestros = recursoCatalogo('productos')

/**
 * CCPIP-95, 124, 125: cuentas segun el alcance del rol. { q, rol, estado, pagina } opcionales.
 * Devuelve { items, pagina, tamano, total, totalPaginas, roles } (roles: los que se pueden consultar).
 */
export function buscarCuentas({ q, rol, estado, pagina } = {}) {
  const p = new URLSearchParams()
  if (q) p.set('q', q)
  if (rol) p.set('rol', rol)
  if (estado) p.set('estado', estado)
  if (pagina) p.set('pagina', String(pagina))
  const consulta = p.toString()
  return apiFetch(`/api/admin/cuentas${consulta ? `?${consulta}` : ''}`)
}

/** Detalle, puntos, acciones permitidas e historial de una cuenta. */
export const obtenerCuenta = (id) => apiFetch(`/api/admin/cuentas/${idRuta(id)}`)

/** CCPIP-304, 305: puntos de acopio activos que se pueden asignar. Solo superadministrador. */
export const puntosAsignables = () => apiFetch('/api/admin/cuentas/puntos')

/** CCPIP-301, 304: { nombre, correo, telefono, rol, puntos }. Devuelve { cuenta, avisoEnviado }. */
export const crearCuenta = (cuenta) => apiFetch('/api/admin/cuentas', { method: 'POST', body: cuenta })

/** CCPIP-302, 305: { nombre, telefono, puntos }. El correo y el rol no cambian. */
export const modificarCuenta = (id, datos) => apiFetch(`/api/admin/cuentas/${idRuta(id)}`, { method: 'PUT', body: datos })

/** CCPIP-303, 306, 307, 308: accion = suspender | reactivar | desactivar, siempre con motivo. */
export const cambiarEstadoCuenta = (id, accion, motivo) =>
  apiFetch(`/api/admin/cuentas/${idRuta(id)}/${accion}`, { method: 'POST', body: { motivo } })

/** Nuevo enlace de bienvenida si la persona aun no eligio su contrasena. */
export const reenviarAcceso = (id) => apiFetch(`/api/admin/cuentas/${idRuta(id)}/reenviar-acceso`, { method: 'POST' })
