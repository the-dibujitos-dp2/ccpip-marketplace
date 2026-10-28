import { act, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const sinSesion = { ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'GET /api/productos/1': () => [200, copia(DATOS.detalle)] }

const ORIGEN = { tipo: 'ORIGEN_VERIFICADO', titulo: 'Comunidad Shipibo Konibo', emisor: 'Ucayali', desde: '2026-06-15', hasta: null }
const ORGANICO = { tipo: 'CERTIFICADO', titulo: 'Certificado orgánico', emisor: 'SENASA', desde: '2026-09-21', hasta: '2027-05-09' }

const confianza = () => screen.findByRole('region', { name: 'Por qué puedes confiar en este producto' })

describe('CCPIP-38 Origen verificado y significado de los sellos (CW-03)', () => {
  it('el visitante ve el origen verificado con su significado y los certificados que lo respaldan', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/sellos': () => [200, { productoId: 1, sellos: [ORIGEN, ORGANICO] }] })
    abrirEn('/productos/1')
    const seccion = await confianza()
    expect(await within(seccion).findByText('Origen verificado por la CCPIP')).toBeInTheDocument()
    expect(within(seccion).getByText(/La Cámara verificó que este producto lo elabora Comunidad Shipibo Konibo \(Ucayali\)/)).toBeInTheDocument()
    expect(within(seccion).getByText(/Verificado el 15 jun 2026/)).toBeInTheDocument()
    const certificados = within(seccion).getByRole('list', { name: 'Certificados vigentes revisados por la Cámara' })
    expect(within(certificados).getByText('Certificado orgánico')).toBeInTheDocument()
    expect(within(certificados).getByText('Emitido por SENASA · vigente hasta el 9 may 2027')).toBeInTheDocument()
  })

  it('no atribuye reglas que nadie acredito', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/sellos': () => [200, { productoId: 1, sellos: [ORIGEN] }] })
    abrirEn('/productos/1')
    const seccion = await confianza()
    await within(seccion).findByText('Origen verificado por la CCPIP')
    expect(within(seccion).queryByText('Cero deforestación')).not.toBeInTheDocument()
    expect(within(seccion).queryByText(/Cero pesticidas/)).not.toBeInTheDocument()
    expect(within(seccion).queryByRole('list', { name: 'Certificados vigentes revisados por la Cámara' })).not.toBeInTheDocument()
  })

  it('solo con certificados no dice que el origen este verificado', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/sellos': () => [200, { productoId: 1, sellos: [ORGANICO] }] })
    abrirEn('/productos/1')
    const seccion = await confianza()
    expect(await within(seccion).findByText('Certificado orgánico')).toBeInTheDocument()
    expect(within(seccion).queryByText('Origen verificado por la CCPIP')).not.toBeInTheDocument()
  })

  it('sin sellos lo dice sin afirmar ni negar una aprobacion', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/sellos': () => [200, { productoId: 1, sellos: [] }] })
    abrirEn('/productos/1')
    const seccion = await confianza()
    expect(await within(seccion).findByText(/todavía no tiene un origen verificado ni certificados vigentes/)).toBeInTheDocument()
    expect(within(seccion).queryByText('Origen verificado por la CCPIP')).not.toBeInTheDocument()
  })

  it('si la consulta falla no afirma ningun sello', async () => {
    const fetchMock = servidor({ ...sinSesion, 'GET /api/productos/1/sellos': () => [500, {}] })
    abrirEn('/productos/1')
    const seccion = await confianza()
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/productos/1/sellos')).toHaveLength(1))
    await act(() => Promise.resolve())
    expect(within(seccion).queryByText('Origen verificado por la CCPIP')).not.toBeInTheDocument()
  })

  it('en ingles el significado tambien se traduce', async () => {
    await i18n.changeLanguage('en')
    servidor({ ...sinSesion, 'GET /api/productos/1/sellos': () => [200, { productoId: 1, sellos: [ORIGEN, ORGANICO] }] })
    abrirEn('/productos/1')
    expect(await screen.findByText(/The Chamber verified that this product is made by Comunidad Shipibo Konibo/)).toBeInTheDocument()
    expect(screen.getByText(/Issued by SENASA/)).toBeInTheDocument()
  })
})
