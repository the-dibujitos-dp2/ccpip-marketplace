import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, PUBLICAS, servidor, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const sinSesion = { ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'GET /api/productos/1': () => [200, copia(DATOS.detalle)] }

const PREMIOS = [
  { titulo: 'Medalla de oro', emisor: 'Salón del Cacao y Chocolate', otorgadoEn: '2026-10-01', vigenteHasta: '2027-10-01', vigente: true, cosecha: '2026-09-04' },
  { titulo: 'Premio regional', emisor: 'Gobierno Regional', otorgadoEn: '2024-05-10', vigenteHasta: '2025-05-10', vigente: false },
  { titulo: 'Reconocimiento', emisor: 'Municipalidad', otorgadoEn: '2023-01-15', vigente: true },
]

describe('CCPIP-169 Premios del producto en la ficha (CW-03)', () => {
  it('el visitante ve emisor, fecha y vigencia de cada premio, como declarado por el productor', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/premios': () => [200, copia(PREMIOS)] })
    abrirEn('/productos/1')
    const seccion = within(await screen.findByRole('region', { name: 'Premios' }))
    const [oro, regional, reconocimiento] = seccion.getAllByRole('listitem')
    expect(within(oro).getByText('Medalla de oro')).toBeInTheDocument()
    expect(within(oro).getByText('Salón del Cacao y Chocolate')).toBeInTheDocument()
    expect(within(oro).getByText('vigente hasta el 1 oct 2027')).toBeInTheDocument()
    expect(within(oro).getByText('producido el 4 set 2026')).toBeInTheDocument()
    expect(within(regional).getByText('venció el 10 may 2025')).toBeInTheDocument()
    expect(within(reconocimiento).getByText('sin fecha de vencimiento')).toBeInTheDocument()
    expect(within(reconocimiento).queryByText('Lote:')).not.toBeInTheDocument()
    // No dice que la Camara lo aprobo ni ofrece el archivo
    expect(seccion.getByText(/declarados por el productor.*La Cámara no los certifica/)).toBeInTheDocument()
    expect(seccion.queryByRole('link')).not.toBeInTheDocument()
  })

  it('sin premios, o si la consulta falla, la ficha sigue sin la sección', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1/premios': () => [200, []] })
    abrirEn('/productos/1')
    expect(await screen.findByRole('heading', { level: 1, name: DATOS.detalle.nombre })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Premios' })).not.toBeInTheDocument()
  })
})
