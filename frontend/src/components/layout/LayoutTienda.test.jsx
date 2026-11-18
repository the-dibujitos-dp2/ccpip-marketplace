import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LayoutTienda from './LayoutTienda.jsx'

vi.mock('../../sesion.js', () => ({
  useSesion: () => ({ user: null, cerrarSesion: vi.fn(), cerrando: false }),
}))
vi.mock('./CabeceraTienda.jsx', () => ({ default: () => <header>Cabecera</header> }))
vi.mock('./PieDePagina.jsx', () => ({ default: () => <footer>Pie</footer> }))

describe('shell editorial de la tienda (CCPIP-127)', () => {
  it('conserva cabecera, contenido y pie', () => {
    render(<LayoutTienda><main>Contenido</main></LayoutTienda>)
    expect(screen.getByRole('banner')).toHaveTextContent('Cabecera')
    expect(screen.getByRole('main')).toHaveTextContent('Contenido')
    expect(screen.getByRole('contentinfo')).toHaveTextContent('Pie')
  })

  it('CCPIP-435: el Herbario es decoración pura, fuera del árbol accesible y sin foco', () => {
    render(<LayoutTienda><main><button type="button">Comprar</button></main></LayoutTienda>)
    const decoracion = screen.getByTestId('decoracion-botanica')
    expect(decoracion).toHaveAttribute('aria-hidden', 'true')
    // Sus ramas no tienen texto alternativo ni se pueden enfocar, y no hay imágenes nuevas para el lector
    const ramas = decoracion.querySelectorAll('img')
    expect(ramas).toHaveLength(2)
    ramas.forEach((rama) => {
      expect(rama).toHaveAttribute('alt', '')
      expect(rama).not.toHaveAttribute('tabindex')
    })
    expect(screen.queryAllByRole('img')).toHaveLength(0)
    // Va después del contenido: no se interpone en el orden de lectura ni de tabulación
    expect(screen.getByRole('contentinfo').compareDocumentPosition(decoracion) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['Comprar'])
  })
})
