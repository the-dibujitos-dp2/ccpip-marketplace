import { vi } from 'vitest'

export const USUARIO = {
  id: 1,
  email: 'consumidor@ccpip.test',
  fullName: 'Consumidor de Prueba',
  role: 'CONSUMIDOR',
  roleName: 'Consumidor final',
}
export const SESION = { user: USUARIO, expiresInSeconds: 120 }
export const PANEL = { titulo: 'Tu mercado', descripcion: 'Explora productos con historia.' }
export const SIN_SESION = [401, { code: 'SIN_SESION' }]
export const EXPIRADA = [401, { code: 'SESION_EXPIRADA' }]

/**
 * Simula el backend. `rutas` mapea "METODO /ruta" a [status, cuerpo], a una funcion que lo
 * devuelve, o a un Error para simular una falla de red.
 */
export function servidor(rutas) {
  const fetchMock = vi.fn(async (url, opciones = {}) => {
    const clave = `${opciones.method || 'GET'} ${url}`
    let respuesta = rutas[clave]
    if (typeof respuesta === 'function') respuesta = respuesta(opciones)
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
