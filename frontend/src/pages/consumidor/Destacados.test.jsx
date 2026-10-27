import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, PUBLICAS, servidor, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

function visitante(inicio = copia(DATOS.inicio)) {
  return servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'GET /api/inicio': () => [200, inicio] })
}

describe('CCPIP-57 Destacados del inicio', () => {
  it('muestra los destacados con su productor y la regla con que se eligen', async () => {
    visitante()
    abrirEn('/')
    const lista = await screen.findByRole('list', { name: 'Destacados' })
    const [primero] = DATOS.inicio.destacados
    const items = within(lista).getAllByRole('listitem')
    expect(items).toHaveLength(DATOS.inicio.destacados.length)
    expect(within(items[0]).getByText(primero.nombre)).toBeInTheDocument()
    expect(within(items[0]).getByText(new RegExp(primero.tienda.nombre))).toBeInTheDocument()
    expect(screen.getByText(/Aquí va uno por comunidad, de los que están en venta/)).toBeInTheDocument()
  })

  it('si ninguna comunidad destacó productos lo dice en vez de rellenar', async () => {
    visitante({ ...copia(DATOS.inicio), destacados: [] })
    abrirEn('/')
    expect(await screen.findByText('Las comunidades aún no han destacado productos en venta.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Destacados' })).not.toBeInTheDocument()
  })
})
