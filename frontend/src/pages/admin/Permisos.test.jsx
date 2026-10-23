import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, PUBLICAS, servidor, SESION_SUPER } from '../../test/servidor.js'

const PERMISOS = [
  { rol: 'CONSUMIDOR', permisos: [{ metodo: 'GET', ruta: '/api/carrito' }] },
  { rol: 'PRODUCTOR', permisos: [{ metodo: 'POST', ruta: '/api/productor/productos' }] },
  { rol: 'ADMIN_PUNTO', permisos: [{ metodo: 'GET', ruta: '/api/acopio/inventario' }] },
  { rol: 'SUB_ADMIN', permisos: [{ metodo: 'GET', ruta: '/api/admin/cuentas' }] },
  { rol: 'SUPER_ADMIN', permisos: [{ metodo: 'GET', ruta: '/api/admin/tablero' }] },
  { rol: 'DISTRIBUIDOR', permisos: [{ metodo: 'GET', ruta: '/api/distribuidor/entregas' }] },
]

function abrirEn(ruta = '/admin/permisos') {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

function comoSuper(datos = PERMISOS) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUPER],
    'GET /api/admin/permisos': [200, copia(datos)],
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
