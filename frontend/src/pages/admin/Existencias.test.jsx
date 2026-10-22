import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, PUBLICAS, servidor, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const EXISTENCIAS = {
  fecha: '2026-11-18',
  totales: {
    puntos: 2, puntosConExistencias: 1, productos: 3, lotes: 4, lotesVencidos: 1,
    cantidades: [{ unidad: 'KG', nombreUnidad: 'Kilogramo', vigente: 120.5, vencido: 4 }],
  },
  puntos: [
    {
      id: 1, nombre: 'Centro Nacional Lima', departamento: 'Lima', provincia: 'Lima', productos: 3, productores: 2, lotes: 4, lotesVencidos: 1,
      cantidades: [
        { unidad: 'KG', nombreUnidad: 'Kilogramo', vigente: 120.5, vencido: 4 },
        { unidad: 'UNIDAD', nombreUnidad: 'Unidad', vigente: 30, vencido: 0 },
      ],
    },
    { id: 3, nombre: 'Centro Regional Cusco', departamento: 'Cusco', provincia: 'Cusco', productos: 0, productores: 0, lotes: 0, lotesVencidos: 0, cantidades: [] },
  ],
  lineas: [
    { codigo: 'CAFE', nombre: 'Café', productos: 2, puntos: 1, cantidades: [{ unidad: 'KG', nombreUnidad: 'Kilogramo', vigente: 120.5, vencido: 4 }] },
  ],
}

function comoSuper(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUPER],
    'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
    'GET /api/admin/reportes/existencias': () => [200, copia(EXISTENCIAS)],
    ...rutas,
  })
}

describe('CCPIP-354 Existencias agregadas de los puntos de acopio', () => {
  it('se llega desde el menu y muestra los totales, cada punto y cada linea por unidad', async () => {
    comoSuper()
    const user = userEvent.setup()
    abrirEn('/admin')
    await user.click(await screen.findByRole('link', { name: 'Existencias' }))
    expect(window.location.pathname).toBe('/admin/existencias')

    expect(await screen.findByRole('region', { name: 'Puntos con existencias' })).toHaveTextContent('1 / 2')
    expect(screen.getByRole('region', { name: 'Productos en acopio' })).toHaveTextContent('En 4 lotes')
    expect(screen.getByRole('region', { name: 'Lotes vencidos' })).toHaveTextContent('1')

    const puntos = screen.getByRole('table', { name: 'Por punto de acopio' })
    const [, lima, cusco] = within(puntos).getAllByRole('row')
    expect(within(lima).getByText('Centro Nacional Lima')).toBeInTheDocument()
    expect(within(lima).getByText('120.5 Kilogramo')).toBeInTheDocument()
    expect(within(lima).getByText('30 Unidad')).toBeInTheDocument()
    expect(within(lima).getByText('4 Kilogramo')).toBeInTheDocument()
    // Un punto sin existencias tambien aparece
    expect(within(cusco).getByText('Centro Regional Cusco')).toBeInTheDocument()
    expect(within(cusco).getAllByText('—')).toHaveLength(2)

    const lineas = screen.getByRole('table', { name: 'Por línea de producto' })
    expect(within(lineas).getByText('Café')).toBeInTheDocument()
  })

  it('es solo consulta: no ofrece acciones sobre el stock', async () => {
    comoSuper()
    abrirEn('/admin/existencias')
    await screen.findByRole('table', { name: 'Por punto de acopio' })
    expect(screen.queryByRole('button', { name: /editar|ajustar|registrar/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /inventario/i })).not.toBeInTheDocument()
  })

  it('si falla la consulta lo dice', async () => {
    comoSuper({ 'GET /api/admin/reportes/existencias': () => [500, {}] })
    abrirEn('/admin/existencias')
    expect(await screen.findByText('No pudimos cargar las existencias')).toBeInTheDocument()
  })

  it('el sub administrador no la ve', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_SUB], 'GET /api/admin/autorizaciones': () => [200, { contadores: {}, solicitudes: [] }] })
    abrirEn('/admin/existencias')
    await screen.findByRole('navigation', { name: 'Sub administrador' })
    expect(window.location.pathname).toBe('/admin/autorizaciones')
  })
})
