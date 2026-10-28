import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUB } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const EXPEDIENTE = '/api/admin/autorizaciones/41'
const FISICAS = `${EXPEDIENTE}/evidencias-fisicas`

const ENVIADA = {
  id: 3, autorizacionId: 41, productoId: 9, descripcion: 'Muestra de 150 g de camu camu', referencia: 'Guía 001-2345',
  enviadaEl: '2026-11-03', observaciones: null, registradaPor: 'Rosa Ccori', registradaEn: '2026-11-03T15:00:00Z',
  estado: 'ENVIADA', recepcion: null,
}
const RECIBIDA = {
  ...ENVIADA, estado: 'RECIBIDA',
  recepcion: { recibidaEl: '2026-11-05', resultado: 'OBSERVADA', observaciones: 'El empaque llegó abierto', registradaPor: 'Rosa Ccori', registradaEn: '2026-11-05T16:00:00Z' },
}

function comoSub(rutas = {}, expediente = copia(DATOS.expedienteAdmin)) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUB],
    [`GET ${EXPEDIENTE}`]: () => [200, expediente],
    [`GET ${EXPEDIENTE}/renovaciones`]: () => [200, []],
    [`GET ${FISICAS}`]: () => [200, []],
    ...rutas,
  })
}

const seccion = async () => within(await screen.findByRole('region', { name: 'Evidencias físicas' }))

describe('CCPIP-35 Registrar el envio de una evidencia fisica', () => {
  it('el subadministrador registra el envio con lo que se envia y su fecha', async () => {
    const fetchMock = comoSub({ [`POST ${FISICAS}`]: () => [201, copia(ENVIADA)] })
    const user = userEvent.setup()
    abrirEn('/admin/autorizaciones/41')

    const s = await seccion()
    expect(s.getByText(/Es opcional: aprobar no exige ninguna/)).toBeInTheDocument()
    await user.click(s.getByRole('button', { name: 'Registrar envío' }))
    const dialogo = within(await screen.findByRole('dialog', { name: 'Registrar el envío de una evidencia física' }))

    // Sin descripcion no se envia
    await user.click(dialogo.getByRole('button', { name: 'Registrar envío' }))
    expect(dialogo.getByText('Describe la evidencia física que se envía')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${FISICAS}`)).toHaveLength(0)

    await user.type(dialogo.getByLabelText('Qué se envía'), 'Muestra de 150 g de camu camu')
    await user.type(dialogo.getByLabelText('Guía o referencia (opcional)'), 'Guía 001-2345')
    await user.clear(dialogo.getByLabelText('Fecha de envío'))
    await user.type(dialogo.getByLabelText('Fecha de envío'), '2026-11-03')
    await user.click(dialogo.getByRole('button', { name: 'Registrar envío' }))

    expect(await screen.findByText('Envío registrado')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${FISICAS}`)
    expect(JSON.parse(opciones.body)).toEqual({
      descripcion: 'Muestra de 150 g de camu camu', referencia: 'Guía 001-2345', enviadaEl: '2026-11-03', observaciones: null,
    })
    const lista = within((await seccion()).getByRole('list', { name: 'Evidencias físicas de esta solicitud' }))
    expect(lista.getByText('Muestra de 150 g de camu camu')).toBeInTheDocument()
    expect(lista.getByRole('button', { name: 'Registrar recepción' })).toBeInTheDocument()
  })

  it('una fecha futura se marca en su campo', async () => {
    comoSub({ [`POST ${FISICAS}`]: () => [422, { code: 'FECHA_FUTURA', error: 'La fecha no puede ser posterior a hoy.' }] })
    const user = userEvent.setup()
    abrirEn('/admin/autorizaciones/41')
    await user.click((await seccion()).getByRole('button', { name: 'Registrar envío' }))
    const dialogo = within(await screen.findByRole('dialog'))
    await user.type(dialogo.getByLabelText('Qué se envía'), 'Muestra')
    await user.click(dialogo.getByRole('button', { name: 'Registrar envío' }))
    expect(await dialogo.findByText('La fecha no puede ser posterior a hoy.')).toBeInTheDocument()
  })

  it('una solicitud ya decidida no ofrece registrar envios, pero muestra los registrados', async () => {
    const resuelta = { ...copia(DATOS.expedienteAdmin), estado: 'APROBADA', puedeDecidir: false, puedeRevocar: true }
    comoSub({ [`GET ${FISICAS}`]: () => [200, [copia(RECIBIDA)]] }, resuelta)
    abrirEn('/admin/autorizaciones/41')
    const s = await seccion()
    expect(await s.findByText('Muestra de 150 g de camu camu')).toBeInTheDocument()
    expect(s.queryByRole('button', { name: 'Registrar envío' })).not.toBeInTheDocument()
  })

  it('una solicitud ya decidida y sin evidencias fisicas no muestra la seccion', async () => {
    const resuelta = { ...copia(DATOS.expedienteAdmin), estado: 'RECHAZADA', puedeDecidir: false }
    comoSub({}, resuelta)
    abrirEn('/admin/autorizaciones/41')
    expect(await screen.findByRole('heading', { name: /Camu camu en polvo 150 g/, level: 2 })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Evidencias físicas' })).not.toBeInTheDocument()
  })
})

describe('CCPIP-329 Registrar la recepcion de una evidencia fisica', () => {
  it('registra la recepcion con resultado y observaciones, y ya no se puede volver a registrar', async () => {
    const fetchMock = comoSub({
      [`GET ${FISICAS}`]: () => [200, [copia(ENVIADA)]],
      [`POST ${FISICAS}/3/recepcion`]: () => [200, copia(RECIBIDA)],
    })
    const user = userEvent.setup()
    abrirEn('/admin/autorizaciones/41')

    await user.click(await (await seccion()).findByRole('button', { name: 'Registrar recepción' }))
    const dialogo = within(await screen.findByRole('dialog', { name: 'Registrar la recepción de «Muestra de 150 g de camu camu»' }))

    // Sin resultado no se envia; con observaciones pendientes tampoco
    await user.click(dialogo.getByRole('button', { name: 'Registrar recepción' }))
    expect(dialogo.getByText('Elige el resultado de la revisión')).toBeInTheDocument()
    await user.selectOptions(dialogo.getByLabelText('Resultado'), 'OBSERVADA')
    await user.click(dialogo.getByRole('button', { name: 'Registrar recepción' }))
    expect(dialogo.getByText('Explica qué se encontró en la evidencia')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${FISICAS}/3/recepcion`)).toHaveLength(0)

    await user.clear(dialogo.getByLabelText('Fecha de recepción'))
    await user.type(dialogo.getByLabelText('Fecha de recepción'), '2026-11-05')
    await user.type(dialogo.getByLabelText('Observaciones (opcional)'), 'El empaque llegó abierto')
    await user.click(dialogo.getByRole('button', { name: 'Registrar recepción' }))

    expect(await screen.findByText('Recepción registrada')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${FISICAS}/3/recepcion`)
    expect(JSON.parse(opciones.body)).toEqual({ recibidaEl: '2026-11-05', resultado: 'OBSERVADA', observaciones: 'El empaque llegó abierto' })
    const s = await seccion()
    expect(s.getByText('Con observaciones')).toBeInTheDocument()
    expect(s.getByText('«El empaque llegó abierto»')).toBeInTheDocument()
    expect(s.queryByRole('button', { name: 'Registrar recepción' })).not.toBeInTheDocument()
  })

  it('conforme no exige observaciones', async () => {
    const fetchMock = comoSub({
      [`GET ${FISICAS}`]: () => [200, [copia(ENVIADA)]],
      [`POST ${FISICAS}/3/recepcion`]: () => [200, { ...copia(RECIBIDA), recepcion: { ...RECIBIDA.recepcion, resultado: 'CONFORME', observaciones: null } }],
    })
    const user = userEvent.setup()
    abrirEn('/admin/autorizaciones/41')
    await user.click(await (await seccion()).findByRole('button', { name: 'Registrar recepción' }))
    const dialogo = within(await screen.findByRole('dialog'))
    await user.selectOptions(dialogo.getByLabelText('Resultado'), 'CONFORME')
    await user.click(dialogo.getByRole('button', { name: 'Registrar recepción' }))
    expect(await screen.findByText('Recepción registrada')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${FISICAS}/3/recepcion`)).toHaveLength(1)
  })
})
