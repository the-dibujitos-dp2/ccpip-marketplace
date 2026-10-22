import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, PUBLICAS, servidor, SIN_SESION } from '../../test/servidor.js'

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

  it('sin evidencias publicas no afirma una aprobacion', async () => {
    servidor({
      ...sinSesion,
      'GET /api/productos/1/certificacion': () => [200, { productoId: 1, estado: 'SIN_EVIDENCIA', verificadaEn: null, vigenteHasta: null, evidencias: [] }],
    })
    abrirEn('/productos/1')
    const seccion = await screen.findByRole('region', { name: 'Certificación del producto' })
    expect(within(seccion).getByText('Sin certificados públicos')).toBeInTheDocument()
    expect(within(seccion).getByText(/no significa que esté aprobado ni rechazado/)).toBeInTheDocument()
    expect(within(seccion).queryByRole('list')).not.toBeInTheDocument()
  })

  it('si la consulta falla, la ficha se muestra sin la seccion', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/certificacion': () => [500, {}] })
    abrirEn('/productos/1')
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Certificación del producto' })).not.toBeInTheDocument()
  })
})
