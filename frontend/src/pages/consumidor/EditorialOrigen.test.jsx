import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import InicioPage from './InicioPage.jsx'

vi.mock('../../api/catalogo.js', () => ({ obtenerInicio: vi.fn(() => new Promise(() => {})) }))
vi.mock('../../components/layout/LayoutTienda.jsx', () => ({ default: ({ children }) => <div className="layout-tienda">{children}</div> }))

describe('portada editorial de origen', () => {
  it('presenta fotografía documental como hero fullbleed y acciones accesibles', () => {
    render(<InicioPage />)
    expect(screen.getByRole('img', { name: /fotografía documental/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveClass('hero__titulo')
    expect(screen.getByRole('link', { name: /explorar/i })).toBeInTheDocument()
  })
})
