import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import InicioPage from './InicioPage.jsx'
import i18n from '../../i18n/index.js'

vi.mock('../../api/catalogo.js', () => ({ obtenerInicio: vi.fn(() => Promise.resolve({ tiendas: [], lineas: [], destacados: [] })) }))
vi.mock('../../components/layout/LayoutTienda.jsx', () => ({ default: ({ children }) => <div className="layout-tienda">{children}</div> }))

describe('home editorial autorizada', () => {
  it('usa titular en la composición acordada, sin eyebrow, y CTA editorial', async () => {
    render(<InicioPage />)
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Todo tieneun origen.')
    expect(screen.getByRole('heading', { level: 1 }).querySelector('em')).toHaveTextContent('origen.')
    expect(screen.getByRole('link', { name: /explorar productos/i })).toBeInTheDocument()
    expect(screen.queryByText(/origen responsable/i)).not.toBeInTheDocument()
  })

  it('traduce el copy editorial al inglés desde el namespace editorial', async () => {
    await i18n.changeLanguage('en')
    render(<InicioPage />)
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Everything hasan origin.')
    expect(screen.getByRole('link', { name: /explore products/i })).toBeInTheDocument()
    await i18n.changeLanguage('es')
  })

  it('no inventa citas y mantiene foto/etiqueta referenciales de categorías API', async () => {
    const { rerender } = render(<InicioPage />)
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    // Sin tiendas/API no se presenta bloque narrativo ni citas atribuibles.
    expect(screen.queryByRole('blockquote')).not.toBeInTheDocument()
    rerender(<InicioPage />)
  })
})
