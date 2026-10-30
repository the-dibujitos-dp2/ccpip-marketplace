import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, llamadas, PUBLICAS, servidor, SESION_SUPER } from '../../test/servidor.js'

const PERMISOS = [
  { rol: 'CONSUMIDOR', permisos: [{ metodo: 'GET', ruta: '/api/carrito' }] },
  { rol: 'PRODUCTOR', permisos: [{ metodo: 'POST', ruta: '/api/productor/productos' }] },
  { rol: 'ADMIN_PUNTO', permisos: [{ metodo: 'GET', ruta: '/api/acopio/inventario' }] },
  { rol: 'SUB_ADMIN', permisos: [{ metodo: 'GET', ruta: '/api/admin/cuentas' }] },
  { rol: 'SUPER_ADMIN', permisos: [{ metodo: 'GET', ruta: '/api/admin/tablero' }] },
  { rol: 'DISTRIBUIDOR', permisos: [{ metodo: 'GET', ruta: '/api/distribuidor/entregas' }] },
]

const restringidos = ['EDITAR_DATOS_COMPRADORES', 'EDITAR_EXISTENCIAS', 'EDITAR_PEDIDOS', 'EDITAR_PRODUCTOS', 'EDITAR_TIENDAS']
  .map((codigo) => ({ codigo, restringido: true, concedido: false, actualizadoEn: null, actualizadoPor: null }))

const CONFIGURABLES = [
  {
    rol: 'SUB_ADMIN',
    permisos: [
      { codigo: 'DECIDIR_AUTORIZACIONES', restringido: false, concedido: true, actualizadoEn: null, actualizadoPor: null },
      { codigo: 'SUSPENDER_CUENTAS', restringido: false, concedido: true, actualizadoEn: null, actualizadoPor: null },
      ...restringidos,
    ],
  },
  { rol: 'SUPER_ADMIN', permisos: restringidos },
]

const CAMBIO = 'PUT /api/admin/permisos/configurables/SUB_ADMIN/SUSPENDER_CUENTAS'

function abrirEn(ruta = '/admin/permisos') {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

function comoSuper(datos = PERMISOS, rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUPER],
    'GET /api/admin/permisos': [200, copia(datos)],
    'GET /api/admin/permisos/configurables': () => [200, copia(CONFIGURABLES)],
    ...rutas,
  })
}

describe('CCPIP-28 consulta de permisos', () => {
  it('el superadministrador ve los permisos que el servidor asigna por rol', async () => {
    comoSuper()
    abrirEn()

    expect(await screen.findByRole('heading', { level: 1, name: 'Permisos por rol' })).toBeInTheDocument()
    const tabla = within(await screen.findByRole('table', { name: 'Permisos asignados por rol' }))
    expect(tabla.getByText('Productor')).toBeInTheDocument()
    expect(tabla.getByText('POST')).toBeInTheDocument()
    expect(tabla.getByText('/api/productor/productos')).toBeInTheDocument()
    expect(tabla.getByText('Super administrador')).toBeInTheDocument()
    expect(tabla.getByText('/api/admin/tablero')).toBeInTheDocument()
    expect(tabla.queryByRole('button')).not.toBeInTheDocument()
    expect(tabla.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('los otros roles no cargan la matriz de permisos', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, { ...SESION_SUPER, user: { ...SESION_SUPER.user, role: 'SUB_ADMIN' } }],
    })
    abrirEn()
    await screen.findByRole('heading', { level: 1 })
    expect(window.location.pathname).toBe('/admin/autorizaciones')
    expect(fetchMock.mock.calls.some(([url]) => url === '/api/admin/permisos')).toBe(false)
  })
})

describe('CCPIP-296 permisos configurables', () => {
  const listaDe = async (rol) => within(await screen.findByRole('list', { name: `Permisos configurables de ${rol}` }))

  it('muestra cada permiso del rol con su estado y las restricciones de gobernanza sin acción', async () => {
    comoSuper()
    abrirEn()
    const sub = await listaDe('Sub administrador')
    expect(sub.getByText('Suspender cuentas')).toBeInTheDocument()
    expect(sub.getAllByText('Concedido')).toHaveLength(2)
    expect(sub.getByRole('button', { name: 'Revocar Suspender cuentas a Sub administrador' })).toBeInTheDocument()
    expect(sub.getByText('Editar pedidos')).toBeInTheDocument()
    expect(sub.getAllByText('Restringido por gobernanza')).toHaveLength(5)
    expect(sub.queryByRole('button', { name: /Editar pedidos/ })).not.toBeInTheDocument()
    const superAdmin = await listaDe('Super administrador')
    expect(superAdmin.queryByRole('button')).not.toBeInTheDocument()
  })

  it('revoca con motivo y muestra el nuevo estado', async () => {
    const revocado = copia(CONFIGURABLES[0])
    revocado.permisos[1] = { ...revocado.permisos[1], concedido: false, actualizadoEn: '2026-10-29T19:00:00Z', actualizadoPor: 'Super Administrador de Prueba' }
    const fetchMock = comoSuper(PERMISOS, { [CAMBIO]: [200, revocado] })
    const user = userEvent.setup()
    abrirEn()
    await user.click((await listaDe('Sub administrador')).getByRole('button', { name: 'Revocar Suspender cuentas a Sub administrador' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Revocar «Suspender cuentas» a Sub administrador' })).toBeInTheDocument()

    // Sin motivo no se envía
    await user.click(dialogo.getByRole('button', { name: 'Revocar' }))
    expect(dialogo.getByText('Indica el motivo del cambio')).toBeInTheDocument()
    expect(llamadas(fetchMock, CAMBIO)).toHaveLength(0)

    await user.type(dialogo.getByLabelText('Motivo'), 'Solo la presidencia suspende cuentas')
    await user.click(dialogo.getByRole('button', { name: 'Revocar' }))
    expect(await screen.findByText('Revocaste «Suspender cuentas» a Sub administrador')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, CAMBIO)
    expect(JSON.parse(opciones.body)).toEqual({ concedido: false, motivo: 'Solo la presidencia suspende cuentas' })
    const sub = await listaDe('Sub administrador')
    expect(sub.getByText('Revocado')).toBeInTheDocument()
    expect(sub.getByRole('button', { name: 'Conceder Suspender cuentas a Sub administrador' })).toBeInTheDocument()
    expect(sub.getByText(/Último cambio: .*, por Super Administrador de Prueba\./)).toBeInTheDocument()
  })

  it('muestra el rechazo del servidor en el diálogo', async () => {
    comoSuper(PERMISOS, {
      [CAMBIO]: [422, { code: 'RESTRICCION_GOBERNANZA', error: 'La administración no puede editar productos, tiendas, pedidos, datos de compradores ni existencias.' }],
    })
    const user = userEvent.setup()
    abrirEn()
    await user.click((await listaDe('Sub administrador')).getByRole('button', { name: 'Revocar Suspender cuentas a Sub administrador' }))
    const dialogo = within(screen.getByRole('dialog'))
    await user.type(dialogo.getByLabelText('Motivo'), 'Prueba')
    await user.click(dialogo.getByRole('button', { name: 'Revocar' }))
    expect(await dialogo.findByText('No pudimos cambiar el permiso')).toBeInTheDocument()
  })
})
