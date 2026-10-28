import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR, SESION_SUB } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const EXPEDIENTE = '/api/admin/autorizaciones/41'
const RENOVACIONES = `${EXPEDIENTE}/renovaciones`
const CERTIFICADO = '6f1c2a4e-1b2c-4d3e-8f90-112233445566'

const aprobado = () => ({ ...copia(DATOS.expedienteAdmin), estado: 'APROBADA', vigenteHasta: '2027-03-31', puedeDecidir: false, puedeRevocar: true })

const RENOVACION = {
  id: 7, autorizacionId: 41, productoId: 9, producto: 'Camu camu en polvo 150 g',
  certificado: { evidenciaId: CERTIFICADO, titulo: 'Certificado orgánico de camu camu', emisor: 'SENASA', vigenteHasta: '2027-03-31', vencido: false },
  motivo: 'Vence en marzo', plazo: '2027-03-31', estado: 'PENDIENTE', solicitadaPor: 'Rosa Ccori', solicitadaEn: '2026-11-18T15:00:00Z',
  ventaPausadaDesde: '2027-04-01',
}

function comoSub(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUB],
    [`GET ${EXPEDIENTE}`]: () => [200, aprobado()],
    [`GET ${RENOVACIONES}`]: () => [200, []],
    ...rutas,
  })
}

describe('CCPIP-321 Solicitud de renovacion de un certificado', () => {
  it('el subadministrador pide renovar un certificado de una autorizacion aprobada', async () => {
    const fetchMock = comoSub({ [`POST ${RENOVACIONES}`]: () => [201, copia(RENOVACION)] })
    const user = userEvent.setup()
    abrirEn('/admin/autorizaciones/41')

    const seccion = await screen.findByRole('region', { name: 'Renovación de certificados' })
    expect(within(seccion).getByText(/La venta no cambia/)).toBeInTheDocument()
    const certificados = within(seccion).getByRole('list', { name: 'Certificados de esta autorización' })
    // Solo los certificados, no los documentos
    expect(within(certificados).getAllByRole('listitem')).toHaveLength(2)
    const [primero] = within(certificados).getAllByRole('listitem')
    await user.click(within(primero).getByRole('button', { name: 'Pedir renovación' }))

    const dialogo = await screen.findByRole('dialog', { name: 'Pedir la renovación de «Certificado orgánico de camu camu»' })
    // Sin motivo no se envia
    await user.click(within(dialogo).getByRole('button', { name: 'Pedir renovación' }))
    expect(within(dialogo).getByText('Escribe el motivo de la solicitud')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${RENOVACIONES}`)).toHaveLength(0)

    await user.type(within(dialogo).getByLabelText('Motivo'), 'Vence en marzo')
    await user.click(within(dialogo).getByRole('button', { name: 'Pedir renovación' }))

    expect(await screen.findByText('Solicitud de renovación registrada')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${RENOVACIONES}`)
    expect(JSON.parse(opciones.body)).toEqual({ evidenciaId: CERTIFICADO, motivo: 'Vence en marzo', plazo: null })

    const solicitudes = within(seccion).getByRole('list', { name: 'Solicitudes de renovación' })
    expect(within(solicitudes).getByText('«Vence en marzo»')).toBeInTheDocument()
    expect(within(solicitudes).getByText(/Venta pausada desde el 01\/04\/2027/)).toBeInTheDocument()
    // El certificado ya pedido no se vuelve a ofrecer
    expect(within(primero).queryByRole('button', { name: 'Pedir renovación' })).not.toBeInTheDocument()
    expect(within(primero).getByText('Renovación pedida')).toBeInTheDocument()
  })

  it('una autorizacion que no esta aprobada no ofrece pedir renovacion', async () => {
    comoSub({ [`GET ${EXPEDIENTE}`]: () => [200, copia(DATOS.expedienteAdmin)] })
    abrirEn('/admin/autorizaciones/41')
    await screen.findByRole('region', { name: /^Evidencias \(/ })
    expect(screen.queryByRole('region', { name: 'Renovación de certificados' })).not.toBeInTheDocument()
  })

  it('un plazo pasado se marca en el campo', async () => {
    comoSub({ [`POST ${RENOVACIONES}`]: () => [422, { code: 'PLAZO_PASADO', error: 'El plazo no puede ser una fecha pasada.' }] })
    const user = userEvent.setup()
    abrirEn('/admin/autorizaciones/41')
    const seccion = await screen.findByRole('region', { name: 'Renovación de certificados' })
    await user.click(within(seccion).getAllByRole('button', { name: 'Pedir renovación' })[0])
    const dialogo = await screen.findByRole('dialog')
    await user.type(within(dialogo).getByLabelText('Motivo'), 'Motivo')
    await user.click(within(dialogo).getByRole('button', { name: 'Pedir renovación' }))
    expect(await within(dialogo).findByText('El plazo no puede ser una fecha pasada.')).toBeInTheDocument()
  })

  it('el productor ve en las evidencias del producto lo que se le pidio renovar', async () => {
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_PRODUCTOR],
      'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
      'GET /api/productor/productos/9/evidencias': () => [200, copia(DATOS.evidenciasProductor)],
      'GET /api/productor/productos/9/renovaciones': () => [200, [{ ...copia(RENOVACION), solicitadaPor: null }]],
    })
    abrirEn('/productor/productos/9/evidencias')
    expect(await screen.findByText('La Cámara te pide renovar un certificado')).toBeInTheDocument()
    expect(screen.getByText(/«Certificado orgánico de camu camu» antes del 31\/03\/2027\. Motivo: Vence en marzo/)).toBeInTheDocument()
    expect(screen.getByText(/Tu producto sigue a la venta/)).toBeInTheDocument()
  })
})
