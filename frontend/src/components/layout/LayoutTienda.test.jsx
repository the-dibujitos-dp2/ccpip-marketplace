import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LayoutTienda from './LayoutTienda.jsx'
import botanicaIzquierda from './botanica-izquierda.svg'
import botanicaDerecha from './botanica-derecha.svg'

vi.mock('../../sesion.js', () => ({
  useSesion: () => ({ user: null, cerrarSesion: vi.fn(), cerrando: false }),
}))
vi.mock('./CabeceraTienda.jsx', () => ({ default: () => <header>Cabecera</header> }))
vi.mock('./PieDePagina.jsx', () => ({ default: () => <footer>Pie</footer> }))

describe('decoración botánica de la tienda', () => {
  it('se sitúa tras el contenido sin interceptar interacción ni tomar foco', () => {
    render(<LayoutTienda><main>Contenido</main></LayoutTienda>)
    const decor = screen.getByTestId('decoracion-botanica')
    expect(decor).toHaveAttribute('aria-hidden', 'true')
    expect(decor).toHaveStyle({ pointerEvents: 'none' })
    expect(decor.querySelectorAll('a, button, input, [tabindex]')).toHaveLength(0)
    expect(decor.querySelector('main')).toBeNull()
    expect(screen.getByRole('main')).toHaveTextContent('Contenido')
    expect([botanicaIzquierda, botanicaDerecha]).toContain(decor.querySelector('img').getAttribute('src'))
  })
})
