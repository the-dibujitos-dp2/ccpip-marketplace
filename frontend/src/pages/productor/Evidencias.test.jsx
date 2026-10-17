import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const RUTA = '/api/productor/productos/9/evidencias'
const pdf = (nombre = 'certificado.pdf', bytes = 20) => new File([new Uint8Array(bytes)], nombre, { type: 'application/pdf' })

const productor = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    [`GET ${RUTA}`]: () => [200, copia(DATOS.evidenciasProductor)],
    ...rutas,
  })

const tarjeta = (nombre) => within(screen.getByRole('region', { name: nombre }))

afterEach(async () => {
  await i18n.changeLanguage('es')
})

describe('Certificados y evidencias del producto (CCPIP-36)', () => {
  it('muestra la solicitud y cada evidencia con su producto, tipo y vigencia', async () => {
    productor()
    abrirEn('/productor/productos/9/evidencias')

    expect(await screen.findByRole('heading', { level: 1, name: 'Certificados y evidencias' })).toBeInTheDocument()
    expect(await screen.findByText(/sustenta la autorización para vender Camu camu en polvo 150 g/)).toBeInTheDocument()
    expect(screen.getByText('En revisión por la Cámara')).toBeInTheDocument()

    const certificados = tarjeta('Certificados')
    expect(certificados.getByText('2 archivos')).toBeInTheDocument()
    expect(certificados.getByText('Vigente hasta 31/03/2027')).toBeInTheDocument()
    expect(certificados.getByText('Venció el 30/09/2026')).toBeInTheDocument()
    expect(certificados.getByText('Control Unión · certificado_organico_2026.pdf · PDF')).toBeInTheDocument()
    expect(certificados.getByRole('link', { name: 'Ver Certificado orgánico de camu camu' }))
      .toHaveAttribute('href', '/api/productor/evidencias/6f1c2a4e-1b2c-4d3e-8f90-112233445566/archivo')
    expect(tarjeta('Documentos').getByText('Pendiente')).toBeInTheDocument()
    expect(screen.getByText('Solo la Cámara y tú pueden verlas.')).toBeInTheDocument()
  })

  it('valida antes de subir y envía el archivo con lo que lo identifica', async () => {
    const fetchMock = productor({
      [`POST ${RUTA}`]: () => [201, { ...copia(DATOS.evidenciasProductor.evidencias[0]), id: 'aa', titulo: 'Registro sanitario' }],
    })
    abrirEn('/productor/productos/9/evidencias')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Agregar certificado' }))
    const form = within(screen.getByRole('form', { name: 'Agregar certificado' }))
    await user.click(form.getByRole('button', { name: 'Subir' }))
    expect(form.getByText('Escribe el nombre del documento')).toBeInTheDocument()
    expect(form.getByText('Indica hasta cuándo vale el certificado')).toBeInTheDocument()
    expect(form.getByText('Elige un archivo')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([, o = {}]) => o.method === 'POST')).toBe(false)

    await user.type(form.getByLabelText('Nombre del documento'), 'Registro sanitario')
    await user.type(form.getByLabelText('Quién lo emite'), 'DIGESA')
    await user.type(form.getByLabelText('Vigente hasta'), '2027-06-30')
    await user.upload(form.getByLabelText('Archivo'), pdf())
    await user.click(form.getByRole('button', { name: 'Subir' }))

    expect(await screen.findByText('Subiste «Registro sanitario». La Cámara la revisará.')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${RUTA}`)
    const datos = opciones.body
    expect(datos.get('tipo')).toBe('CERTIFICADO')
    expect(datos.get('titulo')).toBe('Registro sanitario')
    expect(datos.get('emisor')).toBe('DIGESA')
    expect(datos.get('vigenteHasta')).toBe('2027-06-30')
    expect(datos.has('vigenteDesde')).toBe(false)
    expect(datos.get('archivo').name).toBe('certificado.pdf')
    // Vuelve a pedir la lista con la evidencia nueva
    expect(llamadas(fetchMock, `GET ${RUTA}`)).toHaveLength(2)
  })

  it('rechaza en la pantalla lo que no es PDF, JPG o PNG, o pesa más de 10 MB', async () => {
    const fetchMock = productor()
    abrirEn('/productor/productos/9/evidencias')
    const user = userEvent.setup({ applyAccept: false })
    await user.click(await screen.findByRole('button', { name: 'Agregar documento' }))
    const form = within(screen.getByRole('form', { name: 'Agregar documento' }))
    await user.type(form.getByLabelText('Nombre del documento'), 'Acta')
    await user.upload(form.getByLabelText('Archivo'), new File(['x'], 'acta.docx', { type: 'application/msword' }))
    await user.click(form.getByRole('button', { name: 'Subir' }))
    expect(form.getByText('Solo se aceptan archivos PDF, JPG o PNG')).toBeInTheDocument()

    await user.upload(form.getByLabelText('Archivo'), pdf('grande.pdf', 10 * 1024 * 1024 + 1))
    await user.click(form.getByRole('button', { name: 'Subir' }))
    expect(form.getByText('El archivo pesa más de 10 MB')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${RUTA}`)).toHaveLength(0)

    await user.click(form.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('form', { name: 'Agregar documento' })).not.toBeInTheDocument()
  })

  it('muestra el error del servidor al subir', async () => {
    productor({ [`POST ${RUTA}`]: [422, { code: 'FORMATO_NO_PERMITIDO', error: 'Solo se aceptan archivos PDF, JPG o PNG.' }] })
    abrirEn('/productor/productos/9/evidencias')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Agregar evidencia' }))
    const form = within(screen.getByRole('form', { name: 'Agregar evidencia' }))
    await user.type(form.getByLabelText('Nombre del documento'), 'Fotos de la cosecha')
    await user.upload(form.getByLabelText('Archivo'), new File(['x'], 'f.png', { type: 'image/png' }))
    await user.click(form.getByRole('button', { name: 'Subir' }))
    expect(await form.findByText('Solo se aceptan archivos PDF, JPG o PNG.')).toBeInTheDocument()
  })

  it('retira una evidencia después de confirmar', async () => {
    const fetchMock = productor({ [`DELETE ${RUTA}/9c4f5d71-4e5f-4061-9c23-445566778899`]: [204, null] })
    abrirEn('/productor/productos/9/evidencias')
    await userEvent.click(await screen.findByRole('button', { name: 'Retirar Certificado de comercio justo' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByText(/«Certificado de comercio justo» dejará de sustentar la solicitud/)).toBeInTheDocument()
    await userEvent.click(dialogo.getByRole('button', { name: 'Retirar' }))
    expect(await screen.findByText('Retiraste «Certificado de comercio justo».')).toBeInTheDocument()
    expect(llamadas(fetchMock, `DELETE ${RUTA}/9c4f5d71-4e5f-4061-9c23-445566778899`)).toHaveLength(1)
  })

  it('una solicitud rechazada muestra el motivo y permite adjuntar de nuevo', async () => {
    const rechazada = copia(DATOS.evidenciasProductor)
    rechazada.autorizacion = { ...rechazada.autorizacion, estado: 'RECHAZADA', motivo: 'El certificado no nombra el producto' }
    productor({ [`GET ${RUTA}`]: [200, rechazada] })
    abrirEn('/productor/productos/9/evidencias')
    expect(await screen.findByText('La Cámara rechazó la solicitud')).toBeInTheDocument()
    expect(screen.getByText('Motivo: «El certificado no nombra el producto». Adjunta nuevas evidencias para volver a solicitarla.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Agregar certificado' })).toBeInTheDocument()
  })

  it('con la autorización vigente sus evidencias no se cambian', async () => {
    const aprobada = copia(DATOS.evidenciasProductor)
    aprobada.autorizacion = { ...aprobada.autorizacion, estado: 'APROBADA', vigenteHasta: '2027-03-31' }
    aprobada.puedeAdjuntar = false
    productor({ [`GET ${RUTA}`]: [200, aprobada] })
    abrirEn('/productor/productos/9/evidencias')
    expect(await screen.findByText('Autorizado para vender')).toBeInTheDocument()
    expect(screen.getByText('La autorización vale hasta el 31/03/2027, cuando vence tu primer certificado.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Agregar/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Retirar/ })).not.toBeInTheDocument()
  })

  it('sin solicitud aún, explica qué subir; al llegar al máximo lo dice', async () => {
    const sinSolicitud = { ...copia(DATOS.evidenciasProductor), autorizacion: { id: null, estado: 'SIN_SOLICITUD' }, evidencias: [] }
    productor({ [`GET ${RUTA}`]: [200, sinSolicitud] })
    const { unmount } = abrirEn('/productor/productos/9/evidencias')
    expect(await screen.findByText('Aún no solicitas la autorización')).toBeInTheDocument()
    unmount()

    productor({ [`GET ${RUTA}`]: [200, { ...copia(DATOS.evidenciasProductor), puedeAdjuntar: false }] })
    abrirEn('/productor/productos/9/evidencias')
    expect(await screen.findByText('Llegaste al máximo de 10 evidencias. Retira una para agregar otra.')).toBeInTheDocument()
  })

  it('un producto ajeno no existe para el productor', async () => {
    productor({ [`GET ${RUTA}`]: [404, { code: 'PRODUCTO_NO_ENCONTRADO', error: 'El producto no existe.' }] })
    abrirEn('/productor/productos/9/evidencias')
    expect(await screen.findByText('El producto no existe.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Volver a mis productos' })).toHaveAttribute('href', '/productor/productos')
  })

  it('se llega desde el menú de cada producto', async () => {
    productor({ 'GET /api/productor/productos?estado=PUBLICADO': () => [200, copia(DATOS.productosProductor)] })
    abrirEn('/productor/productos')
    await userEvent.click(await screen.findByRole('button', { name: 'Más acciones para Camu camu en polvo 150 g' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Certificados y evidencias' }))
    expect(window.location.pathname).toBe('/productor/productos/9/evidencias')
    expect(await screen.findByRole('heading', { level: 1, name: 'Certificados y evidencias' })).toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    productor()
    abrirEn('/productor/productos/9/evidencias')
    expect(await screen.findByRole('heading', { level: 1, name: 'Certificates and evidence' })).toBeInTheDocument()
    expect(await screen.findByText('Under review by the Chamber')).toBeInTheDocument()
    expect(tarjeta('Certificates').getByText('Expired on 30/09/2026')).toBeInTheDocument()
  })
})
