import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { render, screen } from '@testing-library/react'
import EstadoVacio from './EstadoVacio.jsx'

const rutasEditorial = readFileSync(new URL('../pages/consumidor/RutasEditorial.css', `file://${process.cwd()}/src/components/EstadoVacio.editorial.test.jsx`), 'utf8')

describe('EstadoVacio consumidor editorial', () => {
  it('presenta estados vacíos planos, con icono y tipografía de la dirección editorial', () => {
    render(<div className="layout-tienda"><EstadoVacio titulo="Tu carrito está vacío" descripcion="Explora productos de temporada." /></div>)

    expect(screen.getByRole('status')).toHaveClass('estado-vacio')
    expect(screen.getByText('Tu carrito está vacío')).toHaveClass('estado-vacio__titulo')
    expect(rutasEditorial).toMatch(/\.layout-tienda \.estado-vacio\s*\{[^}]*border-radius:\s*var\(--editorial-radius\)/s)
    expect(rutasEditorial).toMatch(/\.layout-tienda \.estado-vacio__icono\s*\{[^}]*border-radius:\s*var\(--editorial-radius\)/s)
    expect(rutasEditorial).toMatch(/\.layout-tienda \.estado-vacio__icono\s*\{[^}]*background:\s*var\(--color-bg-brand-subtle\)/s)
    expect(rutasEditorial).toMatch(/\.layout-tienda \.estado-vacio__titulo\s*\{[^}]*font-family:\s*var\(--consumer-serif,\s*'Fraunces'/s)
  })
})
