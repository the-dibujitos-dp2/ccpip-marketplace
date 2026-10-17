import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const BANDEJA = '/api/admin/autorizaciones'
const EXPEDIENTE = `${BANDEJA}/41`

const comoSub = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUB],
    [`GET ${BANDEJA}`]: () => [200, copia(DATOS.autorizacionesAdmin)],
    [`GET ${EXPEDIENTE}`]: () => [200, copia(DATOS.expedienteAdmin)],
    ...rutas,
  })

/** El expediente que devolveria el servidor tras decidir. */
function decidido(decision, motivo, conAnalisis = false) {
  const e = copia(DATOS.expedienteAdmin)
  e.estado = decision
  e.motivo = motivo
  e.puedeDecidir = false
  e.vigenteHasta = decision === 'APROBADA' ? '2027-03-31' : null
  e.decisiones = [{ id: 1, decision, motivo, decididoPor: 'Lucía Paredes', decididoEn: '2026-10-16T17:00:00Z', conAnalisis, vigenteHasta: e.vigenteHasta }]
  return e
}

const decision = () => within(screen.getByRole('complementary', { name: 'Decisión' }))
const evidencia = (titulo) => within(screen.getByText(titulo).closest('li'))

afterEach(async () => {
  await i18n.changeLanguage('es')
})

describe('S-01 Autorizaciones de productos (CCPIP-174, CCPIP-284)', () => {
  it('lista las solicitudes pendientes con sus evidencias y el análisis', async () => {
    comoSub()
    abrirEn('/admin/autorizaciones')
    expect(await screen.findByRole('heading', { level: 1, name: 'Autorizaciones de productos' })).toBeInTheDocument()
    const kpi = (nombre) => within(screen.getByRole('region', { name: nombre }))
    await screen.findByRole('region', { name: 'Pendientes' })
    expect(kpi('Pendientes').getByText('2')).toBeInTheDocument()
    expect(kpi('Aprobadas este mes').getByText('3')).toBeInTheDocument()
    expect(kpi('Rechazadas este mes').getByText('1')).toBeInTheDocument()

    const filas = within(screen.getByRole('table', { name: 'Pendientes' })).getAllByRole('row')
    expect(filas).toHaveLength(3)
    const camu = within(filas[1])
    expect(camu.getByText('Camu camu en polvo 150 g')).toBeInTheDocument()
    expect(camu.getByText('Shipibo-Konibo · Ucayali')).toBeInTheDocument()
    expect(camu.getByText('3 archivos')).toBeInTheDocument()
    expect(camu.getByText('1 certificado vigente')).toBeInTheDocument()
    expect(camu.getByText('Con observaciones')).toBeInTheDocument()
    expect(camu.getByText('12 oct')).toBeInTheDocument()
    const cacao = within(filas[2])
    expect(cacao.getByText('Sin certificado vigente')).toBeInTheDocument()
    expect(cacao.getByText('Sin análisis')).toBeInTheDocument()

    const nav = within(screen.getByRole('navigation', { name: 'Sub administrador' }))
    expect(nav.getByRole('link', { name: 'Autorizaciones' })).toHaveAttribute('aria-current', 'page')

    await userEvent.click(camu.getByRole('link', { name: 'Revisar Camu camu en polvo 150 g de Asoc. Nii Joi' }))
    expect(window.location.pathname).toBe('/admin/autorizaciones/41')
    expect(await screen.findByRole('heading', { level: 1, name: 'Asoc. Nii Joi · Camu camu en polvo 150 g' })).toBeInTheDocument()
    // Dentro del expediente sigue marcado el modulo
    expect(within(screen.getByRole('navigation', { name: 'Sub administrador' })).getByRole('link', { name: 'Autorizaciones' }))
      .toHaveAttribute('aria-current', 'page')
  })

  it('las pestañas consultan las resueltas', async () => {
    const vacia = { ...copia(DATOS.autorizacionesAdmin), solicitudes: [] }
    const fetchMock = comoSub({ [`GET ${BANDEJA}?estado=RECHAZADA`]: [200, vacia] })
    abrirEn('/admin/autorizaciones')
    await userEvent.click(await screen.findByRole('tab', { name: 'Rechazadas' }))
    expect(await screen.findByText('Aún no rechazas solicitudes.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${BANDEJA}?estado=SOLICITADA`)).toHaveLength(1)
    expect(llamadas(fetchMock, `GET ${BANDEJA}?estado=RECHAZADA`)).toHaveLength(1)
  })

  it('el superadministrador no revisa autorizaciones: vuelve a su tablero', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_SUPER],
      'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
    })
    abrirEn('/admin/autorizaciones/41')
    expect(await screen.findByRole('heading', { level: 1, name: 'Tablero de operación' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/admin')
    expect(fetchMock.mock.calls.some(([url]) => url.startsWith(BANDEJA))).toBe(false)
    const nav = within(screen.getByRole('navigation', { name: 'Super administrador' }))
    expect(nav.queryByRole('link', { name: 'Autorizaciones' })).not.toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    comoSub()
    abrirEn('/admin/autorizaciones')
    expect(await screen.findByRole('heading', { level: 1, name: 'Product authorizations' })).toBeInTheDocument()
    expect(await screen.findByText('With findings')).toBeInTheDocument()
    expect(screen.getByText('1 valid certificate')).toBeInTheDocument()
  })
})

describe('S-02 Expediente: análisis preliminar y decisión (CCPIP-50, CCPIP-174, CCPIP-284)', () => {
  it('muestra las evidencias privadas con su vigencia y sin análisis se puede decidir', async () => {
    comoSub()
    abrirEn('/admin/autorizaciones/41')
    expect(await screen.findByText('Certificado orgánico de camu camu')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Evidencias (3)' })).toBeInTheDocument()
    expect(screen.getByText('Visibles solo para la administración y el productor')).toBeInTheDocument()
    expect(evidencia('Certificado orgánico de camu camu').getByText('Certificados · Control Unión · desde 10/01/2026 · PDF')).toBeInTheDocument()
    expect(evidencia('Certificado orgánico de camu camu').getByText('Vigente hasta 31/03/2027')).toBeInTheDocument()
    expect(evidencia('Acta comunal 2025').getByText('Sin vencimiento')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver Registro sanitario de cacao' }))
      .toHaveAttribute('href', '/api/admin/autorizaciones/41/evidencias/7a2d3b5f-2c3d-4e4f-9a01-223344556677/archivo')

    expect(decision().getByText('2 certificados vigentes del producto')).toBeInTheDocument()
    expect(decision().getByText('Sin análisis preliminar (opcional)')).toBeInTheDocument()
    expect(decision().getByText('La IA no aprueba ni rechaza. Sus observaciones son sugerencias para tu revisión.')).toBeInTheDocument()
    expect(decision().getByRole('button', { name: 'Aprobar autorización' })).toBeEnabled()
  })

  it('el análisis simulado se muestra como resultado de prueba, ligado a cada certificado, y no decide', async () => {
    const fetchMock = comoSub({ [`POST ${EXPEDIENTE}/analisis`]: () => [200, copia(DATOS.analisisSimulado)] })
    abrirEn('/admin/autorizaciones/41')
    await userEvent.click(await screen.findByRole('button', { name: 'Pedir análisis preliminar' }))

    expect(await screen.findByText('Resultado de prueba, no es un análisis real')).toBeInTheDocument()
    expect(decision().getByText(/no leyó los archivos/)).toBeInTheDocument()
    expect(decision().getByText('Con observaciones')).toBeInTheDocument()
    expect(decision().getByText('Faltan fotos o registros del proceso de elaboración.')).toBeInTheDocument()
    expect(decision().getByText('Análisis preliminar disponible')).toBeInTheDocument()
    expect(decision().getByText(/proveedor: simulado/)).toBeInTheDocument()

    const registro = evidencia('Registro sanitario de cacao')
    expect(registro.getByText(/Resultado de prueba \(sugerencia simulada\)/)).toBeInTheDocument()
    expect(registro.getByText('Revisar')).toBeInTheDocument()
    expect(registro.getByText(/El título no menciona/)).toBeInTheDocument()
    expect(evidencia('Certificado orgánico de camu camu').getByText('Conforme')).toBeInTheDocument()

    // La solicitud sigue en revision: solo una persona decide
    expect(screen.getByText('En revisión')).toBeInTheDocument()
    expect(decision().getByRole('button', { name: 'Volver a analizar' })).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${EXPEDIENTE}/aprobar`)).toHaveLength(0)
  })

  it('sin servicio de análisis pasa a revisión manual y se aprueba con motivo', async () => {
    const sinServicio = [503, { code: 'IA_NO_DISPONIBLE', error: 'El análisis asistido no está disponible.' }]
    let pedido = false
    const fetchMock = comoSub({
      [`POST ${EXPEDIENTE}/analisis`]: () => {
        pedido = true
        return sinServicio
      },
      [`GET ${EXPEDIENTE}`]: () => {
        const e = copia(DATOS.expedienteAdmin)
        if (pedido) e.analisis = { id: 8, estado: 'NO_DISPONIBLE', proveedor: 'ninguno', simulado: false, recomendacion: null, resumen: null, hallazgos: [], solicitadoEn: '2026-10-16T16:30:00Z', desactualizado: false }
        return [200, e]
      },
      [`POST ${EXPEDIENTE}/aprobar`]: () => [200, decidido('APROBADA', 'Certificados vigentes y coherentes')],
    })
    abrirEn('/admin/autorizaciones/41')
    await userEvent.click(await screen.findByRole('button', { name: 'Pedir análisis preliminar' }))
    expect(await screen.findByText('Análisis no disponible: revisión manual')).toBeInTheDocument()
    expect(decision().getByText('Revisión manual')).toBeInTheDocument()

    await userEvent.type(decision().getByLabelText('Motivo de la decisión'), 'Certificados vigentes y coherentes')
    await userEvent.click(decision().getByRole('button', { name: 'Aprobar autorización' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Aprobar Camu camu en polvo 150 g' })).toBeInTheDocument()
    expect(dialogo.getByText('Motivo: «Certificados vigentes y coherentes»')).toBeInTheDocument()
    await userEvent.click(dialogo.getByRole('button', { name: 'Aprobar' }))

    expect(await screen.findByText('Aprobaste la solicitud. El producto ya se puede comprar.')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${EXPEDIENTE}/aprobar`)
    expect(JSON.parse(opciones.body)).toEqual({ motivo: 'Certificados vigentes y coherentes' })
    expect(screen.getByText('Autorizada hasta el 31/03/2027.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Aprobar autorización' })).not.toBeInTheDocument()
    const historial = within(screen.getByRole('region', { name: 'Historial de decisiones' }))
    expect(historial.getByText('«Certificados vigentes y coherentes»')).toBeInTheDocument()
    expect(historial.getByText(/Lucía Paredes · .* · revisión manual/)).toBeInTheDocument()
  })

  it('el motivo es obligatorio y el rechazo se confirma en un diálogo', async () => {
    const fetchMock = comoSub({
      [`POST ${EXPEDIENTE}/rechazar`]: () => [200, decidido('RECHAZADA', 'El registro sanitario es de otro producto')],
    })
    abrirEn('/admin/autorizaciones/41')
    await userEvent.click(await screen.findByRole('button', { name: 'Rechazar solicitud' }))
    expect(decision().getByText('Escribe el motivo de la decisión')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await userEvent.type(decision().getByLabelText('Motivo de la decisión'), '   El registro sanitario es de otro producto  ')
    await userEvent.click(decision().getByRole('button', { name: 'Rechazar solicitud' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByText(/Asoc\. Nii Joi verá este motivo/)).toBeInTheDocument()
    // Cancelar no envia nada
    await userEvent.click(dialogo.getByRole('button', { name: 'Cancelar' }))
    expect(llamadas(fetchMock, `POST ${EXPEDIENTE}/rechazar`)).toHaveLength(0)

    await userEvent.click(decision().getByRole('button', { name: 'Rechazar solicitud' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Rechazar solicitud' }))
    expect(await screen.findByText('Rechazaste la solicitud. El productor verá tu motivo.')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, `POST ${EXPEDIENTE}/rechazar`)[0][1].body)).toEqual({ motivo: 'El registro sanitario es de otro producto' })
    expect(within(screen.getByRole('region', { name: 'Solicitud' })).getByText('Rechazada')).toBeInTheDocument()
  })

  it('sin certificado vigente propio no se puede aprobar, pero sí rechazar', async () => {
    const sinCertificado = { ...copia(DATOS.expedienteAdmin), certificadosVigentes: 0 }
    comoSub({ [`GET ${EXPEDIENTE}`]: [200, sinCertificado] })
    abrirEn('/admin/autorizaciones/41')
    expect(await screen.findByText('Para aprobar, el producto necesita al menos un certificado vigente propio.')).toBeInTheDocument()
    expect(decision().getByRole('button', { name: 'Aprobar autorización' })).toBeDisabled()
    expect(decision().getByRole('button', { name: 'Rechazar solicitud' })).toBeEnabled()
  })

  it('si otra persona ya decidió, lo dice y recarga el expediente', async () => {
    let resuelta = false
    comoSub({
      [`GET ${EXPEDIENTE}`]: () => [200, resuelta ? decidido('APROBADA', 'Ya revisado') : copia(DATOS.expedienteAdmin)],
      [`POST ${EXPEDIENTE}/rechazar`]: () => {
        resuelta = true
        return [409, { code: 'SOLICITUD_YA_RESUELTA', error: 'Esta solicitud ya no está pendiente de revisión.' }]
      },
    })
    abrirEn('/admin/autorizaciones/41')
    await userEvent.type(await screen.findByLabelText('Motivo de la decisión'), 'Ilegible')
    await userEvent.click(decision().getByRole('button', { name: 'Rechazar solicitud' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Rechazar solicitud' }))
    expect(await screen.findByText('Esta solicitud ya no está pendiente de revisión.')).toBeInTheDocument()
    expect(await screen.findByText('Autorizada hasta el 31/03/2027.')).toBeInTheDocument()
  })

  it('una solicitud que no existe muestra el error y la salida', async () => {
    comoSub({ [`GET ${EXPEDIENTE}`]: [404, { code: 'SOLICITUD_NO_ENCONTRADA', error: 'La solicitud no existe.' }] })
    abrirEn('/admin/autorizaciones/41')
    expect(await screen.findByText('La solicitud no existe.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Volver a autorizaciones' })).toHaveAttribute('href', '/admin/autorizaciones')
  })

  it('en inglés el resultado simulado también se marca como prueba', async () => {
    await i18n.changeLanguage('en')
    comoSub({ [`GET ${EXPEDIENTE}`]: [200, { ...copia(DATOS.expedienteAdmin), analisis: copia(DATOS.analisisSimulado) }] })
    abrirEn('/admin/autorizaciones/41')
    expect(await screen.findByText('Test result, not a real analysis')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Approve authorization' })).toBeInTheDocument()
    expect(screen.getByText('The AI neither approves nor rejects. Its findings are suggestions for your review.')).toBeInTheDocument()
  })
})
