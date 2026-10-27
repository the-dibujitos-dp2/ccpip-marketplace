import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_ACOPIO, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const RUTA = '/api/acopio/movimientos'

/** Una venta confirmada como la devuelve el servidor: con lote y pedido, sin datos del comprador. */
function conVenta() {
  const datos = copia(DATOS.movimientos)
  datos.items.unshift({
    ...datos.items[0],
    id: 50,
    registradoEn: '2026-11-16T14:12:00Z',
    tipo: 'SALIDA_VENTA',
    lote: 'L-0010',
    cantidad: 2.0,
    pedido: 'MC-00148',
    observacion: 'Venta del pedido MC-00148',
  })
  datos.total += 1
  return datos
}

const comoAcopio = (rutas = {}) => ({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_ACOPIO], ...rutas })

describe('A-03 Movimientos del punto de acopio (CCPIP-158)', () => {
  it('muestra cada movimiento con su tipo, producto, lote, pedido y cantidad con signo', async () => {
    servidor(comoAcopio({ [`GET ${RUTA}?pagina=0`]: [200, conVenta()] }))
    abrirEn('/acopio/movimientos')

    expect(await screen.findByRole('heading', { level: 1, name: 'Movimientos' })).toBeInTheDocument()
    // El nombre del punto llega con los movimientos
    expect(await screen.findByText('Centro Nacional Lima / Movimientos')).toBeInTheDocument()
    const filas = within(screen.getByRole('list', { name: 'Movimientos del punto' })).getAllByRole('listitem')
    expect(filas).toHaveLength(4)

    // La venta: salida con su lote y numero de pedido, hora de Lima, y sin el nombre del comprador
    expect(within(filas[0]).getByText('16/11 09:12')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Salida')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Cafe tostado en grano 500 g · L-0010 · MC-00148')).toBeInTheDocument()
    expect(within(filas[0]).getByText('−2')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Compra en línea')).toBeInTheDocument()

    // La carga inicial sembrada: entrada sin lote
    expect(within(filas[1]).getByText('Entrada')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Cafe tostado en grano 500 g')).toBeInTheDocument()
    expect(within(filas[1]).getByText('+100')).toBeInTheDocument()
  })

  it('cada pestaña pide solo ese grupo de movimientos', async () => {
    const soloVenta = conVenta()
    soloVenta.items = soloVenta.items.slice(0, 1)
    const fetchMock = servidor(comoAcopio({
      [`GET ${RUTA}?pagina=0`]: [200, conVenta()],
      [`GET ${RUTA}?pagina=0&tipo=SALIDAS`]: [200, soloVenta],
      [`GET ${RUTA}?pagina=0&tipo=AJUSTES`]: [200, { ...soloVenta, items: [], total: 0, totalPaginas: 0 }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/movimientos')
    await screen.findByText('Cafe tostado en grano 500 g · L-0010 · MC-00148')

    await user.click(screen.getByRole('tab', { name: 'Salidas' }))
    expect(screen.getByRole('tab', { name: 'Salidas' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(screen.queryByText('+100')).not.toBeInTheDocument())
    expect(llamadas(fetchMock, `GET ${RUTA}?pagina=0&tipo=SALIDAS`)).toHaveLength(1)

    await user.click(screen.getByRole('tab', { name: 'Ajustes' }))
    expect(await screen.findByText('Aún no hay movimientos')).toBeInTheDocument()
  })

  it('busca mientras se escribe por producto, lote o pedido, sin consultar con cada tecla (CCPIP-417)', async () => {
    const delPedido = conVenta()
    delPedido.items = delPedido.items.slice(0, 1)
    delPedido.total = 1
    const fetchMock = servidor(comoAcopio({
      [`GET ${RUTA}?pagina=0`]: () => [200, copia(DATOS.movimientos)],
      [`GET ${RUTA}?pagina=0&q=mc-00148`]: [200, delPedido],
      [`GET ${RUTA}?pagina=0&q=nada`]: [200, { ...delPedido, items: [], total: 0, totalPaginas: 0 }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/movimientos')
    await screen.findByText('Camu camu en polvo 150 g')

    // Sin Enter: la lista cambia sola cuando se deja de escribir
    await user.type(screen.getByRole('searchbox', { name: 'Buscar producto, lote o N.º de pedido' }), 'mc-00148')
    expect(await screen.findByText('Cafe tostado en grano 500 g · L-0010 · MC-00148')).toBeInTheDocument()
    expect(screen.queryByText('Camu camu en polvo 150 g')).not.toBeInTheDocument()
    // Una sola consulta con todo lo escrito, no una por letra
    expect(fetchMock.mock.calls.filter(([url]) => url.includes('q='))).toHaveLength(1)

    await user.clear(screen.getByRole('searchbox'))
    await user.type(screen.getByRole('searchbox'), 'nada')
    expect(await screen.findByText('Nada coincide con la búsqueda y los filtros')).toBeInTheDocument()
  })

  it('filtra por un rango de fechas y de cantidad, combinado con el tipo, y limpiar vuelve a todo (CCPIP-417)', async () => {
    const soloVenta = conVenta()
    soloVenta.items = soloVenta.items.slice(0, 1)
    const FILTRADO = `GET ${RUTA}?pagina=0&tipo=SALIDAS&q=cafe&desde=2026-11-01&hasta=2026-11-30&cantidadMin=1&cantidadMax=5`
    const fetchMock = servidor(comoAcopio({
      [`GET ${RUTA}?pagina=0`]: () => [200, conVenta()],
      [`GET ${RUTA}`]: [200, soloVenta],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/movimientos')
    await screen.findByText('Cafe tostado en grano 500 g · L-0010 · MC-00148')
    expect(screen.getByText('Para ver un solo día, elige la misma fecha en Desde y Hasta.')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Salidas' }))
    await user.type(screen.getByRole('searchbox'), 'cafe')
    await user.type(screen.getByLabelText('Desde'), '2026-11-01')
    await user.type(screen.getByLabelText('Hasta'), '2026-11-30')
    await user.type(screen.getByLabelText('Cantidad mínima'), '1')
    await user.type(screen.getByLabelText('Cantidad máxima'), '5')
    await waitFor(() => expect(llamadas(fetchMock, FILTRADO)).toHaveLength(1))

    await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }))
    expect(screen.getByRole('tab', { name: 'Todos' })).toHaveAttribute('aria-selected', 'true')
    for (const campo of ['Desde', 'Hasta', 'Cantidad mínima', 'Cantidad máxima']) {
      expect(screen.getByLabelText(campo)).toHaveValue(campo.startsWith('Cantidad') ? null : '')
    }
    expect(screen.getByRole('searchbox')).toHaveValue('')
    await waitFor(() => expect(llamadas(fetchMock, `GET ${RUTA}?pagina=0`)).toHaveLength(2))
    expect(screen.queryByRole('button', { name: 'Limpiar filtros' })).not.toBeInTheDocument()
  })

  it('un rango al revés lo explica el servidor', async () => {
    servidor(comoAcopio({
      [`GET ${RUTA}?pagina=0`]: () => [200, conVenta()],
      [`GET ${RUTA}?pagina=0&cantidadMin=5&cantidadMax=1`]: [422, { code: 'RANGO_INVALIDO', error: 'La cantidad mínima debe ser menor o igual a la máxima.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/movimientos')
    await screen.findByText('Cafe tostado en grano 500 g · L-0010 · MC-00148')
    await user.type(screen.getByLabelText('Cantidad mínima'), '5')
    await user.type(screen.getByLabelText('Cantidad máxima'), '1')
    expect(await screen.findByText('La cantidad mínima debe ser menor o igual a la máxima.')).toBeInTheDocument()
  })

  it('si falla lo dice y permite reintentar', async () => {
    let intentos = 0
    servidor(comoAcopio({
      [`GET ${RUTA}?pagina=0`]: () => (++intentos === 1 ? new Error('sin red') : [200, copia(DATOS.movimientos)]),
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/movimientos')

    expect(await screen.findByText('No pudimos cargar los movimientos')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Camu camu en polvo 150 g')).toBeInTheDocument()
  })

  it('el administrador de acopio llega desde su inicio', async () => {
    servidor(comoAcopio({
      'GET /api/panel/acopio': [200, { titulo: 'Tu punto', descripcion: 'Panel del punto' }],
      [`GET ${RUTA}?pagina=0`]: [200, copia(DATOS.movimientos)],
    }))
    const user = userEvent.setup()
    abrirEn('/inicio')
    await user.click(await screen.findByRole('link', { name: 'Ver movimientos del punto' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Movimientos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/acopio/movimientos')
  })

  it('solo el administrador de punto entra; sin sesion pide ingresar', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION],
      'GET /api/panel/consumidor': [200, { titulo: 'Tu mercado', descripcion: 'Panel' }],
    })
    const { unmount } = abrirEn('/acopio/movimientos')
    await waitFor(() => expect(window.location.pathname).toBe('/inicio'))
    expect(llamadas(fetchMock, `GET ${RUTA}?pagina=0`)).toHaveLength(0)
    unmount()

    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/acopio/movimientos')
    await waitFor(() => expect(window.location.pathname).toBe('/ingresar'))
    expect(window.location.search).toBe(`?volver=${encodeURIComponent('/acopio/movimientos')}`)
  })
})
