import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import userEvent from '@testing-library/user-event'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { PUBLICAS, SESION_ACOPIO, SESION_PRODUCTOR, servidor } from '../../test/servidor.js'

const zonas = [{ id: 2, ubigeo: '150101', departamento: 'Lima', provincia: 'Lima', distrito: 'Lima', activa: true, actualizadoEn: null }, { id: 3, ubigeo: '150102', departamento: 'Lima', provincia: 'Lima', distrito: 'Ancón', activa: false, actualizadoEn: null }]
const cuerpo = [{ puntoId: 7, punto: 'Punto Centro', zonas }, { puntoId: 8, punto: 'Punto Norte', zonas: [] }]
const sesion = (s = SESION_ACOPIO) => ({ ...PUBLICAS, 'GET /api/auth/me': [200, s], 'GET /api/acopio/coberturas': [200, cuerpo] })
const abrir = () => { window.history.replaceState(null, '', '/acopio/cobertura'); render(<App />) }

afterEach(async () => { await i18n.changeLanguage('es') })
describe('Cobertura del punto (CCPIP-401)', () => {
  it('muestra los puntos y zonas declaradas, incluidas inactivas, con navegación readonly', async () => {
    const fetch = servidor(sesion()); abrir()
    expect(await screen.findByRole('heading', { name: 'Cobertura de entrega' })).toBeInTheDocument()
    expect(await screen.findByText('Punto Centro')).toBeInTheDocument()
    expect(screen.getByText('150101')).toBeInTheDocument(); expect(screen.getByText('Activa')).toBeInTheDocument()
    expect(screen.getByText('150102')).toBeInTheDocument(); expect(screen.getByText('Inactiva')).toBeInTheDocument()
    expect(screen.getByText('Punto Norte')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Cobertura' })).toHaveAttribute('aria-current', 'page')
    expect(fetch.mock.calls.filter(([, o]) => o?.method && o.method !== 'GET')).toHaveLength(0)
  })
  it('maneja zona vacía, falta de asignación 409 y retry de errores de servicio', async () => {
    let n = 0
    const fetch = servidor({ ...sesion(), 'GET /api/acopio/coberturas': () => ++n === 1 ? [503, {}] : [200, []] })
    const user = userEvent.setup(); abrir()
    expect(await screen.findByText('No pudimos cargar la cobertura')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('No hay puntos de acopio asignados a tu cuenta.')).toBeInTheDocument()
    expect(fetch.mock.calls.filter(([u]) => u === '/api/acopio/coberturas')).toHaveLength(2)
  })
  it('muestra el estado sin zonas para un punto propio', async () => {
    servidor({ ...sesion(), 'GET /api/acopio/coberturas': [200, [{ puntoId: 8, punto: 'Punto Norte', zonas: [] }]] }); abrir()
    expect(await screen.findByText('Este punto aún no tiene zonas de entrega declaradas.')).toBeInTheDocument()
  })
  it('protege la ruta para ADMIN_PUNTO y traduce al inglés', async () => {
    await i18n.changeLanguage('en'); servidor(sesion()); abrir()
    expect(await screen.findByRole('heading', { name: 'Delivery coverage' })).toBeInTheDocument()
    expect(await screen.findByText('Active')).toBeInTheDocument(); expect(screen.queryByText('Activa')).not.toBeInTheDocument()
  })
  it('no muestra ni consulta cobertura para otro rol autenticado', async () => {
    const fetch = servidor(sesion(SESION_PRODUCTOR)); abrir()
    await waitFor(() => expect(fetch.mock.calls.some(([u]) => u === '/api/auth/me')).toBe(true))
    await act(async () => { await Promise.all(fetch.mock.results.map((r) => r.value)) })
    expect(screen.queryByRole('heading', { name: 'Cobertura de entrega' })).not.toBeInTheDocument()
    expect(fetch.mock.calls.filter(([u]) => u === '/api/acopio/coberturas')).toHaveLength(0)
  })
  it('distingue el conflicto de falta de asignacion', async () => {
    servidor({ ...sesion(), 'GET /api/acopio/coberturas': [409, { code: 'ACOPIO_SIN_ASIGNAR' }] }); abrir()
    expect(await screen.findByText('No hay puntos de acopio asignados a tu cuenta.')).toBeInTheDocument()
    expect(screen.queryByText('No pudimos cargar la cobertura')).not.toBeInTheDocument()
  })
})
