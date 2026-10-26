import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_ACOPIO, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const TODOS = ['POR_VENCER', 'ESTANCADO', 'REPOSICION', 'INTERES', 'TRANSFERENCIA_RECIBIDA', 'PEDIDO_NUEVO']

// Armado segun AlertaModelos del backend (PW-21)
const BANDEJA = {
  sinLeer: 2,
  alertas: [
    {
      id: 7, tipo: 'POR_VENCER', fecha: '2026-10-23', creadaEn: '2026-10-18T13:00:00Z', leida: false, resuelta: false,
      porCorreo: true, productoId: 9, producto: 'Harina de aguaje', lote: 'L-0231', ubicacion: 'Centro Nacional Lima', cantidad: 6,
    },
    {
      id: 6, tipo: 'INTERES', fecha: '2026-10-17', creadaEn: '2026-10-17T15:00:00Z', leida: false, resuelta: false,
      porCorreo: true, productoId: 5, producto: 'Aceite de sacha inchi', cantidad: 23, personas: 11,
    },
    {
      id: 5, tipo: 'TRANSFERENCIA_RECIBIDA', fecha: '2026-10-16', creadaEn: '2026-10-16T15:00:00Z', leida: true, resuelta: false,
      porCorreo: false, ubicacion: 'Centro Regional Ucayali', referencia: 'TR-0028', cantidad: 20, productos: ['Harina de aguaje'],
    },
    {
      id: 4, tipo: 'REPOSICION', fecha: '2026-10-10', creadaEn: '2026-10-10T15:00:00Z', leida: true, resuelta: true,
      porCorreo: true, productoId: 3, producto: 'Camu camu en polvo',
    },
  ],
}

const PREFERENCIAS = { diasVencimiento: 15, diasEstancado: 30, tipos: TODOS, correo: true, provisional: true, disponibles: TODOS }

function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/alertas': () => [200, copia(BANDEJA)],
    'GET /api/productor/alertas/preferencias': () => [200, copia(PREFERENCIAS)],
    'POST /api/productor/alertas/7/leida': [204],
    'POST /api/productor/alertas/leidas': [204],
    ...rutas,
  })
}

const cuerpo = (fetchMock, clave) => JSON.parse(llamadas(fetchMock, clave)[0][1].body)

describe('PW-21 Alertas del productor (CCPIP-79, 145, 293, 337, 366, 367)', () => {
  afterEach(() => {
    vi.useRealTimers()
    return i18n.changeLanguage('es')
  })

  it('está en el menú y muestra cada alerta con producto, motivo y fecha', async () => {
    productor()
    abrirEn('/productor/alertas')

    expect(await screen.findByRole('heading', { level: 1, name: 'Alertas' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Alertas' })).toHaveAttribute('aria-current', 'page')

    const lista = await screen.findByRole('list')
    const items = within(lista).getAllByRole('listitem')
    expect(items).toHaveLength(4)
    expect(within(items[0]).getByText('Lote por vencer: Harina de aguaje')).toBeInTheDocument()
    expect(within(items[0]).getByText(/El lote L-0231 en Centro Nacional Lima vence el 23\/10\/2026 y le quedan 6 und/)).toBeInTheDocument()
    expect(within(items[0]).getByText('También por correo')).toBeInTheDocument()
    expect(within(items[0]).getByRole('img', { name: 'Sin leer' })).toBeInTheDocument()
    expect(within(items[1]).getByText('Interés de compradores en Aceite de sacha inchi')).toBeInTheDocument()
    expect(within(items[1]).getByText(/Personas interesadas: 11 · Unidades pedidas: 23/)).toBeInTheDocument()
    expect(within(items[2]).getByText('Centro Regional Ucayali recibió TR-0028')).toBeInTheDocument()
    expect(within(items[2]).queryByText('También por correo')).not.toBeInTheDocument()
    expect(within(items[2]).queryByRole('img', { name: 'Sin leer' })).not.toBeInTheDocument()
    // Una condicion que ya no se cumple queda en el historial como resuelta
    expect(within(items[3]).getByText('Resuelta')).toBeInTheDocument()
  })

  it('filtra por tipo con los chips', async () => {
    productor()
    const user = userEvent.setup()
    abrirEn('/productor/alertas')

    const filtros = await screen.findByRole('group', { name: 'Filtrar alertas' })
    expect(within(filtros).getByRole('button', { name: 'Todas (4)', pressed: true })).toBeInTheDocument()
    // Solo los tipos que tienen alertas
    expect(within(filtros).queryByRole('button', { name: 'Pedidos' })).not.toBeInTheDocument()

    await user.click(within(filtros).getByRole('button', { name: 'Interés' }))
    const items = within(screen.getByRole('list')).getAllByRole('listitem')
    expect(items).toHaveLength(1)
    expect(items[0]).toHaveTextContent('Aceite de sacha inchi')
  })

  it('abrir una alerta la marca leída y lleva a donde se resuelve', async () => {
    const fetchMock = productor({ 'GET /api/productor/inventario': new Error('sin red') })
    const user = userEvent.setup()
    abrirEn('/productor/alertas')

    await user.click(await screen.findByRole('link', { name: 'Ver almacén' }))
    await waitFor(() => expect(llamadas(fetchMock, 'POST /api/productor/alertas/7/leida')).toHaveLength(1))
    expect(window.location.pathname).toBe('/productor/almacen')
  })

  it('marca todas como leídas', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/alertas')

    await user.click(await screen.findByRole('button', { name: 'Marcar todas como leídas' }))
    await waitFor(() => expect(llamadas(fetchMock, 'POST /api/productor/alertas/leidas')).toHaveLength(1))
    expect(screen.queryByRole('img', { name: 'Sin leer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Marcar todas como leídas' })).not.toBeInTheDocument()
  })

  // CCPIP-425: la bandeja abierta se mantiene al dia, sin esperar la revision periodica ni recargar
  it('muestra una alerta nueva sin recargar la página', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let bandeja = { sinLeer: 0, alertas: [] }
    const fetchMock = productor({ 'GET /api/productor/alertas': () => [200, copia(bandeja)] })
    abrirEn('/productor/alertas')
    expect(await screen.findByText('No tienes alertas')).toBeInTheDocument()

    bandeja = { sinLeer: 1, alertas: [BANDEJA.alertas[0]] }
    await act(() => vi.advanceTimersByTimeAsync(15_000))

    expect(await screen.findByText('Lote por vencer: Harina de aguaje')).toBeInTheDocument()
    expect(screen.queryByText('No tienes alertas')).not.toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/productor/alertas')).toHaveLength(2)
  })

  it('sin alertas lo explica', async () => {
    productor({ 'GET /api/productor/alertas': [200, { sinLeer: 0, alertas: [] }] })
    abrirEn('/productor/alertas')
    expect(await screen.findByText('No tienes alertas')).toBeInTheDocument()
  })

  it('configura la anticipación, los tipos y el correo sin días arbitrarios', async () => {
    const guardadas = { ...PREFERENCIAS, diasVencimiento: 45, tipos: TODOS.filter((t) => t !== 'PEDIDO_NUEVO'), correo: false, provisional: false }
    const fetchMock = productor({ 'PUT /api/productor/alertas/preferencias': [200, guardadas] })
    const user = userEvent.setup()
    abrirEn('/productor/alertas')

    const form = await screen.findByRole('form', { name: 'Configurar alertas' })
    const anticipacion = await within(form).findByRole('combobox', { name: 'Avisarme de vencimientos con anticipación de' })
    expect(anticipacion).toHaveValue('15')
    expect(within(form).getByRole('combobox', { name: 'Lote estancado si no tiene salidas en' })).toHaveValue('30')
    expect(within(form).getByText('Valores provisionales de la Cámara hasta que guardes los tuyos.')).toBeInTheDocument()
    const plataforma = within(form).getByRole('checkbox', { name: 'En la plataforma (siempre activo)' })
    expect(plataforma).toBeChecked()
    expect(plataforma).toHaveAttribute('aria-disabled', 'true')
    await user.click(plataforma)
    expect(plataforma).toBeChecked()

    await user.selectOptions(anticipacion, 'Personalizado')
    const dias = within(form).getByRole('textbox', { name: 'Días' })
    await user.clear(dias)
    // Un valor fuera de rango no se envia
    await user.type(dias, '0')
    await user.click(within(form).getByRole('button', { name: 'Guardar preferencias' }))
    expect(within(form).getByText('Ingresa un número de días entre 1 y 365.')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'PUT /api/productor/alertas/preferencias')).toHaveLength(0)

    await user.clear(dias)
    await user.type(dias, '45')
    await user.click(within(form).getByRole('checkbox', { name: 'Pedidos nuevos' }))
    await user.click(within(form).getByRole('checkbox', { name: 'Correo electrónico para alertas importantes' }))
    await user.click(within(form).getByRole('button', { name: 'Guardar preferencias' }))

    expect(await within(form).findByText('Preferencias guardadas')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'PUT /api/productor/alertas/preferencias')).toEqual({
      diasVencimiento: 45, diasEstancado: 30, tipos: TODOS.filter((t) => t !== 'PEDIDO_NUEVO'), correo: false,
    })
    expect(within(form).queryByText('Valores provisionales de la Cámara hasta que guardes los tuyos.')).not.toBeInTheDocument()
    // CCPIP-425: otra anticipacion abre o cierra alertas, asi que la bandeja se vuelve a pedir
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/productor/alertas')).toHaveLength(2))
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    productor()
    abrirEn('/productor/alertas')
    expect(await screen.findByText('Batch expiring soon: Harina de aguaje')).toBeInTheDocument()
    expect(screen.getByRole('form', { name: 'Alert settings' })).toBeInTheDocument()
  })
})

const PUNTO = { ubicacionId: 1, punto: 'Centro Nacional Lima', preferencias: { ...PREFERENCIAS, tipos: ['POR_VENCER', 'ESTANCADO'], disponibles: ['POR_VENCER', 'ESTANCADO'] } }

function acopio(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_ACOPIO],
    'GET /api/acopio/alertas': [200, {
      sinLeer: 1,
      alertas: [{
        id: 9, tipo: 'ESTANCADO', fecha: '2026-09-08', creadaEn: '2026-10-18T12:00:00Z', leida: false, resuelta: false,
        porCorreo: true, productoId: 2, producto: 'Cacao en grano', lote: 'L-0240', ubicacion: 'Centro Nacional Lima', cantidad: 4,
      }],
    }],
    'GET /api/acopio/alertas/preferencias': () => [200, [copia(PUNTO)]],
    ...rutas,
  })
}

describe('A-08 Alertas del punto de acopio (CCPIP-79, 145, 337)', () => {
  it('está en el menú del punto con sus lotes y solo los tipos del punto', async () => {
    acopio()
    abrirEn('/acopio/alertas')

    expect(await screen.findByText('Lote estancado: Cacao en grano')).toBeInTheDocument()
    expect(screen.getByText(/El lote L-0240 no tiene salidas desde el 08\/09\/2026/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver inventario' })).toHaveAttribute('href', '/acopio/inventario')
    expect(screen.getByRole('link', { name: 'Alertas' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText('Centro Nacional Lima / Alertas')).toBeInTheDocument()

    const form = screen.getByRole('form', { name: 'Configurar alertas' })
    expect(within(form).getByText('Configurable por el punto.')).toBeInTheDocument()
    expect(within(form).getByRole('checkbox', { name: 'Lotes próximos a vencer' })).toBeChecked()
    expect(within(form).getByRole('checkbox', { name: 'Lotes estancados' })).toBeChecked()
    expect(within(form).queryByRole('checkbox', { name: 'Ofertas sin stock en venta' })).not.toBeInTheDocument()
    // Con un solo punto no hay que elegirlo
    expect(within(form).queryByRole('combobox', { name: 'Punto de acopio' })).not.toBeInTheDocument()
  })

  it('guarda la configuración del punto elegido', async () => {
    const loreto = { ubicacionId: 2, punto: 'Centro Regional Loreto', preferencias: { ...PUNTO.preferencias, diasVencimiento: 7, provisional: false } }
    const fetchMock = acopio({
      'GET /api/acopio/alertas/preferencias': () => [200, [copia(PUNTO), copia(loreto)]],
      'PUT /api/acopio/alertas/preferencias/2': (o) => [200, { ...loreto, preferencias: { ...loreto.preferencias, ...JSON.parse(o.body) } }],
    })
    const user = userEvent.setup()
    abrirEn('/acopio/alertas')

    const form = await screen.findByRole('form', { name: 'Configurar alertas' })
    await user.selectOptions(await within(form).findByRole('combobox', { name: 'Punto de acopio' }), 'Centro Regional Loreto')
    expect(within(form).getByRole('combobox', { name: 'Avisarme de vencimientos con anticipación de' })).toHaveValue('7')
    await user.selectOptions(within(form).getByRole('combobox', { name: 'Lote estancado si no tiene salidas en' }), '60 días')
    await user.click(within(form).getByRole('button', { name: 'Guardar preferencias' }))

    expect(await within(form).findByText('Preferencias guardadas')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'PUT /api/acopio/alertas/preferencias/2')).toEqual({
      diasVencimiento: 7, diasEstancado: 60, tipos: ['POR_VENCER', 'ESTANCADO'], correo: true,
    })
  })
})
