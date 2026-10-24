import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_ACOPIO, SESION_PRODUCTOR, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const cuerpo = (fetchMock, clave, i = 0) => JSON.parse(llamadas(fetchMock, clave)[i][1].body)

// MermaModelos.Causa: las activas del catalogo
const CAUSAS = [
  { id: 1, nombre: 'Vencimiento', nombreEn: 'Expiry', requiereDetalle: false },
  { id: 2, nombre: 'Daño', nombreEn: 'Damage', requiereDetalle: false },
  { id: 3, nombre: 'Accidente', nombreEn: 'Accident', requiereDetalle: false },
  { id: 6, nombre: 'Otra', nombreEn: 'Other', requiereDetalle: true },
]

const fila = (l) => ({ productorId: 1, productor: 'C.N. Puerto Alegre', ubicacionId: 1, ubicacion: 'Centro Nacional Lima', ...l })

const INVENTARIO = {
  ubicacion: 'Centro Nacional Lima',
  diasAviso: 15,
  resumen: { stock: 9, vendible: 9, noVendible: 0, lotes: 2 },
  productores: [{ id: 1, nombre: 'C.N. Puerto Alegre' }],
  items: [
    fila({ id: 40, codigo: 'L-0240', productoId: 8, producto: 'Harina de aguaje 500 g', fechaVencimiento: '2027-01-20', entradas: 12, salidas: 3, stock: 9, estado: 'DISPONIBLE', vendible: true }),
    fila({ id: 41, codigo: 'L-0241', productoId: 8, producto: 'Harina de aguaje 500 g', fechaVencimiento: '2027-01-20', entradas: 5, salidas: 5, stock: 0, estado: 'AGOTADO', vendible: false }),
  ],
  pagina: 0,
  tamano: 20,
  total: 2,
  totalPaginas: 1,
}

function merma(m) {
  return {
    id: 900, registradoEn: '2026-11-15T15:00:00Z', productoId: 8, producto: 'Harina de aguaje', lote: 'L-0240',
    ubicacion: 'Centro Nacional Lima', cantidad: 5, motivo: 'Daño', motivoEn: 'Damage', automatica: false,
    registradoPor: 'Admin Acopio', productorId: 1, productor: 'C.N. Puerto Alegre', ...m,
  }
}

const MARCADA = { merma: merma({}), saldo: 4, repetida: false }

function acopio(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_ACOPIO],
    'GET /api/acopio/inventario': () => [200, copia(INVENTARIO)],
    'GET /api/merma/causas': [200, CAUSAS],
    'POST /api/acopio/merma': [201, MARCADA],
    ...rutas,
  })
}

describe('A-04 y A-07 Marcar como merma (CCPIP-76)', () => {
  afterEach(() => i18n.changeLanguage('es'))

  it('desde el lote del inventario, con la cantidad, la causa y la confirmación expresa', async () => {
    const fetchMock = acopio()
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')

    // Solo un lote con saldo se puede marcar
    expect(await screen.findByRole('button', { name: 'Marcar como merma el lote L-0240' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Marcar como merma el lote L-0241' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Marcar como merma el lote L-0240' }))

    const dialogo = await screen.findByRole('dialog', { name: 'Marcar como merma' })
    expect(within(dialogo).getByText('Harina de aguaje 500 g · L-0240')).toBeInTheDocument()
    expect(within(dialogo).getByText('C.N. Puerto Alegre · stock actual 9 und')).toBeInTheDocument()
    expect(await within(dialogo).findByRole('radio', { name: 'Daño' })).toBeInTheDocument()
    expect(within(dialogo).getByRole('radio', { name: 'Otra (especificar)' })).toBeInTheDocument()

    // Nunca mas que el stock del lote
    const cantidad = within(dialogo).getByRole('textbox', { name: 'Cantidad' })
    await user.type(cantidad, '12')
    await user.click(within(dialogo).getByRole('button', { name: 'Continuar' }))
    expect(within(dialogo).getByText('No puede ser mayor que el stock del lote (9 und).')).toBeInTheDocument()
    expect(within(dialogo).getByText('Elige la causa.')).toBeInTheDocument()

    await user.clear(cantidad)
    await user.type(cantidad, '5')
    expect(within(dialogo).getByText('Stock resultante: 4 und')).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('radio', { name: 'Daño' }))
    await user.click(within(dialogo).getByRole('button', { name: 'Continuar' }))

    // A-07: el resumen avisa que no se puede revertir; nada se envia antes de confirmar
    const confirmar = await screen.findByRole('dialog', { name: 'Confirmar merma' })
    expect(within(confirmar).getByText(/Vas a marcar 5 und de Harina de aguaje 500 g \(L-0240 · C\.N\. Puerto Alegre\) como merma por Daño\. El stock del lote pasará de 9 a 4 und/)).toBeInTheDocument()
    expect(within(confirmar).getByText('Esta acción no podrá revertirse.')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/acopio/merma')).toHaveLength(0)

    await user.click(within(confirmar).getByRole('button', { name: 'Confirmar merma' }))
    expect(await screen.findByText('Merma registrada: 5 und del lote L-0240')).toBeInTheDocument()
    const enviado = cuerpo(fetchMock, 'POST /api/acopio/merma')
    expect(enviado).toMatchObject({ loteId: 40, cantidad: '5', motivoId: 2, detalle: null, confirmada: true })
    expect(enviado.clave).toMatch(/^[0-9a-f-]{36}$/)
    // El inventario se vuelve a pedir con el nuevo saldo
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/acopio/inventario?pagina=0').length).toBeGreaterThanOrEqual(2))
  })

  it('«Otra» pide describir la causa y cancelar en el resumen vuelve al formulario', async () => {
    const fetchMock = acopio()
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')
    await user.click(await screen.findByRole('button', { name: 'Marcar como merma el lote L-0240' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Marcar como merma' })

    await user.type(within(dialogo).getByRole('textbox', { name: 'Cantidad' }), '1.5')
    await user.click(await within(dialogo).findByRole('radio', { name: 'Otra (especificar)' }))
    await user.click(within(dialogo).getByRole('button', { name: 'Continuar' }))
    expect(within(dialogo).getByText('Describe la causa de la merma.')).toBeInTheDocument()

    await user.type(within(dialogo).getByRole('textbox', { name: 'Describe la causa' }), 'Roedores')
    await user.click(within(dialogo).getByRole('button', { name: 'Continuar' }))
    const confirmar = await screen.findByRole('dialog', { name: 'Confirmar merma' })
    await user.click(within(confirmar).getByRole('button', { name: 'Cancelar' }))
    expect(await screen.findByRole('dialog', { name: 'Marcar como merma' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Cantidad' })).toHaveValue('1.5')

    await user.click(screen.getByRole('button', { name: 'Continuar' }))
    await user.click(await screen.findByRole('button', { name: 'Confirmar merma' }))
    await waitFor(() => expect(llamadas(fetchMock, 'POST /api/acopio/merma')).toHaveLength(1))
    expect(cuerpo(fetchMock, 'POST /api/acopio/merma')).toMatchObject({ cantidad: '1.5', motivoId: 6, detalle: 'Roedores' })
  })

  it('si el servidor la rechaza, vuelve al formulario con el motivo', async () => {
    acopio({ 'POST /api/acopio/merma': [409, { code: 'SALDO_INSUFICIENTE', error: 'El lote L-0240 tiene 3 unidades: la merma no puede ser mayor.' }] })
    const user = userEvent.setup()
    abrirEn('/acopio/inventario')
    await user.click(await screen.findByRole('button', { name: 'Marcar como merma el lote L-0240' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Marcar como merma' })
    await user.type(within(dialogo).getByRole('textbox', { name: 'Cantidad' }), '5')
    await user.click(await within(dialogo).findByRole('radio', { name: 'Accidente' }))
    await user.click(within(dialogo).getByRole('button', { name: 'Continuar' }))
    await user.click(await screen.findByRole('button', { name: 'Confirmar merma' }))

    expect(await screen.findByText('El lote L-0240 tiene 3 unidades: la merma no puede ser mayor.')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'Marcar como merma' })).toBeInTheDocument()
  })
})

describe('A-04 Merma del punto y del productor (CCPIP-75, CCPIP-76)', () => {
  it('el punto ve su merma manual y la automática por vencimiento', async () => {
    acopio({
      'GET /api/acopio/merma': [200, {
        ubicacion: 'Centro Nacional Lima', pagina: 0, tamano: 20, total: 2, totalPaginas: 1,
        items: [
          merma({ id: 901, lote: 'L-0236', motivo: 'Vencimiento', motivoEn: 'Expiry', cantidad: 6, automatica: true, registradoPor: undefined }),
          merma({ id: 900 }),
        ],
      }],
    })
    abrirEn('/acopio/merma')

    expect(await screen.findByText('Centro Nacional Lima / Merma')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Merma' })).toHaveAttribute('aria-current', 'page')
    const tabla = await screen.findByRole('table', { name: 'Merma registrada' })
    const filas = within(tabla).getAllByRole('row').slice(1)
    expect(filas[0]).toHaveTextContent('Harina de aguaje · L-0236')
    expect(filas[0]).toHaveTextContent('Vencimiento · automático')
    expect(filas[0]).toHaveTextContent('−6')
    expect(filas[0]).toHaveTextContent('Sistema')
    expect(within(filas[0]).getByText('Automática')).toBeInTheDocument()
    expect(filas[1]).toHaveTextContent('Daño')
    expect(filas[1]).toHaveTextContent('Admin Acopio')
    expect(within(filas[1]).getByText('Merma confirmada')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ir al inventario' })).toHaveAttribute('href', '/acopio/inventario')
  })

  it('el punto ve a qué productor pertenece el lote de cada merma (CCPIP-423)', async () => {
    acopio({
      'GET /api/acopio/merma': [200, {
        ubicacion: 'Centro Nacional Lima', pagina: 0, tamano: 20, total: 2, totalPaginas: 1,
        items: [
          merma({ id: 901, lote: 'L-0236', motivo: 'Vencimiento', motivoEn: 'Expiry', automatica: true, registradoPor: undefined }),
          merma({ id: 900, productorId: 2, productor: 'C.N. Yarina' }),
        ],
      }],
    })
    abrirEn('/acopio/merma')

    const tabla = await screen.findByRole('table', { name: 'Merma registrada' })
    const [encabezado, ...filas] = within(tabla).getAllByRole('row')
    expect(within(encabezado).getByRole('columnheader', { name: 'Productor' })).toBeInTheDocument()
    const columna = within(encabezado).getAllByRole('columnheader').findIndex((c) => c.textContent === 'Productor')
    expect(within(filas[0]).getAllByRole('cell')[columna]).toHaveTextContent('C.N. Puerto Alegre')
    expect(within(filas[1]).getAllByRole('cell')[columna]).toHaveTextContent('C.N. Yarina')
  })

  it('el productor ve la merma de sus lotes con su ubicación y la marca desde su almacén', async () => {
    const lote = { id: 31, codigo: 'L-0231', ubicacionId: 11, ubicacion: 'Almacén', tipoUbicacion: 'ALMACEN_PRODUCTOR', cantidad: 40, fechaVencimiento: '2027-03-15', estado: 'DISPONIBLE' }
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_PRODUCTOR],
      'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
      'GET /api/productor/merma': [200, {
        pagina: 0, tamano: 20, total: 1, totalPaginas: 1,
        items: [merma({ ubicacion: 'Centro Nacional Lima', registradoPor: undefined, motivo: 'Accidente', motivoEn: 'Accident' })],
      }],
      'GET /api/productor/inventario': () => [200, {
        resumen: { enAlmacen: 40, productosEnAlmacen: 1, enPuntosAcopio: 0, porPunto: [], lotesPorVencer: 0, lotesVencidos: 0 },
        diasAviso: 15,
        ubicaciones: [{ id: 11, nombre: 'Almacén', tipo: 'ALMACEN_PRODUCTOR', departamento: 'Ucayali' }],
        productos: [{ id: 9, nombre: 'Aceite de sacha inchi 250 ml', total: 40, porUbicacion: [{ ubicacionId: 11, ubicacion: 'Almacén', tipo: 'ALMACEN_PRODUCTOR', cantidad: 40 }], lotes: [lote] }],
      }],
      'GET /api/merma/causas': [200, CAUSAS],
      'POST /api/productor/merma': [201, { merma: merma({ lote: 'L-0231', cantidad: 2 }), saldo: 38, repetida: false }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/merma')

    const tabla = await screen.findByRole('table', { name: 'Merma registrada' })
    const fila = within(tabla).getAllByRole('row')[1]
    expect(fila).toHaveTextContent('Centro Nacional Lima')
    expect(fila).toHaveTextContent('Accidente')
    // La marco el punto: no se muestra quien
    expect(fila).toHaveTextContent('El punto de acopio')
    // Su propia merma: sin columna de productor
    expect(within(tabla).queryByRole('columnheader', { name: 'Productor' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Ir a Mi almacén' }))
    await user.click(await screen.findByRole('button', { name: 'Marcar como merma el lote L-0231' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Marcar como merma' })
    expect(within(dialogo).getByText('Mi almacén · stock actual 40 und')).toBeInTheDocument()
    await user.type(within(dialogo).getByRole('textbox', { name: 'Cantidad' }), '2')
    await user.click(await within(dialogo).findByRole('radio', { name: 'Daño' }))
    await user.click(within(dialogo).getByRole('button', { name: 'Continuar' }))
    await user.click(await screen.findByRole('button', { name: 'Confirmar merma' }))

    expect(await screen.findByText('Merma registrada: 2 und del lote L-0231')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'POST /api/productor/merma')).toMatchObject({ loteId: 31, cantidad: '2', motivoId: 2, confirmada: true })
  })

  it('en inglés la causa sale con su nombre en inglés', async () => {
    await i18n.changeLanguage('en')
    acopio({ 'GET /api/acopio/merma': [200, { ubicacion: 'Centro Nacional Lima', pagina: 0, tamano: 20, total: 1, totalPaginas: 1, items: [merma({})] }] })
    abrirEn('/acopio/merma')
    const tabla = await screen.findByRole('table', { name: 'Recorded shrinkage' })
    expect(within(tabla).getAllByRole('row')[1]).toHaveTextContent('Damage')
    expect(within(tabla).getByRole('columnheader', { name: 'Producer' })).toBeInTheDocument()
    expect(within(tabla).getAllByRole('row')[1]).toHaveTextContent('C.N. Puerto Alegre')
    await i18n.changeLanguage('es')
  })
})

// MermaModelos.Motivo
const MOTIVOS = [
  { id: 1, nombre: 'Vencimiento', nombreEn: 'Expiry', requiereDetalle: false, activo: true, sistema: true, usos: 12, actualizadoEn: '2026-10-18T10:00:00Z' },
  { id: 2, nombre: 'Daño', nombreEn: 'Damage', requiereDetalle: false, activo: true, sistema: false, usos: 3, actualizadoEn: '2026-10-18T10:00:00Z' },
  { id: 4, nombre: 'Derrame', nombreEn: 'Spill', requiereDetalle: false, activo: false, sistema: false, usos: 0, actualizadoEn: '2026-10-18T10:00:00Z' },
]

function superadmin(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUPER],
    'GET /api/admin/motivos-merma': () => [200, copia(MOTIVOS)],
    ...rutas,
  })
}

describe('Causas de merma del superadministrador (CCPIP-77, 298, 299, 300)', () => {
  it('consulta el catálogo; la causa de la merma automática no se desactiva', async () => {
    superadmin()
    abrirEn('/admin/motivos-merma')

    const tabla = await screen.findByRole('table', { name: 'Causas de merma' })
    expect(screen.getByRole('link', { name: 'Causas de merma' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText('2 causas activas de 3')).toBeInTheDocument()
    const filas = within(tabla).getAllByRole('row').slice(1)
    expect(filas[0]).toHaveTextContent('La usa la merma automática por vencimiento')
    expect(within(filas[0]).queryByRole('button', { name: /Desactivar/ })).not.toBeInTheDocument()
    expect(within(filas[1]).getByRole('button', { name: 'Desactivar Daño' })).toBeInTheDocument()
    expect(within(filas[2]).getByText('Inactiva')).toBeInTheDocument()
    expect(within(filas[2]).getByRole('button', { name: 'Activar Derrame' })).toBeInTheDocument()
  })

  it('crea una causa con su nombre en ambos idiomas', async () => {
    const creada = { id: 7, nombre: 'Plaga', nombreEn: 'Pest', requiereDetalle: true, activo: true, sistema: false, usos: 0, actualizadoEn: '2026-10-18T12:00:00Z' }
    const fetchMock = superadmin({ 'POST /api/admin/motivos-merma': [201, creada] })
    const user = userEvent.setup()
    abrirEn('/admin/motivos-merma')

    await user.click(await screen.findByRole('button', { name: 'Nueva causa' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Nueva causa' })
    await user.click(within(dialogo).getByRole('button', { name: 'Guardar' }))
    expect(within(dialogo).getByText('Escribe el nombre en español.')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/admin/motivos-merma')).toHaveLength(0)

    await user.type(within(dialogo).getByRole('textbox', { name: 'Nombre en español' }), 'Plaga')
    await user.type(within(dialogo).getByRole('textbox', { name: 'Nombre en inglés' }), 'Pest')
    await user.click(within(dialogo).getByRole('checkbox', { name: 'Pide describir la causa (como «Otra»)' }))
    await user.click(within(dialogo).getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Causa «Plaga» creada')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'POST /api/admin/motivos-merma')).toEqual({ nombre: 'Plaga', nombreEn: 'Pest', requiereDetalle: true })
    expect(within(screen.getByRole('table', { name: 'Causas de merma' })).getByText('Plaga')).toBeInTheDocument()
  })

  it('modifica una causa avisando que la merma registrada conserva el nombre', async () => {
    const fetchMock = superadmin({
      'PUT /api/admin/motivos-merma/2': (o) => [200, { ...MOTIVOS[1], ...JSON.parse(o.body) }],
    })
    const user = userEvent.setup()
    abrirEn('/admin/motivos-merma')

    await user.click(await screen.findByRole('button', { name: 'Editar Daño' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Editar causa' })
    expect(within(dialogo).getByText('3 mermas ya registradas conservan el nombre actual.')).toBeInTheDocument()
    const nombre = within(dialogo).getByRole('textbox', { name: 'Nombre en español' })
    await user.clear(nombre)
    await user.type(nombre, 'Daño físico')
    await user.click(within(dialogo).getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Causa «Daño físico» actualizada')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'PUT /api/admin/motivos-merma/2')).toEqual({ nombre: 'Daño físico', nombreEn: 'Damage', requiereDetalle: false })
  })

  it('desactiva una causa con confirmación y conserva su historial', async () => {
    const fetchMock = superadmin({ 'POST /api/admin/motivos-merma/2/desactivar': [200, { ...MOTIVOS[1], activo: false }] })
    const user = userEvent.setup()
    abrirEn('/admin/motivos-merma')

    await user.click(await screen.findByRole('button', { name: 'Desactivar Daño' }))
    const dialogo = await screen.findByRole('dialog', { name: '¿Desactivar «Daño»?' })
    expect(within(dialogo).getByText(/La merma ya registrada con esta causa \(3\) la conserva/)).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('button', { name: 'Desactivar' }))

    expect(await screen.findByText('Causa «Daño» desactivada')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/admin/motivos-merma/2/desactivar')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Activar Daño' })).toBeInTheDocument()
  })
})
