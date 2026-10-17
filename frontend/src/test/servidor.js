import { vi } from 'vitest'
// Respuestas reales del backend (stack de e2e), para que las simulaciones no se desvien del contrato
import catalogos from './fixtures/catalogos.json'
import inicio from './fixtures/inicio.json'
import buscar from './fixtures/buscar.json'
import carrito from './fixtures/carrito.json'
import detalle from './fixtures/productos-1.json'
import tiendas from './fixtures/tiendas.json'
import tienda from './fixtures/tiendas-2.json'
import panelProductor from './fixtures/productor-panel.json'
import tiendaProductor from './fixtures/productor-tienda.json'
import productosProductor from './fixtures/productor-productos-estado-PUBLICADO.json'
import edicion from './fixtures/productor-productos-9.json'
import lotes from './fixtures/productor-productos-9-lotes.json'
import ubicaciones from './fixtures/productor-ubicaciones.json'
import movimientos from './fixtures/acopio-movimientos.json'
import pedidos from './fixtures/acopio-pedidos.json'
import pedidoListo from './fixtures/acopio-pedido-listo.json'
import pedidoEntregado from './fixtures/acopio-pedido-entregado.json'
// CCPIP-111: armado segun TableroModelos (los registros del backend), con lineas y puntos del seed
import tableroAdmin from './fixtures/admin-tablero.json'
// CCPIP-90 y CCPIP-91: armados segun ComisionModelos.Estado y ComisionesPorPedido del backend
import comisionAdmin from './fixtures/admin-comision.json'
import comisionesAdmin from './fixtures/admin-comisiones.json'
// CCPIP-112: armado segun ResumenPedido del backend
import misPedidos from './fixtures/mis-pedidos.json'
// CCPIP-36, 50, 174 y 284: armados segun AutorizacionModelos del backend
import autorizacionesAdmin from './fixtures/admin-autorizaciones.json'
import expedienteAdmin from './fixtures/admin-autorizacion-41.json'
import analisisSimulado from './fixtures/admin-analisis-simulado.json'
import evidenciasProductor from './fixtures/productor-evidencias-9.json'

export const USUARIO = {
  id: 1,
  email: 'consumidor@ccpip.test',
  fullName: 'Consumidor de Prueba',
  role: 'CONSUMIDOR',
  roleName: 'Consumidor final',
}
export const SESION = { user: USUARIO, expiresInSeconds: 120 }
export const SESION_ACOPIO = {
  user: { id: 4, email: 'admin_acopio@ccpip.test', fullName: 'Admin Acopio', role: 'ADMIN_PUNTO', roleName: 'Administrador de punto de acopio' },
  expiresInSeconds: 120,
}
export const SESION_PRODUCTOR = {
  user: { id: 2, email: 'productor@ccpip.test', fullName: 'Comunidad Shipibo Konibo', role: 'PRODUCTOR', roleName: 'Productor' },
  expiresInSeconds: 120,
}
export const SESION_SUPER = {
  user: { id: 8, email: 'sudo@ccpip.test', fullName: 'Ana Shuñaqui', role: 'SUPER_ADMIN', roleName: 'Super administrador' },
  expiresInSeconds: 120,
}
export const SESION_SUB = {
  user: { id: 7, email: 'sub_admin@ccpip.test', fullName: 'Lucía Paredes', role: 'SUB_ADMIN', roleName: 'Sub administrador' },
  expiresInSeconds: 120,
}
export const PANEL = { titulo: 'Tu mercado', descripcion: 'Explora productos con historia.' }
export const SIN_SESION = [401, { code: 'SIN_SESION' }]
export const EXPIRADA = [401, { code: 'SESION_EXPIRADA' }]

/**
 * Simula el backend. `rutas` mapea "METODO /ruta" a [status, cuerpo], a una funcion que lo
 * devuelve (recibe las opciones y la URL; puede ser asincrona, para simular una respuesta lenta), o a un
 * Error para simular una falla de red.
 */
export function servidor(rutas) {
  const fetchMock = vi.fn(async (url, opciones = {}) => {
    const clave = `${opciones.method || 'GET'} ${url}`
    // Si no hay una ruta exacta, vale la misma ruta sin query string
    let respuesta = rutas[clave] ?? rutas[clave.split('?')[0]]
    if (typeof respuesta === 'function') respuesta = await respuesta(opciones, url)
    if (respuesta instanceof Error) throw respuesta
    if (!respuesta) throw new Error(`Ruta no simulada: ${clave}`)
    const [status, cuerpo] = respuesta
    return { status, ok: status >= 200 && status < 300, json: async () => cuerpo ?? {} }
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export function llamadas(fetchMock, clave) {
  return fetchMock.mock.calls.filter(([url, o = {}]) => `${o.method || 'GET'} ${url}` === clave)
}

export const PRODUCTOR = { id: 1, comunidad: 'Comunidad Shipibo Konibo', region: 'Ucayali', origenVerificado: true }

export function producto(id, nombre, extra = {}) {
  return {
    id,
    nombre,
    precio: 48,
    unidad: { codigo: 'ML', nombre: 'Mililitro' },
    linea: { codigo: 'ACEITES', nombre: 'Aceites' },
    productor: PRODUCTOR,
    ...extra,
  }
}

export function pagina(items, { pagina = 0, tamano = 12, total = items.length } = {}) {
  return [200, { items, pagina, tamano, total, totalPaginas: Math.ceil(total / tamano) }]
}

export const DATOS = {
  catalogos,
  carrito,
  inicio,
  buscar,
  detalle,
  tiendas,
  tienda,
  panelProductor,
  tiendaProductor,
  productosProductor,
  edicion,
  lotes,
  ubicaciones,
  movimientos,
  pedidos,
  pedidoListo,
  pedidoEntregado,
  tableroAdmin,
  comisionAdmin,
  comisionesAdmin,
  misPedidos,
  autorizacionesAdmin,
  expedienteAdmin,
  analisisSimulado,
  evidenciasProductor,
}

/** Copia profunda para que cada prueba pueda modificar los datos sin afectar a otras. */
export const copia = (x) => structuredClone(x)

/** Rutas publicas que usan la cabecera y las paginas del consumidor. */
export const PUBLICAS = {
  'GET /api/catalogos': () => [200, copia(catalogos)],
  'GET /api/inicio': () => [200, copia(inicio)],
  'GET /api/productos': () => [200, copia(buscar)],
  'GET /api/tiendas': () => [200, copia(tiendas)],
}
