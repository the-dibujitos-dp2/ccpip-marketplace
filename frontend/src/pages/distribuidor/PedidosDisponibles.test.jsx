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

const VACIA = { items: [], siguiente: null, total: 0, puntos: [] }
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

/**
 * El servidor de la cola: filtra por punto y entrega las paginas con un cursor (aqui, la posicion en la lista),
 * junto con el total y los puntos con pedidos, que no dependen del filtro.
 */
function cola(pedidos, tamano = 20) {
  return (_opciones, url) => {
    const query = new URL(url, 'http://servidor').searchParams
    const punto = query.get('punto') ? Number(query.get('punto')) : null
    const desde = query.get('cursor') ? Number(query.get('cursor')) : 0
    const filtrados = pedidos.filter((p) => punto === null || p.origen.id === punto)
    const porPunto = new Map()
    pedidos.forEach((p) => porPunto.set(p.origen.id, { id: p.origen.id, nombre: p.origen.nombre, total: (porPunto.get(p.origen.id)?.total ?? 0) + 1 }))
    return [200, {
      items: filtrados.slice(desde, desde + tamano),
      siguiente: desde + tamano < filtrados.length ? String(desde + tamano) : null,
      total: filtrados.length,
      puntos: [...porPunto.values()].sort((a, b) => a.nombre.localeCompare(b.nombre)),
    }]
  }
}

const comoDistribuidor = (extra = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_DISTRIBUIDOR],
  [DISPONIBLES]: cola(PEDIDOS),
  ...extra,
})

// Mas pedidos de los que caben en una pagina, el que lleva mas esperando primero
const MUCHOS = Array.from({ length: 25 }, (_, i) => ({
  numero: `MC-${String(200 + i).padStart(5, '0')}`,
  origen: i % 5 === 4 ? CUSCO : LIMA,
  destino: destino('Miraflores', '150122'),
  disponibleDesde: '2026-10-22T15:00:00Z',
  esperaMinutos: 3000 - i * 10,
}))

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
    expect(menu.getAllByRole('link').map((l) => l.textContent)).toEqual(['Pedidos disponibles', 'Mis entregas'])
    expect(menu.getByRole('link', { name: 'Pedidos disponibles' })).toHaveAttribute('aria-current', 'page')
    expect(menu.getByRole('link', { name: 'Mis entregas' })).toHaveAttribute('href', '/distribuidor/entregas')
  })

  it('se filtra por punto de acopio y se vuelve a ver todos', async () => {
    const fetchMock = servidor(comoDistribuidor())
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    const filtro = within(await screen.findByRole('group', { name: 'Filtrar por punto de acopio' }))
    expect(filtro.getByRole('button', { name: 'Todos (3)' })).toHaveAttribute('aria-pressed', 'true')
    await usuario.click(filtro.getByRole('button', { name: 'Centro Regional Cusco (1)' }))
    const tabla = await screen.findByRole('table', { name: 'Pedidos disponibles' })
    await waitFor(() => expect(within(tabla).getAllByRole('row')).toHaveLength(2))
    expect(llamadas(fetchMock, `${DISPONIBLES}?punto=3`)).toHaveLength(1)
    expect(within(tabla).getByText('MC-00151')).toBeInTheDocument()
    expect(within(tabla).queryByText('MC-00139')).not.toBeInTheDocument()
    expect(screen.getByText('1 pedido disponible')).toBeInTheDocument()

    await usuario.click(filtro.getByRole('button', { name: 'Todos (3)' }))
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Pedidos disponibles' })).getAllByRole('row')).toHaveLength(4))
  })

  it('si el punto filtrado ya no tiene pedidos al actualizar, vuelve a mostrar todos', async () => {
    let actuales = PEDIDOS
    servidor(comoDistribuidor({ [DISPONIBLES]: (opciones, url) => cola(actuales)(opciones, url) }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    await usuario.click(await screen.findByRole('button', { name: 'Centro Regional Cusco (1)' }))
    await waitFor(() => expect(screen.queryByText('MC-00139')).not.toBeInTheDocument())
    // El punto filtrado se queda sin pedidos mientras tanto
    actuales = PEDIDOS.filter((p) => p.origen.id === 1)
    await usuario.click(screen.getByRole('button', { name: 'Actualizar' }))
    expect(await screen.findByText('MC-00139')).toBeInTheDocument()
    expect(screen.getByText('MC-00153')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Centro Regional Cusco/ })).not.toBeInTheDocument()
  })

  it('muestra la cola por páginas: avisa cuántos faltan y carga el resto con el cursor', async () => {
    const fetchMock = servidor(comoDistribuidor({ [DISPONIBLES]: cola(MUCHOS) }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    const tabla = await screen.findByRole('table', { name: 'Pedidos disponibles' })
    expect(within(tabla).getAllByRole('row')).toHaveLength(21)
    expect(screen.getByText('25 pedidos disponibles')).toBeInTheDocument()
    expect(screen.getByText('Mostrando 20 de 25')).toBeInTheDocument()

    await usuario.click(screen.getByRole('button', { name: 'Cargar más pedidos' }))
    await waitFor(() => expect(within(tabla).getAllByRole('row')).toHaveLength(26))
    // Las páginas se encadenan en el orden del servidor y sin repetir pedidos
    const numeros = within(tabla).getAllByRole('rowheader').map((c) => c.textContent)
    expect(numeros).toEqual(MUCHOS.map((p) => p.numero))
    expect(screen.queryByRole('button', { name: 'Cargar más pedidos' })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, `${DISPONIBLES}?cursor=20`)).toHaveLength(1)
  })

  it('si falla cargar más lo avisa, conserva lo cargado y deja reintentar', async () => {
    let paginas = 0
    servidor(comoDistribuidor({
      [DISPONIBLES]: (opciones, url) => (url.includes('cursor') && ++paginas === 1 ? [500, { code: 'ERROR', error: 'Falla' }] : cola(MUCHOS)(opciones, url)),
    }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    await usuario.click(await screen.findByRole('button', { name: 'Cargar más pedidos' }))
    expect(await screen.findByText('No pudimos cargar más pedidos')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Pedidos disponibles' })).getAllByRole('row')).toHaveLength(21)
    await usuario.click(screen.getByRole('button', { name: 'Cargar más pedidos' }))
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Pedidos disponibles' })).getAllByRole('row')).toHaveLength(26))
    expect(screen.queryByText('No pudimos cargar más pedidos')).not.toBeInTheDocument()
  })

  it('al cambiar de punto o actualizar vuelve a la primera página', async () => {
    const fetchMock = servidor(comoDistribuidor({ [DISPONIBLES]: cola(MUCHOS) }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    await usuario.click(await screen.findByRole('button', { name: 'Cargar más pedidos' }))
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Pedidos disponibles' })).getAllByRole('row')).toHaveLength(26))
    await usuario.click(screen.getByRole('button', { name: 'Actualizar' }))
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Pedidos disponibles' })).getAllByRole('row')).toHaveLength(21))
    expect(screen.getByRole('button', { name: 'Cargar más pedidos' })).toBeInTheDocument()

    // Los chips cuentan todos los pedidos de cada punto, no solo los cargados
    await usuario.click(screen.getByRole('button', { name: 'Centro Regional Cusco (5)' }))
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Pedidos disponibles' })).getAllByRole('row')).toHaveLength(6))
    expect(screen.getByText('5 pedidos disponibles')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cargar más pedidos' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Todos (25)' })).toBeInTheDocument()
    expect(llamadas(fetchMock, `${DISPONIBLES}?punto=3`)).toHaveLength(1)
  })

  it('sin pedidos explica por qué y deja actualizar', async () => {
    let vez = 0
    const fetchMock = servidor(comoDistribuidor({ [DISPONIBLES]: (opciones, url) => (++vez === 1 ? [200, VACIA] : cola(PEDIDOS)(opciones, url)) }))
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
    servidor(comoDistribuidor({ [DISPONIBLES]: (opciones, url) => (++vez === 1 ? [500, { code: 'ERROR', error: 'Falla' }] : cola(PEDIDOS)(opciones, url)) }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')

    expect(await screen.findByText('No pudimos cargar los pedidos disponibles')).toBeInTheDocument()
    await usuario.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('MC-00139')).toBeInTheDocument()
  })

  it('no muestra la dirección exacta ni el contacto de quien recibe', async () => {
    const conDatos = PEDIDOS.map((p) => ({ ...p, destino: { ...p.destino, direccion: 'Calle Secreta 999' }, contactoCelular: '999888777', comprador: 'Persona Reservada' }))
    servidor(comoDistribuidor({ [DISPONIBLES]: cola(conDatos) }))
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
    expect(await screen.findByRole('columnheader', { name: 'Pick up at' })).toBeInTheDocument()
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
      [DISPONIBLES]: cola(PEDIDOS),
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
