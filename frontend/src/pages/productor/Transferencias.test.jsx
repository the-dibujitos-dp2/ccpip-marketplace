import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

// Armado segun TransferenciaModelos del backend (CCPIP-73, 146, 164, 362, 277)
const ALMACEN = { id: 11, nombre: 'Almacén Comunidad Shipibo', tipo: 'ALMACEN_PRODUCTOR', departamento: 'Ucayali', provincia: 'Coronel Portillo', distrito: 'Yarinacocha', direccion: 'Jr. Los Shipibos 120' }
const LIMA = { id: 1, nombre: 'Centro Nacional Lima', tipo: 'PUNTO_ACOPIO', departamento: 'Lima', provincia: 'Lima', distrito: 'Cercado de Lima', direccion: 'Av. Abancay 210' }
const UCAYALI = { id: 4, nombre: 'Centro Regional Ucayali', tipo: 'PUNTO_ACOPIO', departamento: 'Ucayali', provincia: 'Coronel Portillo', distrito: 'Callería' }
const UBICACIONES = { almacen: ALMACEN, puntos: [LIMA, UCAYALI] }

const sinDireccion = ({ direccion: _d, ...u }) => u

function transferencia(id, estado, extra = {}) {
  return {
    id,
    codigo: `TR-${String(id).padStart(4, '0')}`,
    tipo: 'ALMACEN_A_PUNTO',
    estado,
    productor: 'Comunidad Shipibo Konibo de Yarinacocha',
    origen: sinDireccion(ALMACEN),
    destino: sinDireccion(LIMA),
    cantidad: 40,
    items: [{ id: 100 + id, productoId: 9, producto: 'Aceite de sacha inchi 250 ml', unidad: 'ML', loteOrigenId: 31, loteOrigen: 'L-0231', fechaProduccion: '2026-09-10', fechaVencimiento: '2027-03-15', cantidad: 40 }],
    creadaEn: '2026-10-16T15:00:00Z',
    actualizadaEn: '2026-10-16T15:00:00Z',
    ...extra,
  }
}

const EN_CURSO = {
  items: [
    transferencia(31, 'INICIADA'),
    transferencia(30, 'PREPARADA'),
    transferencia(29, 'DESPACHADA'),
    transferencia(28, 'SOLICITADA', { tipo: 'ENTRE_PUNTOS', origen: sinDireccion(LIMA), destino: sinDireccion(UCAYALI), cantidad: 5 }),
  ],
  enCurso: 4,
  historial: 1,
}
const HISTORIAL = { items: [transferencia(20, 'RECIBIDA')], enCurso: 4, historial: 1 }

const INVENTARIO = {
  resumen: { enAlmacen: 64, productosEnAlmacen: 2, enPuntosAcopio: 9, porPunto: [], lotesPorVencer: 0, lotesVencidos: 1 },
  diasAviso: 15,
  ubicaciones: [ALMACEN, LIMA],
  productos: [
    {
      id: 9,
      nombre: 'Aceite de sacha inchi 250 ml',
      total: 40,
      porUbicacion: [],
      lotes: [
        { id: 31, codigo: 'L-0231', ubicacionId: 11, cantidad: 40, fechaProduccion: '2026-09-10', fechaVencimiento: '2027-03-15', estado: 'DISPONIBLE' },
        { id: 33, codigo: 'L-0199', ubicacionId: 11, cantidad: 3, fechaVencimiento: '2026-10-01', estado: 'VENCIDO' },
      ],
    },
    {
      id: 10,
      nombre: 'Harina de aguaje 500 g',
      total: 33,
      porUbicacion: [],
      lotes: [
        { id: 41, codigo: 'HA-01', ubicacionId: 11, cantidad: 24, fechaVencimiento: '2026-10-25', estado: 'POR_VENCER' },
        { id: 42, codigo: 'HA-02', ubicacionId: 1, cantidad: 9, estado: 'DISPONIBLE' },
      ],
    },
  ],
}

function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/transferencias?vista=EN_CURSO': () => [200, copia(EN_CURSO)],
    'GET /api/productor/transferencias?vista=HISTORIAL': () => [200, copia(HISTORIAL)],
    'GET /api/productor/transferencias/ubicaciones': () => [200, copia(UBICACIONES)],
    'GET /api/productor/inventario': () => [200, copia(INVENTARIO)],
    ...rutas,
  })
}

const cuerpo = (llamada) => JSON.parse(llamada[1].body)

describe('CCPIP-73, 164, 362 y 277 Mis transferencias (PW-17)', () => {
  afterEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('se llega desde el menu y muestra las en curso con su estado y los contadores', async () => {
    productor({ 'GET /api/productor/panel': () => [200, copia(DATOS.panelProductor)] })
    const user = userEvent.setup()
    abrirEn('/productor')
    const menu = await screen.findByRole('link', { name: 'Transferencias' })
    const enlaces = within(menu.closest('nav')).getAllByRole('link').map((a) => a.textContent)
    expect(enlaces.indexOf('Transferencias')).toBe(enlaces.indexOf('Lotes') + 1)
    await user.click(menu)
    expect(window.location.pathname).toBe('/productor/transferencias')

    expect(await screen.findByRole('heading', { name: 'Mis transferencias' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'En curso (4)', selected: true })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Historial (1)' })).toBeInTheDocument()
    const lista = screen.getByRole('list', { name: 'Lista de transferencias' })
    const filas = within(lista).getAllByRole('listitem')
    expect(filas).toHaveLength(4)
    expect(within(filas[0]).getByText('TR-0031')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Mi almacén → Centro Nacional Lima')).toBeInTheDocument()
    expect(within(filas[0]).getByText('1 lote(s) · 40 und')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Iniciada')).toBeInTheDocument()
    expect(within(filas[0]).getByRole('button', { name: 'Registrar la preparación de TR-0031' })).toBeInTheDocument()
    expect(within(filas[1]).getByRole('button', { name: 'Registrar el despacho de TR-0030' })).toBeInTheDocument()
    // En transito ya no se prepara ni se despacha: solo el punto lo recibe
    expect(within(filas[2]).getByText('En tránsito')).toBeInTheDocument()
    expect(within(filas[2]).queryByRole('button', { name: /Registrar/ })).not.toBeInTheDocument()
    expect(within(filas[2]).getByText(/no se venden hasta que el punto de destino/)).toBeInTheDocument()
    // C8: la solicitud entre puntos no avanza
    expect(within(filas[3]).getByText('Solicitada')).toBeInTheDocument()
    expect(within(filas[3]).getByText('Centro Nacional Lima → Centro Regional Ucayali')).toBeInTheDocument()
    expect(within(filas[3]).getByText(/Aún no está definido quién aprueba/)).toBeInTheDocument()
    expect(within(filas[3]).queryByRole('button', { name: /Registrar/ })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Historial (1)' }))
    expect(await screen.findByText('TR-0020')).toBeInTheDocument()
    expect(screen.getByText('Recibida')).toBeInTheDocument()
  })

  it('muestra el detalle con producto, lote, cantidad e historial', async () => {
    const detalle = transferencia(31, 'INICIADA', {
      observacion: 'Para la feria',
      historial: [{ estado: 'INICIADA', fecha: '2026-10-16T15:00:00Z', usuario: 'Comunidad Shipibo' }],
    })
    productor({ 'GET /api/productor/transferencias/31': [200, detalle] })
    const user = userEvent.setup()
    abrirEn('/productor/transferencias')
    const fila = (await screen.findByText('TR-0031')).closest('li')
    await user.click(within(fila).getByRole('button', { name: 'Ver detalle' }))
    const tabla = await within(fila).findByRole('table', { name: 'Lotes de la transferencia TR-0031' })
    expect(within(tabla).getByText('L-0231')).toBeInTheDocument()
    expect(within(tabla).getByText('15/03/2027')).toBeInTheDocument()
    expect(within(fila).getByText('Mi almacén · Ucayali, Coronel Portillo, Yarinacocha')).toBeInTheDocument()
    expect(within(fila).getByText('Observación: Para la feria')).toBeInTheDocument()
    const historial = within(fila).getByRole('region', { name: 'Historial' })
    expect(within(historial).getByText('Comunidad Shipibo')).toBeInTheDocument()
    await user.click(within(fila).getByRole('button', { name: 'Ocultar detalle' }))
    expect(within(fila).queryByRole('table')).not.toBeInTheDocument()
  })

  it('mientras trae el detalle, "Ver detalle" muestra el aro de carga (CCPIP-413)', async () => {
    let responder
    productor({ 'GET /api/productor/transferencias/31': () => new Promise((r) => { responder = r }) })
    const user = userEvent.setup()
    abrirEn('/productor/transferencias')
    const fila = (await screen.findByText('TR-0031')).closest('li')
    const ver = within(fila).getByRole('button', { name: 'Ver detalle' })
    await user.click(ver)

    expect(ver).toBeDisabled()
    expect(ver).toHaveAttribute('aria-busy', 'true')
    expect(ver.querySelector('.boton__giro')).not.toBeNull()
    responder([200, transferencia(31, 'INICIADA', { historial: [] })])
    expect(await within(fila).findByRole('button', { name: 'Ocultar detalle' })).toBeEnabled()
  })

  it('CCPIP-164: registra la preparacion tras confirmar', async () => {
    const fetchMock = productor({
      'POST /api/productor/transferencias/31/preparar': () => [200, transferencia(31, 'PREPARADA')],
    })
    const user = userEvent.setup()
    abrirEn('/productor/transferencias')
    await user.click(await screen.findByRole('button', { name: 'Registrar la preparación de TR-0031' }))
    const dialogo = screen.getByRole('dialog', { hidden: true })
    expect(within(dialogo).getByText(/Se separarán 40 und de los lotes de origen para llevarlas a Centro Nacional Lima/)).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('button', { name: 'Registrar preparación', hidden: true }))
    expect(await screen.findByText('La transferencia TR-0031 está preparada')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/transferencias/31/preparar')).toHaveLength(1)
  })

  it('CCPIP-362: un despacho rechazado muestra el motivo del servidor', async () => {
    const fetchMock = productor({
      'POST /api/productor/transferencias/30/despachar': [409, { code: 'TRANSICION_INVALIDA', error: 'Solo se despacha una transferencia preparada.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/transferencias')
    await user.click(await screen.findByRole('button', { name: 'Registrar el despacho de TR-0030' }))
    const dialogo = screen.getByRole('dialog', { hidden: true })
    expect(within(dialogo).getByText(/No se vuelven a descontar/)).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('button', { name: 'Registrar despacho', hidden: true }))
    expect(await screen.findByText('No pudimos registrar el despacho')).toBeInTheDocument()
    expect(screen.getByText('Solo se despacha una transferencia preparada.')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/transferencias/30/despachar')).toHaveLength(1)
  })

  it('cancelar la confirmacion no envia nada', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/transferencias')
    await user.click(await screen.findByRole('button', { name: 'Registrar la preparación de TR-0031' }))
    await user.click(within(screen.getByRole('dialog', { hidden: true })).getByRole('button', { name: 'Cancelar', hidden: true }))
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/preparar'))).toBe(false)
  })

  it('sin transferencias muestra el estado vacio, y un error de carga permite reintentar', async () => {
    let falla = true
    productor({
      'GET /api/productor/transferencias?vista=EN_CURSO': () => (falla ? new Error('red') : [200, { items: [], enCurso: 0, historial: 0 }]),
    })
    const user = userEvent.setup()
    abrirEn('/productor/transferencias')
    expect(await screen.findByText('No pudimos cargar tus transferencias')).toBeInTheDocument()
    falla = false
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('No tienes transferencias en curso')).toBeInTheDocument()
  })
})

describe('CCPIP-73, 277 y 146 Nueva transferencia (PW-18)', () => {
  afterEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('CCPIP-73: inicia una transferencia de su almacen a un punto con la ubicacion de cada lugar', async () => {
    const fetchMock = productor({
      'POST /api/productor/transferencias': () => [201, transferencia(32, 'INICIADA')],
    })
    const user = userEvent.setup()
    abrirEn('/productor/transferencias')
    await user.click(await screen.findByRole('link', { name: 'Nueva transferencia' }))
    expect(window.location.pathname).toBe('/productor/transferencias/nueva')
    expect(await screen.findByRole('heading', { name: 'Nueva transferencia' })).toBeInTheDocument()
    expect(await screen.findByRole('radio', { name: /De mi almacén a un punto de acopio/ })).toBeChecked()

    // CCPIP-146: su almacen y el destino con la ubicacion registrada, sin rutas
    const origen = screen.getByLabelText('Ubicación del origen')
    expect(within(origen).getByText('Mi almacén')).toBeInTheDocument()
    expect(within(origen).getByText('Yarinacocha, Coronel Portillo, Ucayali')).toBeInTheDocument()
    expect(within(origen).getByText('Jr. Los Shipibos 120')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Destino'), 'Centro Nacional Lima')
    const destino = screen.getByLabelText('Ubicación del destino')
    expect(within(destino).getByText('Cercado de Lima, Lima, Lima')).toBeInTheDocument()
    expect(within(destino).getByText('Av. Abancay 210')).toBeInTheDocument()
    expect(screen.getByText(/no se calculan rutas/)).toBeInTheDocument()

    // Solo los lotes vigentes de su almacen
    expect(screen.getByLabelText('Cantidad del lote L-0231')).toBeInTheDocument()
    expect(screen.getByLabelText('Cantidad del lote HA-01')).toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad del lote L-0199')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad del lote HA-02')).not.toBeInTheDocument()
    expect(screen.getByText('Lote L-0231 · vence 15/03/2027 · 40 und disponibles')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Cantidad del lote L-0231'), '12.5')
    await user.type(screen.getByLabelText('Observación (opcional)'), '  Para la feria ')
    await user.click(screen.getByRole('button', { name: 'Iniciar transferencia' }))

    await waitFor(() => expect(window.location.pathname).toBe('/productor/transferencias'))
    expect(await screen.findByText('Registramos la transferencia TR-0032')).toBeInTheDocument()
    const [envio] = llamadas(fetchMock, 'POST /api/productor/transferencias')
    expect(cuerpo(envio)).toEqual({ tipo: 'ALMACEN_A_PUNTO', destinoId: 1, items: [{ loteId: 31, cantidad: '12.5' }], observacion: 'Para la feria' })
  })

  it('valida destino, lotes y cantidades antes de enviar', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/transferencias/nueva')
    await user.click(await screen.findByRole('button', { name: 'Iniciar transferencia' }))
    expect(screen.getByText('Elige el destino')).toBeInTheDocument()
    expect(screen.getByText('Escribe la cantidad de al menos un lote')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Cantidad del lote L-0231'), '41')
    await user.type(screen.getByLabelText('Cantidad del lote HA-01'), '0')
    await user.click(screen.getByRole('button', { name: 'Iniciar transferencia' }))
    expect(screen.getByText('Solo hay 40 und disponibles')).toBeInTheDocument()
    expect(screen.getByText('Escribe una cantidad mayor a cero, con hasta 3 decimales')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/transferencias')).toHaveLength(0)
  })

  it('muestra el rechazo del servidor, por ejemplo si el saldo ya no alcanza', async () => {
    productor({
      'POST /api/productor/transferencias': [409, { code: 'SALDO_INSUFICIENTE', error: 'El lote L-0231 solo tiene 10 disponibles.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/transferencias/nueva')
    await user.selectOptions(await screen.findByLabelText('Destino'), 'Centro Regional Ucayali')
    await user.type(screen.getByLabelText('Cantidad del lote L-0231'), '20')
    await user.click(screen.getByRole('button', { name: 'Iniciar transferencia' }))
    expect(await screen.findByText('No pudimos registrar la transferencia')).toBeInTheDocument()
    expect(screen.getByText('El lote L-0231 solo tiene 10 disponibles.')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/productor/transferencias/nueva')
  })

  it('CCPIP-277: solicita una transferencia entre puntos con los lotes del punto de origen', async () => {
    const fetchMock = productor({
      'POST /api/productor/transferencias': () => [201, transferencia(33, 'SOLICITADA', { tipo: 'ENTRE_PUNTOS' })],
    })
    const user = userEvent.setup()
    abrirEn('/productor/transferencias/nueva')
    await user.click(await screen.findByRole('radio', { name: /Entre puntos de acopio/ }))
    expect(screen.getByText('Se registrará como solicitud')).toBeInTheDocument()
    expect(screen.getByText('Elige el origen para ver sus lotes.')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Origen'), 'Centro Nacional Lima')
    // El destino no ofrece el mismo punto
    const destino = screen.getByLabelText('Destino')
    expect(within(destino).queryByRole('option', { name: 'Centro Nacional Lima' })).not.toBeInTheDocument()
    await user.selectOptions(destino, 'Centro Regional Ucayali')
    expect(screen.queryByLabelText('Cantidad del lote L-0231')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Cantidad del lote HA-02'), '9')
    await user.click(screen.getByRole('button', { name: 'Solicitar transferencia' }))
    await waitFor(() => expect(window.location.pathname).toBe('/productor/transferencias'))
    const [envio] = llamadas(fetchMock, 'POST /api/productor/transferencias')
    expect(cuerpo(envio)).toEqual({ tipo: 'ENTRE_PUNTOS', origenId: 1, destinoId: 4, items: [{ loteId: 42, cantidad: '9' }] })
  })

  it('un punto sin lotes vigentes lo dice, y sin almacen registrado avisa', async () => {
    productor({ 'GET /api/productor/transferencias/ubicaciones': [200, { puntos: [LIMA, UCAYALI] }] })
    const user = userEvent.setup()
    abrirEn('/productor/transferencias/nueva')
    expect(await screen.findByText('Aún no tienes un almacén registrado.')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: /Entre puntos de acopio/ }))
    await user.selectOptions(screen.getByLabelText('Origen'), 'Centro Regional Ucayali')
    expect(screen.getByText('No hay lotes vigentes en Centro Regional Ucayali')).toBeInTheDocument()
  })

  it('se ve en ingles', async () => {
    productor()
    await i18n.changeLanguage('en')
    abrirEn('/productor/transferencias/nueva')
    expect(await screen.findByRole('heading', { name: 'New transfer' })).toBeInTheDocument()
    expect(await screen.findByRole('radio', { name: /From my warehouse to a collection point/ })).toBeInTheDocument()
    expect(screen.getByText('Batch L-0231 · expires 15/03/2027 · 40 units available')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start transfer' })).toBeInTheDocument()
  })
})
