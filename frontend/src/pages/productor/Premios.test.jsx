import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const RUTA = '/api/productor/productos/9/premios'
const pdf = (nombre = 'medalla.pdf', bytes = 20) => new File([new Uint8Array(bytes)], nombre, { type: 'application/pdf' })

const LOTES = [
  { id: 31, codigo: 'L-2026-09', produccion: '2026-09-04', vencimiento: '2027-03-04' },
  { id: 30, codigo: 'L-2026-06', produccion: '2026-06-10', vencimiento: '2026-12-10' },
]
const MEDALLA = {
  id: 5,
  productoId: 9,
  lote: LOTES[0],
  titulo: 'Medalla de oro',
  emisor: 'Salón del Cacao y Chocolate',
  otorgadoEn: '2026-10-01',
  vigenteHasta: '2027-10-01',
  vigente: true,
  nombreArchivo: 'medalla.pdf',
  formato: 'PDF',
  bytes: 2048,
  registradoEn: '2026-10-20T15:00:00Z',
  url: '/api/productor/premios/5/archivo',
}
const PREMIOS = { productoId: 9, producto: 'Camu camu en polvo 150 g', premios: [MEDALLA], lotes: LOTES, puedeRegistrar: true, maximo: 10 }

const productor = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    [`GET ${RUTA}`]: () => [200, copia(PREMIOS)],
    ...rutas,
  })

describe('Premios del producto (CCPIP-43)', () => {
  it('lista cada premio con su emisor, fecha, lote, vigencia y su archivo privado', async () => {
    productor()
    abrirEn('/productor/productos/9/premios')
    expect(await screen.findByRole('heading', { level: 1, name: 'Premios del producto' })).toBeInTheDocument()
    expect(await screen.findByText(/Registra los premios que recibió Camu camu en polvo 150 g/)).toBeInTheDocument()
    const lista = within(screen.getByRole('list', { name: 'Premios registrados' }))
    expect(lista.getByText('Medalla de oro')).toBeInTheDocument()
    expect(lista.getByText('Salón del Cacao y Chocolate · otorgado el 01/10/2026 · Lote L-2026-09 (producido el 04/09/2026)')).toBeInTheDocument()
    expect(lista.getByText('Vigente hasta 01/10/2027')).toBeInTheDocument()
    expect(lista.getByRole('link', { name: 'Ver Medalla de oro' })).toHaveAttribute('href', '/api/productor/premios/5/archivo')
    expect(screen.getByText('El archivo de respaldo es privado: solo lo ves tú.')).toBeInTheDocument()
  })

  it('valida antes de registrar y envía el premio con su lote y su archivo', async () => {
    const fetchMock = productor({ [`POST ${RUTA}`]: () => [201, { ...copia(MEDALLA), id: 6, titulo: 'Premio regional' }] })
    abrirEn('/productor/productos/9/premios')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Registrar premio' }))
    const form = within(screen.getByRole('form', { name: 'Registrar premio' }))
    await user.click(form.getByRole('button', { name: 'Registrar premio' }))
    expect(form.getByText('Escribe el nombre del premio.')).toBeInTheDocument()
    expect(form.getByText('Indica la entidad que otorgó el premio.')).toBeInTheDocument()
    expect(form.getByText('Indica cuándo se otorgó el premio.')).toBeInTheDocument()
    expect(form.getByText('Elige el lote al que corresponde el premio.')).toBeInTheDocument()
    expect(form.getByText('Elige un archivo')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${RUTA}`)).toHaveLength(0)

    await user.type(form.getByLabelText('Nombre del premio'), 'Premio regional')
    await user.type(form.getByLabelText('Entidad que lo otorgó'), 'Gobierno Regional de Ucayali')
    await user.selectOptions(form.getByLabelText('Lote premiado'), '30')
    await user.type(form.getByLabelText('Fecha en que se otorgó'), '2026-08-15')
    await user.upload(form.getByLabelText('Archivo que lo respalda'), pdf('diploma.pdf'))
    await user.click(form.getByRole('button', { name: 'Registrar premio' }))

    expect(await screen.findByText('Registraste «Premio regional».')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${RUTA}`)
    const datos = opciones.body
    expect(datos.get('titulo')).toBe('Premio regional')
    expect(datos.get('emisor')).toBe('Gobierno Regional de Ucayali')
    expect(datos.get('loteId')).toBe('30')
    expect(datos.get('otorgadoEn')).toBe('2026-08-15')
    expect(datos.has('vigenteHasta')).toBe(false)
    expect(datos.get('archivo').name).toBe('diploma.pdf')
    expect(llamadas(fetchMock, `GET ${RUTA}`)).toHaveLength(2)
  })

  it('no acepta una fecha futura ni una vigencia que termina antes', async () => {
    const fetchMock = productor()
    abrirEn('/productor/productos/9/premios')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Registrar premio' }))
    const form = within(screen.getByRole('form', { name: 'Registrar premio' }))
    await user.type(form.getByLabelText('Fecha en que se otorgó'), '2999-01-01')
    await user.click(form.getByRole('button', { name: 'Registrar premio' }))
    expect(form.getByText('La fecha no puede ser posterior a hoy.')).toBeInTheDocument()

    await user.clear(form.getByLabelText('Fecha en que se otorgó'))
    await user.type(form.getByLabelText('Fecha en que se otorgó'), '2026-05-01')
    await user.type(form.getByLabelText('Vigente hasta (opcional)'), '2026-04-01')
    await user.click(form.getByRole('button', { name: 'Registrar premio' }))
    expect(form.getByText('La vigencia termina antes de que se otorgara el premio.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${RUTA}`)).toHaveLength(0)
  })

  it('muestra el error del servidor al registrar', async () => {
    productor({ [`POST ${RUTA}`]: [422, { code: 'PREMIO_LOTE_AJENO', error: 'Elige un lote de este producto.' }] })
    abrirEn('/productor/productos/9/premios')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Registrar premio' }))
    const form = within(screen.getByRole('form', { name: 'Registrar premio' }))
    await user.type(form.getByLabelText('Nombre del premio'), 'Medalla')
    await user.type(form.getByLabelText('Entidad que lo otorgó'), 'Emisor')
    await user.selectOptions(form.getByLabelText('Lote premiado'), '31')
    await user.type(form.getByLabelText('Fecha en que se otorgó'), '2026-08-15')
    await user.upload(form.getByLabelText('Archivo que lo respalda'), pdf())
    await user.click(form.getByRole('button', { name: 'Registrar premio' }))
    expect(await form.findByText('Elige un lote de este producto.')).toBeInTheDocument()
  })

  it('retira un premio después de confirmar', async () => {
    const fetchMock = productor({ [`DELETE ${RUTA}/5`]: [204, null] })
    abrirEn('/productor/productos/9/premios')
    await userEvent.click(await screen.findByRole('button', { name: 'Retirar Medalla de oro' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByText(/«Medalla de oro» dejará de mostrarse en la ficha del producto/)).toBeInTheDocument()
    await userEvent.click(dialogo.getByRole('button', { name: 'Retirar' }))
    expect(await screen.findByText('Retiraste «Medalla de oro». Ya no se muestra en la ficha.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `DELETE ${RUTA}/5`)).toHaveLength(1)
  })

  it('sin lotes pide registrar uno primero', async () => {
    productor({ [`GET ${RUTA}`]: [200, { ...copia(PREMIOS), premios: [], lotes: [] }] })
    abrirEn('/productor/productos/9/premios')
    expect(await screen.findByText('Para registrar un premio, primero registra un lote de este producto.')).toBeInTheDocument()
    expect(screen.getByText('Aún no registraste premios para este producto.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar premio' })).not.toBeInTheDocument()
  })

  it('al llegar al máximo lo dice y no ofrece registrar', async () => {
    productor({ [`GET ${RUTA}`]: [200, { ...copia(PREMIOS), puedeRegistrar: false }] })
    abrirEn('/productor/productos/9/premios')
    expect(await screen.findByText('Llegaste al máximo de 10 premios. Retira uno para registrar otro.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Registrar premio' })).not.toBeInTheDocument()
  })
})
