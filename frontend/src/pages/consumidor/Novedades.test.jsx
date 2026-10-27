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
  desde: '2026-09-27',
  hasta: '2026-10-26',
  productos: [copia(segundo), copia(primero)],
}

function visitante(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': SIN_SESION,
    'GET /api/novedades?periodo=ULTIMOS_30_DIAS': () => [200, copia(TREINTA_DIAS)],
    ...rutas,
  })
}

describe('CCPIP-338 Publicados recientemente en el inicio', () => {
  afterEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('la pestaña muestra lo publicado en los últimos 30 días con su productor, sin puesto', async () => {
    const fetchMock = visitante()
    const user = userEvent.setup()
    abrirEn('/')
    expect(llamadas(fetchMock, 'GET /api/novedades?periodo=ULTIMOS_30_DIAS')).toHaveLength(0)
    await user.click(await screen.findByRole('tab', { name: 'Novedades' }))

    const lista = await screen.findByRole('list', { name: 'Novedades' })
    const items = within(lista).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByText(segundo.nombre)).toBeInTheDocument()
    expect(within(items[0]).getByText(new RegExp(segundo.tienda.nombre))).toBeInTheDocument()
    expect(within(items[1]).getByText(primero.nombre)).toBeInTheDocument()
    // No es un ranking: no lleva puesto
    expect(screen.queryByText('N.º 1')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Período')).toHaveValue('ULTIMOS_30_DIAS')
    expect(screen.getByText(/según la fecha en que se publicaron/)).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/novedades?periodo=ULTIMOS_30_DIAS')).toHaveLength(1)
  })

  it('cambiar el período consulta ese período; sin publicaciones lo dice en vez de rellenar', async () => {
    const fetchMock = visitante({
      'GET /api/novedades?periodo=ULTIMOS_3_MESES': () => [200, { ...copia(TREINTA_DIAS), periodo: 'ULTIMOS_3_MESES', productos: [] }],
    })
    const user = userEvent.setup()
    abrirEn('/')
    await user.click(await screen.findByRole('tab', { name: 'Novedades' }))
    await screen.findByRole('list', { name: 'Novedades' })

    await user.selectOptions(screen.getByLabelText('Período'), 'ULTIMOS_3_MESES')
    expect(await screen.findByText('No se publicaron productos en este período. Prueba con uno más largo.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Novedades' })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/novedades?periodo=ULTIMOS_3_MESES')).toHaveLength(1)
  })

  it('un error al consultar se avisa', async () => {
    visitante({ 'GET /api/novedades?periodo=ULTIMOS_30_DIAS': () => [500, {}] })
    const user = userEvent.setup()
    abrirEn('/')
    await user.click(await screen.findByRole('tab', { name: 'Novedades' }))
    expect(await screen.findByText(/No pudimos cargar/)).toBeInTheDocument()
  })

  it('sale en inglés', async () => {
    await i18n.changeLanguage('en')
    visitante()
    const user = userEvent.setup()
    abrirEn('/')
    await user.click(await screen.findByRole('tab', { name: 'New arrivals' }))
    expect(await screen.findByRole('list', { name: 'New arrivals' })).toBeInTheDocument()
    expect(screen.getByText('Products on sale by the date they were published, newest first.')).toBeInTheDocument()
  })
})
