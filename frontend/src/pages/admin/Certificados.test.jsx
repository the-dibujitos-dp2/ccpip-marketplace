import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { llamadas, PUBLICAS, servidor, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const RUTA = '/api/admin/certificados'
const fila = (extra) => ({
  evidenciaId: 'e1',
  autorizacionId: 41,
  productoId: 9,
  producto: 'Camu camu en polvo 150 g',
  linea: 'Superfrutos',
  productorId: 1,
  organizacion: 'Asoc. Nii Joi',
  titulo: 'Registro sanitario',
  emisor: 'DIGESA',
  vigenteHasta: '2026-11-05',
  diasRestantes: 10,
  estado: 'POR_VENCER',
  renovacionPlazo: null,
  ...extra,
})
const respuesta = (estado, dias, certificados) => ({ dias, estado, contadores: { vencidos: 1, porVencer: 2, vigentes: 5 }, certificados })

const POR_VENCER = respuesta('POR_VENCER', 30, [
  fila(),
  fila({ evidenciaId: 'e2', titulo: 'Orgánico', emisor: null, vigenteHasta: '2026-11-20', diasRestantes: 25, renovacionPlazo: '2026-11-30' }),
])
const VENCIDOS = respuesta('VENCIDO', 30, [fila({ evidenciaId: 'e3', titulo: 'Comercio justo', vigenteHasta: '2026-10-24', diasRestantes: -2, estado: 'VENCIDO' })])

const comoSub = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUB],
    [`GET ${RUTA}?estado=POR_VENCER&dias=30`]: () => [200, POR_VENCER],
    ...rutas,
  })

describe('Vigencia de certificados (CCPIP-184)', () => {
  it('alerta lo vencido y lo que vence pronto, con su producto, organización y renovación', async () => {
    comoSub()
    abrirEn('/admin/certificados')
    expect(await screen.findByRole('heading', { level: 1, name: 'Vigencia de certificados' })).toBeInTheDocument()
    expect(within(await screen.findByRole('region', { name: 'Vencidos' })).getByText('1')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Vencen en 30 días' })).getByText('2')).toBeInTheDocument()

    const tabla = within(screen.getByRole('table', { name: 'Por vencer' }))
    const [, sanitario, organico] = tabla.getAllByRole('row')
    expect(within(sanitario).getByText('Registro sanitario')).toBeInTheDocument()
    expect(within(sanitario).getByText('DIGESA')).toBeInTheDocument()
    expect(within(sanitario).getByText('Camu camu en polvo 150 g')).toBeInTheDocument()
    expect(within(sanitario).getByText('Asoc. Nii Joi')).toBeInTheDocument()
    expect(within(sanitario).getByText('05/11/2026')).toBeInTheDocument()
    expect(within(sanitario).getByText('Vence en 10 días')).toBeInTheDocument()
    expect(within(sanitario).getByText('Sin pedir')).toBeInTheDocument()
    expect(within(organico).getByText('Pedida, plazo 30/11/2026')).toBeInTheDocument()
    expect(within(sanitario).getByRole('link', { name: 'Ver la solicitud de Camu camu en polvo 150 g (Registro sanitario)' }))
      .toHaveAttribute('href', '/admin/autorizaciones/41')
    expect(screen.getByText(/nunca la bloquea para siempre/)).toBeInTheDocument()

    const nav = within(screen.getByRole('navigation', { name: 'Sub administrador' }))
    expect(nav.getByRole('link', { name: 'Vigencia de certificados' })).toHaveAttribute('aria-current', 'page')
  })

  it('las pestañas y el horizonte consultan lo pedido', async () => {
    const fetchMock = comoSub({
      [`GET ${RUTA}?estado=VENCIDO&dias=30`]: () => [200, VENCIDOS],
      [`GET ${RUTA}?estado=VENCIDO&dias=90`]: () => [200, respuesta('VENCIDO', 90, [])],
    })
    const user = userEvent.setup()
    abrirEn('/admin/certificados')
    await screen.findByRole('table', { name: 'Por vencer' })

    await user.click(screen.getByRole('tab', { name: 'Vencidos' }))
    const tabla = within(await screen.findByRole('table', { name: 'Vencidos' }))
    expect(tabla.getByText('Venció hace 2 días')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Por vencer en'), '90')
    expect(await screen.findByText('No hay certificados vencidos.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${RUTA}?estado=VENCIDO&dias=90`)).toHaveLength(1)
  })

  it('si la consulta falla lo dice', async () => {
    comoSub({ [`GET ${RUTA}?estado=POR_VENCER&dias=30`]: [500, {}] })
    abrirEn('/admin/certificados')
    expect(await screen.findByText('No pudimos cargar los certificados.')).toBeInTheDocument()
  })

  it('el superadministrador no entra a la vigencia de certificados', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_SUPER], 'GET /api/admin/tablero': [200, {}] })
    abrirEn('/admin/certificados')
    expect(await screen.findByRole('navigation', { name: /administrador/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1, name: 'Vigencia de certificados' })).not.toBeInTheDocument()
  })
})
