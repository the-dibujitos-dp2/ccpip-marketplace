import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { llamadas, PANEL, PUBLICAS, servidor, SESION, SIN_SESION } from '../../test/servidor.js'
import { textoEspera } from './PedidosDisponiblesPage.jsx'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const SESION_DISTRIBUIDOR = {
  user: { id: 8, email: 'distribuidor@ccpip.test', fullName: 'Julio Paredes', role: 'DISTRIBUIDOR', roleName: 'Distribuidor' },
  expiresInSeconds: 120,
}

const LIMA = { id: 1, nombre: 'Centro Nacional Lima', distrito: 'Lima', direccion: 'Sede central CCPIP' }
const CUSCO = { id: 3, nombre: 'Centro Regional Cusco', distrito: 'Cusco', direccion: 'Centro regional andino' }
const destino = (distrito, ubigeo, provincia = 'Lima', departamento = 'Lima') => ({ ubigeo, departamento, provincia, distrito })

// El que lleva mas esperando primero, como lo entrega el servidor
const PEDIDOS = [
  { numero: 'MC-00139', origen: LIMA, destino: destino('San Isidro', '150131'), disponibleDesde: '2026-10-21T15:00:00Z', esperaMinutos: 2 * 1440 + 4 * 60 + 10 },
  { numero: 'MC-00151', origen: CUSCO, destino: destino('Wanchaq', '080108', 'Cusco', 'Cusco'), disponibleDesde: '2026-10-23T09:00:00Z', esperaMinutos: 6 * 60 + 5 },
  { numero: 'MC-00153', origen: LIMA, destino: destino('Miraflores', '150122'), disponibleDesde: '2026-10-23T14:15:00Z', esperaMinutos: 45 },
]

const DISPONIBLES = 'GET /api/distribuidor/pedidos/disponibles'

const comoDistribuidor = (extra = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_DISTRIBUIDOR],
  [DISPONIBLES]: [200, PEDIDOS],
  ...extra,
})

describe('DW-01 Pedidos disponibles del distribuidor (CCPIP-107)', () => {
  afterEach(() => i18n.changeLanguage('es'))

  it('lista los pedidos con el punto de recojo, el distrito de destino y cuánto llevan esperando', async () => {
    servidor(comoDistribuidor())
    abrirEn('/distribuidor/pedidos')

    expect(await screen.findByRole('heading', { level: 1, name: 'Pedidos disponibles' })).toBeInTheDocument()
    expect(screen.getByText('Reparto / Pedidos disponibles')).toBeInTheDocument()
    const filas = within(await screen.findByRole('table', { name: 'Pedidos disponibles' })).getAllByRole('row').slice(1)
    expect(filas).toHaveLength(3)
    // Conserva el orden del servidor: el que lleva mas esperando primero
    expect(filas.map((f) => within(f).getAllByRole('rowheader')[0].textContent)).toEqual(['MC-00139', 'MC-00151', 'MC-00153'])
    expect(within(filas[0]).getByText('Centro Nacional Lima')).toBeInTheDocument()
    expect(within(filas[0]).getByText('San Isidro')).toBeInTheDocument()
    expect(within(filas[0]).getByText('2d 4h')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Wanchaq')).toBeInTheDocument()
    expect(within(filas[1]).getByText('6 h')).toBeInTheDocument()
    expect(within(filas[2]).getByText('45 min')).toBeInTheDocument()
    expect(screen.getByText('3 pedidos disponibles')).toBeInTheDocument()
  })

  it('el menú del distribuidor solo ofrece lo que ya existe', async () => {
    servidor(comoDistribuidor())
    abrirEn('/distribuidor/pedidos')
    const menu = within(await screen.findByRole('navigation', { name: 'Distribuidor' }))
    expect(menu.getAllByRole('link')).toHaveLength(1)
    expect(menu.getByRole('link', { name: 'Pedidos disponibles' })).toHaveAttribute('aria-current', 'page')
  })

  it('se filtra por punto de acopio y se vuelve a ver todos', async () => {
    servidor(comoDistribuidor())
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    const filtro = within(await screen.findByRole('group', { name: 'Filtrar por punto de acopio' }))
    expect(filtro.getByRole('button', { name: 'Todos (3)' })).toHaveAttribute('aria-pressed', 'true')
    await usuario.click(filtro.getByRole('button', { name: 'Centro Regional Cusco (1)' }))
    const tabla = screen.getByRole('table', { name: 'Pedidos disponibles' })
    expect(within(tabla).getAllByRole('row')).toHaveLength(2)
    expect(within(tabla).getByText('MC-00151')).toBeInTheDocument()
    expect(within(tabla).queryByText('MC-00139')).not.toBeInTheDocument()
    expect(screen.getByText('1 pedido disponible')).toBeInTheDocument()

    await usuario.click(filtro.getByRole('button', { name: 'Todos (3)' }))
    expect(within(screen.getByRole('table', { name: 'Pedidos disponibles' })).getAllByRole('row')).toHaveLength(4)
  })

  it('si el punto filtrado ya no tiene pedidos al actualizar, vuelve a mostrar todos', async () => {
    let vez = 0
    servidor(comoDistribuidor({ [DISPONIBLES]: () => [200, ++vez === 1 ? PEDIDOS : PEDIDOS.filter((p) => p.origen.id === 1)] }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    await usuario.click(await screen.findByRole('button', { name: 'Centro Regional Cusco (1)' }))
    await usuario.click(screen.getByRole('button', { name: 'Actualizar' }))
    expect(await screen.findByText('MC-00139')).toBeInTheDocument()
    expect(screen.getByText('MC-00153')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Centro Regional Cusco/ })).not.toBeInTheDocument()
  })

  it('sin pedidos explica por qué y deja actualizar', async () => {
    let vez = 0
    const fetchMock = servidor(comoDistribuidor({ [DISPONIBLES]: () => [200, ++vez === 1 ? [] : PEDIDOS] }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    expect(await screen.findByText('No hay pedidos disponibles para repartir')).toBeInTheDocument()
    expect(screen.getByText(/aparecerá aquí/)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    await usuario.click(screen.getByRole('button', { name: 'Actualizar' }))
    expect(await screen.findByText('MC-00139')).toBeInTheDocument()
    expect(llamadas(fetchMock, DISPONIBLES)).toHaveLength(2)
  })

  it('si falla la consulta lo avisa y deja reintentar', async () => {
    let vez = 0
    servidor(comoDistribuidor({ [DISPONIBLES]: () => (++vez === 1 ? [500, { code: 'ERROR', error: 'Falla' }] : [200, PEDIDOS]) }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    expect(await screen.findByText('No pudimos cargar los pedidos disponibles')).toBeInTheDocument()
    await usuario.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('MC-00139')).toBeInTheDocument()
  })

  it('no muestra la dirección exacta ni el contacto de quien recibe', async () => {
    const conDatos = PEDIDOS.map((p) => ({ ...p, destino: { ...p.destino, direccion: 'Calle Secreta 999' }, contactoCelular: '999888777', comprador: 'Persona Reservada' }))
    servidor(comoDistribuidor({ [DISPONIBLES]: [200, conDatos] }))
    abrirEn('/distribuidor/pedidos')
    await screen.findByText('MC-00139')
    expect(screen.queryByText(/Calle Secreta/)).not.toBeInTheDocument()
    expect(screen.queryByText(/999888777/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Persona Reservada/)).not.toBeInTheDocument()
  })

  it('está en inglés cuando se elige', async () => {
    await i18n.changeLanguage('en')
    servidor(comoDistribuidor())
    abrirEn('/distribuidor/pedidos')
    expect(await screen.findByRole('heading', { level: 1, name: 'Available orders' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Pick up at' })).toBeInTheDocument()
    expect(screen.getByText('3 orders available')).toBeInTheDocument()
  })
})

describe('Acceso al reparto y su inicio (CCPIP-107)', () => {
  it('/distribuidor lleva a los pedidos disponibles', async () => {
    servidor(comoDistribuidor())
    abrirEn('/distribuidor')
    expect(await screen.findByRole('heading', { level: 1, name: 'Pedidos disponibles' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/distribuidor/pedidos')
  })

  it('otro rol no entra al reparto: vuelve a su inicio', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION],
      'GET /api/panel/consumidor': [200, PANEL],
      [DISPONIBLES]: [200, PEDIDOS],
    })
    abrirEn('/distribuidor/pedidos')
    await waitFor(() => expect(window.location.pathname).toBe('/inicio'))
    expect(llamadas(fetchMock, DISPONIBLES)).toHaveLength(0)
  })

  it('sin sesión pide ingresar y vuelve después', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/distribuidor/pedidos')
    await waitFor(() => expect(window.location.pathname).toBe('/ingresar'))
    expect(window.location.search).toContain('volver=%2Fdistribuidor%2Fpedidos')
  })

  it('el inicio del distribuidor lo presenta como responsable de las entregas y lleva a los pedidos', async () => {
    servidor(comoDistribuidor({ 'GET /api/panel/distribuidor': [200, { titulo: 'Tus entregas', descripcion: 'Aqui veras los pedidos listos para repartir y los que asumas.' }] }))
    abrirEn('/inicio')
    expect(await screen.findByRole('heading', { name: 'Tus entregas' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver pedidos disponibles' })).toHaveAttribute('href', '/distribuidor/pedidos')
    expect(screen.queryByText(/compras mayoristas/)).not.toBeInTheDocument()
  })
})

describe('Espera de un pedido', () => {
  const t = (clave, o) => ({
    'distribuidor.pedidos.espera.minutos': `${o?.m} min`,
    'distribuidor.pedidos.espera.horas': `${o?.h} h`,
    'distribuidor.pedidos.espera.dias': `${o?.d}d ${o?.h}h`,
  })[clave]

  it('se muestra en minutos, horas o días con horas', () => {
    expect(textoEspera(0, t)).toBe('0 min')
    expect(textoEspera(59, t)).toBe('59 min')
    expect(textoEspera(60, t)).toBe('1 h')
    expect(textoEspera(1439, t)).toBe('23 h')
    expect(textoEspera(1440, t)).toBe('1d 0h')
    expect(textoEspera(2 * 1440 + 4 * 60 + 30, t)).toBe('2d 4h')
  })
})
