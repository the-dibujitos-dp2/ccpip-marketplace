import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_ACOPIO } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const RUTA = '/api/acopio/inventario'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

const fila = (l) => ({ productorId: 1, productor: 'Asoc. Nii Joi', ubicacionId: 1, ubicacion: 'Centro Nacional Lima', ...l })

// Armado segun InventarioModelos.InventarioPunto del backend (CCPIP-162, A-02)
const INVENTARIO = {
  ubicacion: 'Centro Nacional Lima',
  diasAviso: 15,
  resumen: { stock: 94, vendible: 92, noVendible: 2, lotes: 3 },
  productores: [{ id: 1, nombre: 'Asoc. Nii Joi' }, { id: 2, nombre: 'C.N. Yarina' }],
  items: [
    fila({ id: 31, codigo: 'L-0231', productoId: 9, producto: 'Aceite de sacha inchi 250 ml', fechaVencimiento: '2026-10-21', entradas: 80, salidas: 48, stock: 32, estado: 'POR_VENCER', vendible: true }),
    fila({ id: 47, codigo: 'L-0247', productoId: 9, producto: 'Aceite de sacha inchi 250 ml', fechaVencimiento: '2027-11-02', entradas: 60, salidas: 0, stock: 60, estado: 'DISPONIBLE', vendible: true }),
    fila({ id: 12, codigo: 'L-0112', productoId: 5, producto: 'Chocolate 70 % cacao 100 g', productorId: 2, productor: 'C.N. Yarina', fechaVencimiento: '2026-10-01', entradas: 5, salidas: 3, stock: 2, estado: 'VENCIDO', vendible: false }),
  ],
  pagina: 0,
  tamano: 20,
  total: 3,
  totalPaginas: 1,
}

// InventarioModelos.LoteOpcion: todos los lotes del punto, aun sin saldo
const LOTES = [
  { id: 31, codigo: 'L-0231', productoId: 9, producto: 'Aceite de sacha inchi 250 ml', productorId: 1, productor: 'Asoc. Nii Joi', ubicacion: 'Centro Nacional Lima', stock: 32, fechaVencimiento: '2026-10-21', vencido: false },
  { id: 47, codigo: 'L-0247', productoId: 9, producto: 'Aceite de sacha inchi 250 ml', productorId: 1, productor: 'Asoc. Nii Joi', ubicacion: 'Centro Nacional Lima', stock: 60, fechaVencimiento: '2027-11-02', vencido: false },
  { id: 50, codigo: 'L-0250', productoId: 9, producto: 'Aceite de sacha inchi 250 ml', productorId: 1, productor: 'Asoc. Nii Joi', ubicacion: 'Centro Nacional Lima', stock: 0, fechaVencimiento: '2027-12-01', vencido: false },
  { id: 12, codigo: 'L-0112', productoId: 5, producto: 'Chocolate 70 % cacao 100 g', productorId: 2, productor: 'C.N. Yarina', ubicacion: 'Centro Nacional Lima', stock: 2, fechaVencimiento: '2026-10-01', vencido: true },
]

/** La respuesta de un movimiento registrado: InventarioModelos.MovimientoRegistrado. */
function registrado(tipo, lote, cantidad, saldo, motivo) {
  return {
    movimiento: { id: 900, registradoEn: '2026-11-16T14:12:00Z', tipo, productoId: 9, producto: 'Aceite de sacha inchi 250 ml', lote, cantidad, registradoPor: 'Admin Acopio', motivo },
    saldoLote: saldo,
    repetido: false,
  }
}

const comoAcopio = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_ACOPIO],
  [`GET ${RUTA}?pagina=0`]: () => [200, copia(INVENTARIO)],
  [`GET ${RUTA}/lotes`]: () => [200, copia(LOTES)],
  ...rutas,
})

const cuerpo = (llamada) => JSON.parse(llamada[1].body)

describe('A-02 Inventario del punto de acopio (CCPIP-162)', () => {
  afterEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('está en el menú del punto y desglosa cada lote por producto, productor y estado', async () => {
    servidor(comoAcopio())
    abrirEn('/acopio/inventario')

    expect(await screen.findByRole('heading', { level: 1, name: 'Inventario' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Inventario' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText('Centro Nacional Lima / Inventario')).toBeInTheDocument()

    const tabla = within(screen.getByRole('table', { name: 'Inventario del punto' }))
    const filas = tabla.getAllByRole('row').slice(1)
    expect(filas).toHaveLength(3)
    const primera = within(filas[0])
    expect(primera.getByText('Aceite de sacha inchi 250 ml')).toBeInTheDocument()
    expect(primera.getByText('Asoc. Nii Joi')).toBeInTheDocument()
    expect(primera.getByText('L-0231')).toBeInTheDocument()
    expect(primera.getByText('21/10/2026')).toBeInTheDocument()
    expect(primera.getByText('80')).toBeInTheDocument()
    expect(primera.getByText('48')).toBeInTheDocument()
    expect(primera.getByText('32')).toBeInTheDocument()
    expect(primera.getByText('Por vencer')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Vencido')).toBeInTheDocument()

    // El total viene del servidor, una vez por lote; lo vencido no es vendible
    expect(screen.getByText('Stock: 94 und · Vendible: 92 und · No vendible: 2 und')).toBeInTheDocument()
    expect(screen.getByText('Mostrando 1–3 de 3 lotes')).toBeInTheDocument()
    expect(screen.getByText('Un solo inventario, calculado a partir de los movimientos')).toBeInTheDocument()
    // CCPIP-76: cada lote con saldo se puede marcar como merma; sin ajustes de conteo
    expect(screen.getByRole('button', { name: 'Marcar como merma el lote L-0231' })).toBeInTheDocument()
    expect(screen.queryByText(/ajuste/i, { selector: 'button' })).not.toBeInTheDocument()
  })

  it('filtra por estado, productor y búsqueda en el servidor', async () => {
    const vacio = { ...copia(INVENTARIO), items: [], total: 0, totalPaginas: 0, resumen: { stock: 0, vendible: 0, noVendible: 0, lotes: 0 } }
    const fetchMock = servidor(comoAcopio({
      [`GET ${RUTA}?pagina=0&estado=AGOTADOS`]: [200, vacio],
      [`GET ${RUTA}?pagina=0&productor=2&estado=AGOTADOS`]: [200, vacio],
      [`GET ${RUTA}?pagina=0&q=l-02&productor=2&estado=AGOTADOS`]: [200, vacio],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')
    await screen.findByText('L-0231')

    await user.selectOptions(screen.getByLabelText('Estado del lote'), 'AGOTADOS')
    expect(await screen.findByText('No hay lotes con estos filtros')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Productor'), 'C.N. Yarina')
    await user.type(screen.getByRole('searchbox', { name: 'Buscar producto o lote…' }), 'l-02{Enter}')

    await waitFor(() => expect(llamadas(fetchMock, `GET ${RUTA}?pagina=0&q=l-02&productor=2&estado=AGOTADOS`)).toHaveLength(1))
    expect(llamadas(fetchMock, `GET ${RUTA}?pagina=0&estado=AGOTADOS`)).toHaveLength(1)
  })

  it('registra una entrada desde la fila del lote, con motivo y saldo resultante (CCPIP-121)', async () => {
    const fetchMock = servidor(comoAcopio({
      'POST /api/acopio/movimientos': [201, registrado('ENTRADA', 'L-0247', 15, 75, 'RECEPCION')],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')

    await user.click(await screen.findByRole('button', { name: 'Registrar un movimiento del lote L-0247' }))
    const dialogo = within(await screen.findByRole('dialog'))
    expect(dialogo.getByRole('tab', { name: 'Entrada' })).toHaveAttribute('aria-selected', 'true')
    expect(dialogo.getByLabelText('Productor')).toHaveDisplayValue('Asoc. Nii Joi')
    expect(dialogo.getByLabelText('Lote')).toHaveDisplayValue('L-0247 · vence 02/11/2027 · 60 und')
    // El vencido no recibe entradas; el agotado si
    expect(within(dialogo.getByLabelText('Productor')).queryByRole('option', { name: 'C.N. Yarina' })).not.toBeInTheDocument()
    await user.selectOptions(dialogo.getByLabelText('Producto'), 'Aceite de sacha inchi 250 ml')
    expect(dialogo.getByRole('option', { name: 'L-0250 · vence 01/12/2027 · 0 und' })).toBeInTheDocument()
    await user.selectOptions(dialogo.getByLabelText('Lote'), 'L-0247 · vence 02/11/2027 · 60 und')

    // Un solo motivo de entrada en el punto: sin ajuste de conteo (CHANGELOG 30/09)
    expect(dialogo.getByLabelText('Motivo')).toHaveDisplayValue('Recepción del productor')
    expect(within(dialogo.getByLabelText('Motivo')).getAllByRole('option')).toHaveLength(2)
    await user.type(dialogo.getByLabelText('Cantidad'), '15')
    expect(dialogo.getByText('Stock resultante: 75 und')).toBeInTheDocument()
    await user.type(dialogo.getByLabelText('Observación (opcional)'), 'Guía 0012-4481')
    await user.click(dialogo.getByRole('button', { name: 'Registrar entrada' }))

    expect(await screen.findByText('Entrada de 15 und registrada en el lote L-0247')).toBeInTheDocument()
    expect(screen.getByText('El lote queda con 75 und.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const [envio] = llamadas(fetchMock, 'POST /api/acopio/movimientos')
    expect(cuerpo(envio)).toEqual({
      tipo: 'ENTRADA', loteId: 47, cantidad: '15', motivo: 'RECEPCION', observacion: 'Guía 0012-4481', clave: expect.stringMatching(UUID),
    })
    // Recarga el inventario con el nuevo saldo
    await waitFor(() => expect(llamadas(fetchMock, `GET ${RUTA}?pagina=0`)).toHaveLength(2))
  })

  it('una salida no supera el saldo del lote y el rechazo del servidor queda junto a la cantidad (CCPIP-287)', async () => {
    const fetchMock = servidor(comoAcopio({
      'POST /api/acopio/movimientos': [409, { code: 'SALDO_INSUFICIENTE', error: 'El lote L-0231 tiene 30 unidades: no puede salir más de eso.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')

    await user.click(await screen.findByRole('button', { name: 'Registrar movimiento' }))
    const dialogo = within(await screen.findByRole('dialog'))
    await user.click(dialogo.getByRole('tab', { name: 'Salida' }))
    // Una salida solo sale de un lote con saldo, vencido o no
    await user.selectOptions(dialogo.getByLabelText('Productor'), 'Asoc. Nii Joi')
    await user.selectOptions(dialogo.getByLabelText('Producto'), 'Aceite de sacha inchi 250 ml')
    expect(dialogo.queryByRole('option', { name: /L-0250/ })).not.toBeInTheDocument()
    await user.selectOptions(dialogo.getByLabelText('Lote'), 'L-0231 · vence 21/10/2026 · 32 und')
    expect(dialogo.getByLabelText('Motivo')).toHaveDisplayValue('Devolución al productor')

    await user.type(dialogo.getByLabelText('Cantidad'), '33')
    await user.click(dialogo.getByRole('button', { name: 'Registrar salida' }))
    expect(dialogo.getByText('El lote tiene 32 und: no puede salir más')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/acopio/movimientos')).toHaveLength(0)

    // Otro movimiento se adelanto: el servidor rechaza y el lote no cambia
    await user.clear(dialogo.getByLabelText('Cantidad'))
    await user.type(dialogo.getByLabelText('Cantidad'), '31')
    expect(dialogo.getByText('Stock resultante: 1 und')).toBeInTheDocument()
    await user.click(dialogo.getByRole('button', { name: 'Registrar salida' }))
    expect(await dialogo.findByText('El lote L-0231 tiene 30 unidades: no puede salir más de eso.')).toBeInTheDocument()
    expect(cuerpo(llamadas(fetchMock, 'POST /api/acopio/movimientos')[0])).toMatchObject({ tipo: 'SALIDA', loteId: 31, motivo: 'DEVOLUCION_PRODUCTOR' })
  })

  it('valida lote, cantidad y motivo antes de enviar', async () => {
    const fetchMock = servidor(comoAcopio())
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')
    await user.click(await screen.findByRole('button', { name: 'Registrar movimiento' }))
    const dialogo = within(await screen.findByRole('dialog'))
    await user.type(dialogo.getByLabelText('Cantidad'), '1.2345')
    await user.click(dialogo.getByRole('button', { name: 'Registrar entrada' }))
    expect(dialogo.getByText('Elige el lote')).toBeInTheDocument()
    expect(dialogo.getByText('Escribe una cantidad mayor a cero, con hasta 3 decimales')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/acopio/movimientos')).toHaveLength(0)

    await user.click(dialogo.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('reintentar tras una falla de red reenvía la misma clave: el servidor no cuenta dos veces', async () => {
    let intentos = 0
    const fetchMock = servidor(comoAcopio({
      'POST /api/acopio/movimientos': () => {
        intentos += 1
        return intentos === 1 ? new Error('red') : [200, { ...registrado('SALIDA', 'L-0247', 5, 55, 'DEVOLUCION_PRODUCTOR'), repetido: true }]
      },
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')
    await user.click(await screen.findByRole('button', { name: 'Registrar un movimiento del lote L-0247' }))
    const dialogo = within(await screen.findByRole('dialog'))
    await user.click(dialogo.getByRole('tab', { name: 'Salida' }))
    await user.type(dialogo.getByLabelText('Cantidad'), '5')
    await user.click(dialogo.getByRole('button', { name: 'Registrar salida' }))
    expect(await dialogo.findByText('No pudimos registrar el movimiento')).toBeInTheDocument()

    await user.click(dialogo.getByRole('button', { name: 'Registrar salida' }))
    expect(await screen.findByText('Salida de 5 und registrada del lote L-0247')).toBeInTheDocument()
    const [primero, segundo] = llamadas(fetchMock, 'POST /api/acopio/movimientos').map(cuerpo)
    expect(segundo.clave).toBe(primero.clave)
  })

  it('si no puede cargar los lotes del formulario, lo dice', async () => {
    servidor(comoAcopio({ [`GET ${RUTA}/lotes`]: [500, {}] }))
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')
    await user.click(await screen.findByRole('button', { name: 'Registrar movimiento' }))
    expect(await screen.findByText('No pudimos cargar los lotes')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('un error al cargar el inventario se puede reintentar', async () => {
    let veces = 0
    servidor(comoAcopio({ [`GET ${RUTA}?pagina=0`]: () => (++veces === 1 ? [500, {}] : [200, copia(INVENTARIO)]) }))
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')
    expect(await screen.findByText('No pudimos cargar el inventario')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('L-0231')).toBeInTheDocument()
  })

  it('en inglés, sin restos en español', async () => {
    await i18n.changeLanguage('en')
    servidor(comoAcopio())
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')
    expect(await screen.findByRole('heading', { level: 1, name: 'Inventory' })).toBeInTheDocument()
    expect(await screen.findByText('Stock: 94 units · Sellable: 92 units · Not sellable: 2 units')).toBeInTheDocument()
    expect(screen.getByText('Expiring soon')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Record a movement for batch L-0247' }))
    const dialogo = within(await screen.findByRole('dialog'))
    expect(dialogo.getByRole('tab', { name: 'Incoming' })).toBeInTheDocument()
    expect(dialogo.getByLabelText('Reason')).toHaveDisplayValue('Received from producer')
    expect(screen.queryByText(/Registrar|Motivo|Vendible/)).not.toBeInTheDocument()
  })
})

describe('A-03 Movimientos: salidas manuales y registro (CCPIP-287)', () => {
  it('la salida manual se lista como salida con su motivo, y el punto puede registrar desde aquí', async () => {
    const datos = copia(DATOS.movimientos)
    datos.items.unshift({ ...datos.items[0], id: 77, tipo: 'SALIDA', lote: 'L-0247', cantidad: 3, motivo: 'DEVOLUCION_PRODUCTOR', registradoPor: 'Marta Chávez' })
    const fetchMock = servidor(comoAcopio({
      'GET /api/acopio/movimientos?pagina=0': () => [200, copia(datos)],
      'POST /api/acopio/movimientos': [201, registrado('ENTRADA', 'L-0247', 2, 62, 'RECEPCION')],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/movimientos')

    const filas = within(await screen.findByRole('list', { name: 'Movimientos del punto' })).getAllByRole('listitem')
    expect(within(filas[0]).getByText('Salida')).toBeInTheDocument()
    expect(within(filas[0]).getByText(/L-0247 · Devolución al productor$/)).toBeInTheDocument()
    expect(within(filas[0]).getByText('−3')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Marta Chávez')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Registrar movimiento' }))
    const dialogo = within(await screen.findByRole('dialog'))
    await user.selectOptions(dialogo.getByLabelText('Productor'), 'Asoc. Nii Joi')
    await user.selectOptions(dialogo.getByLabelText('Producto'), 'Aceite de sacha inchi 250 ml')
    await user.selectOptions(dialogo.getByLabelText('Lote'), 'L-0247 · vence 02/11/2027 · 60 und')
    await user.type(dialogo.getByLabelText('Cantidad'), '2')
    await user.click(dialogo.getByRole('button', { name: 'Registrar entrada' }))
    expect(await screen.findByText('Entrada de 2 und registrada en el lote L-0247')).toBeInTheDocument()
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/acopio/movimientos?pagina=0')).toHaveLength(2))
  })
})
