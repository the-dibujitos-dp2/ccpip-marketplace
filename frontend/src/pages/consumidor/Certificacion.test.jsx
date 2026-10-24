import { act, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const sinSesion = { ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'GET /api/productos/1': () => [200, copia(DATOS.detalle)] }

const VERIFICADA = {
  productoId: 1,
  estado: 'VERIFICADA',
  verificadaEn: '2026-10-05T15:00:00Z',
  vigenteHasta: '2027-05-09',
  evidencias: [
    { tipo: 'CERTIFICADO', titulo: 'Certificado orgánico', emisor: 'SENASA', vigenteDesde: '2026-09-21', vigenteHasta: '2027-05-09', vigente: true },
    { tipo: 'EVIDENCIA', titulo: 'Fotos de la parcela', emisor: null, vigenteDesde: null, vigenteHasta: null, vigente: true },
  ],
}

describe('CCPIP-365 Evidencias publicas de certificacion (CW-03)', () => {
  it('el visitante ve emisor, fecha y vigencia de cada evidencia y su significado', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/certificacion': () => [200, copia(VERIFICADA)] })
    abrirEn('/productos/1')
    const seccion = await screen.findByRole('region', { name: 'Certificación del producto' })
    expect(within(seccion).getByText('Verificado por la Cámara')).toBeInTheDocument()
    expect(within(seccion).getByText(/aprobó la venta del producto el 5 de octubre de 2026/)).toBeInTheDocument()

    const [certificado, foto] = within(within(seccion).getByRole('list', { name: 'Evidencias públicas' })).getAllByRole('listitem')
    expect(within(certificado).getByText('Certificado orgánico')).toBeInTheDocument()
    expect(within(certificado).getByText('SENASA')).toBeInTheDocument()
    expect(within(certificado).getByText(/^21 \S+ 2026$/)).toBeInTheDocument()
    expect(within(certificado).getByText('vigente hasta el 9 may 2027')).toBeInTheDocument()
    expect(within(certificado).getByText(/acredita una condición del producto/)).toBeInTheDocument()
    expect(within(foto).getByText('sin fecha de vencimiento')).toBeInTheDocument()
    // Nunca un enlace al archivo
    expect(within(seccion).queryByRole('link')).not.toBeInTheDocument()
  })

  it('con el certificado vencido no dice verificado', async () => {
    servidor({
      ...sinSesion,
      'GET /api/productos/1/certificacion': () => [200, {
        ...copia(VERIFICADA), estado: 'VENCIDA',
        evidencias: [{ ...VERIFICADA.evidencias[0], vigenteHasta: '2026-10-01', vigente: false }],
      }],
    })
    abrirEn('/productos/1')
    const seccion = await screen.findByRole('region', { name: 'Certificación del producto' })
    expect(within(seccion).getByText('Certificación vencida')).toBeInTheDocument()
    expect(within(seccion).getByText('venció el 1 oct 2026')).toBeInTheDocument()
    expect(within(seccion).queryByText('Verificado por la Cámara')).not.toBeInTheDocument()
  })

  // CCPIP-408: sin certificaciones no hay seccion vacia (ni afirma una aprobacion)
  it('sin evidencias publicas la ficha no muestra la seccion de certificaciones', async () => {
    const fetchMock = servidor({
      ...sinSesion,
      'GET /api/productos/1/certificacion': () => [200, { productoId: 1, estado: 'SIN_EVIDENCIA', verificadaEn: null, vigenteHasta: null, evidencias: [] }],
    })
    abrirEn('/productos/1')
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/productos/1/certificacion')).toHaveLength(1))
    await act(() => Promise.resolve())
    expect(screen.queryByRole('region', { name: 'Certificación del producto' })).not.toBeInTheDocument()
    expect(screen.queryByText('Sin certificados públicos')).not.toBeInTheDocument()
    expect(screen.queryByText(/Verificado por la Cámara/)).not.toBeInTheDocument()
  })

  it('con una sola evidencia la seccion se muestra como siempre', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/certificacion': () => [200, { ...copia(VERIFICADA), evidencias: [VERIFICADA.evidencias[1]] }] })
    abrirEn('/productos/1')
    const seccion = await screen.findByRole('region', { name: 'Certificación del producto' })
    expect(within(within(seccion).getByRole('list', { name: 'Evidencias públicas' })).getAllByRole('listitem')).toHaveLength(1)
    expect(within(seccion).getByText('Fotos de la parcela')).toBeInTheDocument()
  })

  it('si la consulta falla, la ficha se muestra sin la seccion', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/certificacion': () => [500, {}] })
    abrirEn('/productos/1')
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Certificación del producto' })).not.toBeInTheDocument()
  })
})
