import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, llamadas, servidor, SESION_ACOPIO } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

// Armado segun TransferenciaModelos.TransferenciasPunto del backend (CCPIP-278)
const ALMACEN = { id: 11, nombre: 'Almacén Comunidad Shipibo', tipo: 'ALMACEN_PRODUCTOR', departamento: 'Ucayali', provincia: 'Coronel Portillo', distrito: 'Yarinacocha' }
const LIMA = { id: 1, nombre: 'Centro Nacional Lima', tipo: 'PUNTO_ACOPIO', departamento: 'Lima', provincia: 'Lima', distrito: 'Cercado de Lima' }
const UCAYALI = { id: 4, nombre: 'Centro Regional Ucayali', tipo: 'PUNTO_ACOPIO', departamento: 'Ucayali', provincia: 'Coronel Portillo', distrito: 'Callería' }

function transferencia(id, estado, extra = {}) {
  return {
    id,
    codigo: `TR-${String(id).padStart(4, '0')}`,
    tipo: 'ALMACEN_A_PUNTO',
    estado,
    productor: 'Comunidad Shipibo Konibo de Yarinacocha',
    origen: ALMACEN,
    destino: LIMA,
    cantidad: 40,
    items: [{ id: 100 + id, productoId: 9, producto: 'Aceite de sacha inchi 250 ml', unidad: 'ML', loteOrigenId: 31, loteOrigen: 'L-0231', fechaVencimiento: '2027-03-15', cantidad: 40 }],
    creadaEn: '2026-10-16T15:00:00Z',
    actualizadaEn: '2026-10-16T15:00:00Z',
    sentido: 'ENTRANTE',
    ...extra,
  }
}

const EN_TRANSITO = transferencia(29, 'DESPACHADA')
const DATOS = {
  ubicacion: 'Centro Nacional Lima',
  porRecibir: [EN_TRANSITO],
  items: [
    EN_TRANSITO,
    transferencia(28, 'SOLICITADA', { tipo: 'ENTRE_PUNTOS', origen: LIMA, destino: UCAYALI, cantidad: 5, sentido: 'SALIENTE' }),
    transferencia(20, 'RECIBIDA', { items: [{ ...EN_TRANSITO.items[0], id: 120, loteDestino: 'L-0231-TR-0020' }] }),
  ],
}

function acopio(rutas = {}) {
  return servidor({
    'GET /api/auth/me': [200, SESION_ACOPIO],
    'GET /api/acopio/pedidos': [200, { ubicacion: 'Centro Nacional Lima', columnas: [] }],
    'GET /api/acopio/transferencias': () => [200, copia(DATOS)],
    ...rutas,
  })
}

describe('CCPIP-278 Transferencias del punto de acopio (A-03)', () => {
  afterEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('muestra lo que espera recepcion y todo lo que entra o sale del punto', async () => {
    acopio()
    abrirEn('/acopio/transferencias')
    expect(await screen.findByRole('heading', { name: 'Transferencias por recibir (1)' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Transferencias' })).toHaveAttribute('aria-current', 'page')
    const porRecibir = screen.getByRole('list', { name: 'Transferencias por recibir' })
    expect(within(porRecibir).getByText('TR-0029')).toBeInTheDocument()
    expect(within(porRecibir).getByText('Comunidad Shipibo Konibo de Yarinacocha')).toBeInTheDocument()
    expect(within(porRecibir).getByText('Mi almacén → Centro Nacional Lima')).toBeInTheDocument()
    expect(within(porRecibir).getByText('En tránsito')).toBeInTheDocument()
    expect(within(porRecibir).getByRole('button', { name: 'Registrar la recepción de TR-0029' })).toBeInTheDocument()

    const todas = screen.getByRole('list', { name: 'Todas las transferencias' })
    const filas = within(todas).getAllByRole('listitem')
    expect(filas).toHaveLength(3)
    expect(within(filas[1]).getByText('Saliente')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Solicitada')).toBeInTheDocument()
    expect(within(filas[1]).getByText(/Aún no está definido quién aprueba/)).toBeInTheDocument()
    // En la lista general no se recibe: solo desde "por recibir"
    expect(within(todas).queryByRole('button', { name: /Registrar la recepción/ })).not.toBeInTheDocument()
  })

  it('el detalle muestra origen, destino, lote, cantidad e historial con quien y cuando', async () => {
    acopio({
      'GET /api/acopio/transferencias/20': [200, {
        ...DATOS.items[2],
        historial: [
          { estado: 'INICIADA', fecha: '2026-10-14T15:00:00Z', usuario: 'Comunidad Shipibo' },
          { estado: 'PREPARADA', fecha: '2026-10-15T15:00:00Z', usuario: 'Comunidad Shipibo' },
          { estado: 'DESPACHADA', fecha: '2026-10-15T16:00:00Z', usuario: 'Comunidad Shipibo' },
          { estado: 'RECIBIDA', fecha: '2026-10-16T15:00:00Z', usuario: 'Admin Acopio' },
        ],
      }],
    })
    const user = userEvent.setup()
    abrirEn('/acopio/transferencias')
    const fila = (await screen.findByText('TR-0020')).closest('li')
    await user.click(within(fila).getByRole('button', { name: 'Ver detalle' }))
    const tabla = await within(fila).findByRole('table')
    expect(within(tabla).getByText('L-0231')).toBeInTheDocument()
    expect(within(tabla).getByText('L-0231-TR-0020')).toBeInTheDocument()
    expect(within(tabla).getByText('40 und')).toBeInTheDocument()
    expect(within(fila).getByText('Centro Nacional Lima · Lima, Lima, Cercado de Lima')).toBeInTheDocument()
    const historial = within(fila).getByRole('region', { name: 'Historial' })
    expect(within(historial).getAllByRole('listitem')).toHaveLength(4)
    expect(within(historial).getByText('Admin Acopio')).toBeInTheDocument()
    expect(within(historial).getByText('16/10 10:00')).toBeInTheDocument()
  })

  it('registra la recepcion tras confirmar y la saca de por recibir', async () => {
    const fetchMock = acopio({
      'POST /api/acopio/transferencias/29/recibir': () => [200, transferencia(29, 'RECIBIDA', { sentido: undefined })],
    })
    const user = userEvent.setup()
    abrirEn('/acopio/transferencias')
    await user.click(await screen.findByRole('button', { name: 'Registrar la recepción de TR-0029' }))
    const dialogo = screen.getByRole('dialog', { hidden: true })
    expect(within(dialogo).getByText(/Llegan 40 und de Comunidad Shipibo Konibo de Yarinacocha desde Mi almacén/)).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('button', { name: 'Registrar recepción', hidden: true }))
    expect(await screen.findByText('Recibiste la transferencia TR-0029')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Transferencias por recibir (0)' })).toBeInTheDocument()
    expect(screen.getByText('No hay transferencias en tránsito hacia tu punto')).toBeInTheDocument()
    const todas = screen.getByRole('list', { name: 'Todas las transferencias' })
    expect(within(within(todas).getByText('TR-0029').closest('li')).getByText('Recibida')).toBeInTheDocument()
    expect(within(within(todas).getByText('TR-0029').closest('li')).getByText('Entrante')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/acopio/transferencias/29/recibir')).toHaveLength(1)
  })

  it('una recepcion rechazada muestra el motivo', async () => {
    acopio({
      'POST /api/acopio/transferencias/29/recibir': [409, { code: 'TRANSICION_INVALIDA', error: 'Solo se recibe una transferencia despachada.' }],
    })
    const user = userEvent.setup()
    abrirEn('/acopio/transferencias')
    await user.click(await screen.findByRole('button', { name: 'Registrar la recepción de TR-0029' }))
    await user.click(within(screen.getByRole('dialog', { hidden: true })).getByRole('button', { name: 'Registrar recepción', hidden: true }))
    expect(await screen.findByText('No pudimos registrar la recepción')).toBeInTheDocument()
    expect(screen.getByText('Solo se recibe una transferencia despachada.')).toBeInTheDocument()
  })

  it('sin transferencias muestra los estados vacios, y en ingles', async () => {
    acopio({ 'GET /api/acopio/transferencias': [200, { ubicacion: 'Centro Nacional Lima', porRecibir: [], items: [] }] })
    await i18n.changeLanguage('en')
    abrirEn('/acopio/transferencias')
    expect(await screen.findByRole('heading', { name: 'Transfers to receive (0)' })).toBeInTheDocument()
    expect(screen.getByText('No transfers in transit to your point')).toBeInTheDocument()
    expect(screen.getByText('No transfers yet')).toBeInTheDocument()
  })

  it('un error de carga se muestra con su motivo traducido', async () => {
    acopio({ 'GET /api/acopio/transferencias': [409, { code: 'ACOPIO_SIN_ASIGNAR', error: 'Tu cuenta aún no tiene un punto de acopio asignado.' }] })
    abrirEn('/acopio/transferencias')
    expect(await screen.findByText('No pudimos cargar las transferencias')).toBeInTheDocument()
    expect(screen.getByText('Tu cuenta aún no tiene un punto de acopio asignado.')).toBeInTheDocument()
  })
})

describe('Buscar y filtrar las transferencias del punto (CCPIP-417)', () => {
  const codigos = (nombre) => within(screen.getByRole('list', { name: nombre })).getAllByText(/^TR-\d{4}$/).map((c) => c.textContent)

  it('busca por código o producto y filtra por sentido y estado en ambas listas; limpiar vuelve a todo', async () => {
    acopio()
    const user = userEvent.setup()
    abrirEn('/acopio/transferencias')
    await screen.findByRole('list', { name: 'Todas las transferencias' })
    expect(codigos('Todas las transferencias')).toEqual(['TR-0029', 'TR-0028', 'TR-0020'])

    await user.type(screen.getByRole('searchbox', { name: 'Buscar por código, productor, producto u origen y destino' }), 'tr-0028')
    expect(codigos('Todas las transferencias')).toEqual(['TR-0028'])
    // La que esta por recibir no coincide: esa lista lo dice en vez de quedar en blanco
    expect(screen.queryByRole('list', { name: 'Transferencias por recibir' })).not.toBeInTheDocument()
    expect(screen.getByText('Nada coincide con la búsqueda y los filtros')).toBeInTheDocument()

    await user.clear(screen.getByRole('searchbox'))
    await user.type(screen.getByRole('searchbox'), 'SACHA INCHI')
    await user.selectOptions(screen.getByLabelText('Sentido'), 'Entrante')
    await user.selectOptions(screen.getByLabelText('Estado'), 'RECIBIDA')
    expect(codigos('Todas las transferencias')).toEqual(['TR-0020'])

    await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }))
    expect(codigos('Todas las transferencias')).toEqual(['TR-0029', 'TR-0028', 'TR-0020'])
    expect(codigos('Transferencias por recibir')).toEqual(['TR-0029'])
    expect(screen.getByRole('searchbox')).toHaveValue('')
    expect(screen.queryByRole('button', { name: 'Limpiar filtros' })).not.toBeInTheDocument()
  })
})
