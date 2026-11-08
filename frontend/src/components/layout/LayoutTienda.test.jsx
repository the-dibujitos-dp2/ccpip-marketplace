import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LayoutTienda from './LayoutTienda.jsx'

vi.mock('../../sesion.js', () => ({
  useSesion: () => ({ user: null, cerrarSesion: vi.fn(), cerrando: false }),
}))
vi.mock('./CabeceraTienda.jsx', () => ({ default: () => <header>Cabecera</header> }))
vi.mock('./PieDePagina.jsx', () => ({ default: () => <footer>Pie</footer> }))

describe('shell editorial de la tienda aprobado en CCPIP-127', () => {
  it('conserva cabecera, contenido y pie sin montar la decoración lateral anterior', () => {
    const { container } = render(<LayoutTienda><main>Contenido</main></LayoutTienda>)
    expect(screen.getByRole('banner')).toHaveTextContent('Cabecera')
    expect(screen.getByRole('main')).toHaveTextContent('Contenido')
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Pie')
    expect(screen.queryByTestId('decoracion-botanica')).not.toBeInTheDocument()
    expect(container.querySelector('[class*="botanica"]')).toBeNull()
  })
})
