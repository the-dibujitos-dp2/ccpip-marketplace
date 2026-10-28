import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const RUTA = '/api/productor/productos/9/cumplimiento'

// Armado segun CumplimientoService.Cumplimiento del backend, con el catalogo de V202610281500
const REGLAS = [
  { codigo: 'CERO_DEFORESTACION', nombre: 'Cero deforestación', nombreEn: 'Zero deforestation', descripcion: 'La materia prima no proviene de áreas taladas.', descripcionEn: 'The raw material does not come from cleared areas.' },
  { codigo: 'CERO_PESTICIDAS', nombre: 'Cero pesticidas o herbicidas', nombreEn: 'Zero pesticides or herbicides', descripcion: 'No se usan agroquímicos.', descripcionEn: 'No agrochemicals are used.' },
  { codigo: 'ELABORADO_POR_COMUNIDAD', nombre: 'Elaborado por comunidad indígena', nombreEn: 'Made by an Indigenous community', descripcion: 'Lo elaboran personas de la comunidad.', descripcionEn: 'It is made by people from the community.' },
]
const SIN_DECLARAR = { productoId: 9, producto: 'Camu camu en polvo 150 g', reglas: REGLAS, vigente: null, historial: [] }
const declaracion = (id, reglas, fecha, observaciones = null) => ({ id, reglas, observaciones, declaradaPor: 'Rosa Ccori', declaradaEn: fecha })

const productor = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/productos/9/evidencias': () => [200, copia(DATOS.evidenciasProductor)],
    'GET /api/productor/productos/9/lotes': [200, { items: [] }],
    'GET /api/productor/productos/9/evidencias/6f1c2a4e-1b2c-4d3e-8f90-112233445566/lote': [204, null],
    [`GET ${RUTA}`]: () => [200, copia(SIN_DECLARAR)],
    ...rutas,
  })

const seccion = async () => within(await screen.findByRole('region', { name: 'Reglas de origen y sostenibilidad' }))

describe('CCPIP-34 Declarar el cumplimiento de las reglas de origen y sostenibilidad', () => {
  it('sin declaracion, ninguna regla viene marcada', async () => {
    productor()
    abrirEn('/productor/productos/9/evidencias')
    const s = await seccion()
    expect(s.getByText('Aún no declaraste ninguna regla para este producto.')).toBeInTheDocument()
    const reglas = within(s.getByRole('list', { name: 'Reglas de la Cámara' }))
    expect(reglas.getAllByRole('switch')).toHaveLength(3)
    reglas.getAllByRole('switch').forEach((r) => expect(r).not.toBeChecked())
    expect(s.getByText(/No es un sello ni una aprobación/)).toBeInTheDocument()
  })

  it('declara las reglas que cumple el producto con observaciones', async () => {
    const guardada = { ...copia(SIN_DECLARAR), vigente: declaracion(5, ['CERO_DEFORESTACION', 'ELABORADO_POR_COMUNIDAD'], '2026-11-03T15:00:00Z', 'Bosque en pie') }
    guardada.historial = [guardada.vigente]
    const fetchMock = productor({ [`POST ${RUTA}`]: () => [201, guardada] })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9/evidencias')
    const s = await seccion()

    // Se marcan en cualquier orden; se envian en el orden del catalogo
    await user.click(s.getByRole('switch', { name: 'Elaborado por comunidad indígena' }))
    await user.click(s.getByRole('switch', { name: 'Cero deforestación' }))
    await user.type(s.getByLabelText('Observaciones (opcional)'), 'Bosque en pie')
    await user.click(s.getByRole('button', { name: 'Guardar declaración' }))

    expect(await screen.findByText('Guardaste la declaración')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${RUTA}`)
    expect(JSON.parse(opciones.body)).toEqual({ reglas: ['CERO_DEFORESTACION', 'ELABORADO_POR_COMUNIDAD'], observaciones: 'Bosque en pie' })
    expect((await seccion()).getByText(/Última declaración: .* por Rosa Ccori/)).toBeInTheDocument()
  })

  it('parte de lo declarado y muestra las declaraciones anteriores', async () => {
    const vigente = declaracion(6, ['CERO_PESTICIDAS'], '2026-11-05T15:00:00Z')
    const anterior = declaracion(5, [], '2026-11-01T15:00:00Z', 'Cambio de semillas')
    productor({ [`GET ${RUTA}`]: () => [200, { ...copia(SIN_DECLARAR), vigente, historial: [vigente, anterior] }] })
    abrirEn('/productor/productos/9/evidencias')
    const s = await seccion()
    expect(s.getByRole('switch', { name: 'Cero pesticidas o herbicidas' })).toBeChecked()
    expect(s.getByRole('switch', { name: 'Cero deforestación' })).not.toBeChecked()
    expect(s.getByText('Declaraciones anteriores')).toBeInTheDocument()
    expect(s.getByText(/Ninguna regla · «Cambio de semillas»/)).toBeInTheDocument()
  })

  it('si el servidor la rechaza, lo dice', async () => {
    productor({ [`POST ${RUTA}`]: () => [422, { code: 'REGLA_INVALIDA', error: 'Solo se declaran reglas vigentes definidas por la Cámara.' }] })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9/evidencias')
    await user.click((await seccion()).getByRole('button', { name: 'Guardar declaración' }))
    expect(await screen.findByText('Solo se declaran reglas vigentes definidas por la Cámara.')).toBeInTheDocument()
  })

  it('en ingles usa los nombres de las reglas en ingles', async () => {
    await i18n.changeLanguage('en')
    productor()
    abrirEn('/productor/productos/9/evidencias')
    const s = within(await screen.findByRole('region', { name: 'Origin and sustainability rules' }))
    expect(s.getByRole('switch', { name: 'Zero deforestation' })).toBeInTheDocument()
  })
})
