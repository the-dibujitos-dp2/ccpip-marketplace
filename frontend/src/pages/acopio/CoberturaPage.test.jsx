import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import userEvent from '@testing-library/user-event'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { llamadas, PUBLICAS, SESION_ACOPIO, SESION_PRODUCTOR, servidor } from '../../test/servidor.js'

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

  it('cancelar no modifica y la confirmación nombra la zona', async () => {
    const fetch = servidor({ ...sesion(), 'PATCH /api/acopio/coberturas/2/desactivar': [204] })
    const user = userEvent.setup(); abrir()
    await user.click(await screen.findByRole('button', { name: 'Desactivar zona · Lima · 150101' }))
    const dialog = await screen.findByRole('dialog', { name: 'Desactivar zona de cobertura' })
    expect(dialog).toHaveTextContent('150101')
    await user.keyboard('{Escape}')
    expect(llamadas(fetch, 'PATCH /api/acopio/coberturas/2/desactivar')).toHaveLength(0)
  })
  it('desactiva, recarga y no ofrece control a zonas inactivas', async () => {
    let active = true
    const fetch = servidor({ ...sesion(), 'PATCH /api/acopio/coberturas/2/desactivar': () => { active = false; return [204] }, 'GET /api/acopio/coberturas': () => [200, [{ ...cuerpo[0], zonas: zonas.map(z => z.id === 2 ? { ...z, activa: active } : z) }]] })
    const user = userEvent.setup(); abrir()
    await user.click(await screen.findByRole('button', { name: 'Desactivar zona · Lima · 150101' }))
    await user.click(screen.getByRole('button', { name: 'Desactivar' }))
    expect(await screen.findAllByText('Inactiva')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Desactivar zona · Lima · 150101' })).not.toBeInTheDocument()
    expect(llamadas(fetch, 'GET /api/acopio/coberturas')).toHaveLength(2)
  })
  it('protege repetidos mientras la mutación está pendiente', async () => {
    let resolvePatch
    const fetch = servidor({ ...sesion(), 'PATCH /api/acopio/coberturas/2/desactivar': () => new Promise(resolve => { resolvePatch = () => resolve([204]) }) })
    const user = userEvent.setup(); abrir()
    await user.click(await screen.findByRole('button', { name: 'Desactivar zona · Lima · 150101' }))
    const confirmar = screen.getByRole('button', { name: 'Desactivar' })
    await user.click(confirmar)
    expect(confirmar).toBeDisabled()
    expect(llamadas(fetch, 'PATCH /api/acopio/coberturas/2/desactivar')).toHaveLength(1)
    resolvePatch()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
  it('presenta error sin mensaje de éxito y permite reintentar', async () => {
    const fetch = servidor({ ...sesion(), 'PATCH /api/acopio/coberturas/2/desactivar': [500, {}] })
    const user = userEvent.setup(); abrir()
    await user.click(await screen.findByRole('button', { name: 'Desactivar zona · Lima · 150101' }))
    await user.click(screen.getByRole('button', { name: 'Desactivar' }))
    expect(await screen.findByText('No se pudo desactivar la zona. Inténtalo nuevamente.')).toBeInTheDocument()
    expect(screen.queryByText('Zona desactivada')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Desactivar' })).toBeEnabled()
    expect(llamadas(fetch, 'PATCH /api/acopio/coberturas/2/desactivar')).toHaveLength(1)
  })
  it('localiza dialogo y acciones en inglés', async () => {
    await i18n.changeLanguage('en'); servidor(sesion()); const user = userEvent.setup(); abrir()
    await user.click(await screen.findByRole('button', { name: 'Deactivate zone · Lima · 150101' }))
    expect(await screen.findByRole('dialog', { name: 'Deactivate delivery zone' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Deactivate' })).toBeInTheDocument()
  })
  it('distingue el conflicto de falta de asignacion', async () => {
    servidor({ ...sesion(), 'GET /api/acopio/coberturas': [409, { code: 'ACOPIO_SIN_ASIGNAR' }] }); abrir()
    expect(await screen.findByText('No hay puntos de acopio asignados a tu cuenta.')).toBeInTheDocument()
    expect(screen.queryByText('No pudimos cargar la cobertura')).not.toBeInTheDocument()
  })
})
