import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const [primero, segundo] = DATOS.inicio.destacados

const TREINTA_DIAS = {
  periodo: 'ULTIMOS_30_DIAS',
  desde: '2026-09-26',
  hasta: '2026-10-25',
  productos: [copia(segundo), copia(primero)],
}

function visitante(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': SIN_SESION,
    'GET /api/mas-vendidos?periodo=ULTIMOS_30_DIAS': () => [200, copia(TREINTA_DIAS)],
    ...rutas,
  })
}

describe('CCPIP-339 Más vendidos en el inicio', () => {
  afterEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('la pestaña muestra los más vendidos de los últimos 30 días en orden, con su productor y sin cifras', async () => {
    const fetchMock = visitante()
    const user = userEvent.setup()
    abrirEn('/')
    await user.click(await screen.findByRole('tab', { name: 'Más vendidos' }))

    const lista = await screen.findByRole('list', { name: 'Más vendidos' })
    const items = within(lista).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText('N.º 1')).toBeInTheDocument()
    expect(within(items[0]).getByText(segundo.nombre)).toBeInTheDocument()
    expect(within(items[0]).getByText(new RegExp(segundo.tienda.nombre))).toBeInTheDocument()
    expect(within(items[1]).getByText('N.º 2')).toBeInTheDocument()
    expect(within(items[1]).getByText(primero.nombre)).toBeInTheDocument()
    expect(screen.getByLabelText('Período')).toHaveValue('ULTIMOS_30_DIAS')
    // Siempre hay un periodo elegido: el selector no ofrece una opcion vacia
    expect(within(screen.getByLabelText('Período')).getAllByRole('option')).toHaveLength(3)
    expect(screen.getByText(/compras pagadas y no canceladas/)).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/mas-vendidos?periodo=ULTIMOS_30_DIAS')).toHaveLength(1)
  })

  it('cambiar el período consulta ese período; sin ventas lo dice en vez de rellenar', async () => {
    const fetchMock = visitante({
      'GET /api/mas-vendidos?periodo=ESTE_ANIO': () => [200, { ...copia(TREINTA_DIAS), periodo: 'ESTE_ANIO', productos: [] }],
    })
    const user = userEvent.setup()
    abrirEn('/')
    await user.click(await screen.findByRole('tab', { name: 'Más vendidos' }))
    await screen.findByRole('list', { name: 'Más vendidos' })

    await user.selectOptions(screen.getByLabelText('Período'), 'ESTE_ANIO')
    expect(await screen.findByText('Aún no hay ventas en este período. Prueba con uno más largo.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Más vendidos' })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/mas-vendidos?periodo=ESTE_ANIO')).toHaveLength(1)
  })

  it('las otras pestañas no consultan los más vendidos y un error se avisa', async () => {
    const fetchMock = visitante({ 'GET /api/mas-vendidos?periodo=ULTIMOS_30_DIAS': () => [500, {}] })
    const user = userEvent.setup()
    abrirEn('/')
    await screen.findByRole('tab', { name: 'Destacados', selected: true })
    expect(llamadas(fetchMock, 'GET /api/mas-vendidos?periodo=ULTIMOS_30_DIAS')).toHaveLength(0)
    await user.click(screen.getByRole('tab', { name: 'Más vendidos' }))
    expect(await screen.findByText(/No pudimos cargar/)).toBeInTheDocument()
  })

  it('sale en inglés', async () => {
    await i18n.changeLanguage('en')
    visitante()
    const user = userEvent.setup()
    abrirEn('/')
    await user.click(await screen.findByRole('tab', { name: 'Best sellers' }))
    expect(await screen.findByText('No. 1')).toBeInTheDocument()
    expect(screen.getByLabelText('Period')).toHaveValue('ULTIMOS_30_DIAS')
    expect(screen.getByRole('option', { name: 'Last 3 months' })).toBeInTheDocument()
  })
})
