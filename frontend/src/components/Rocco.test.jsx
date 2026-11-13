import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import EstadoVacio from './EstadoVacio.jsx'
import Rocco, { RoccoDice, VARIANTES } from './Rocco.jsx'

describe('CCPIP-513 Rocco, la mascota', () => {
  it('es decorativo: el lector de pantalla lo ignora', () => {
    const { container } = render(<Rocco variante="feliz" tamano={80} />)
    const img = container.querySelector('img')
    expect(img).toHaveAttribute('alt', '')
    expect(img).toHaveAttribute('aria-hidden', 'true')
    expect(img).toHaveAttribute('width', '80')
    expect(img).toHaveAttribute('height', '88')
    expect(img).toHaveAttribute('data-rocco', 'feliz')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('tiene las 8 variantes acordadas y una desconocida cae en el saludo', () => {
    expect(Object.keys(VARIANTES)).toEqual(['rama', 'saluda', 'feliz', 'emocionado', 'sorprendido', 'pensativo', 'guino', 'triste'])
    const { container } = render(<Rocco variante="no-existe" />)
    expect(container.querySelector('img').getAttribute('src')).toBe(VARIANTES.saluda)
  })

  it('animado solo agrega la clase; la animación respeta "reducir movimiento" en el CSS', () => {
    const { container } = render(<Rocco variante="pensativo" animado />)
    expect(container.querySelector('img')).toHaveClass('rocco', 'rocco--animado')
  })

  it('con globo, el texto es lo que se lee', () => {
    render(<RoccoDice variante="guino">Consejo de Rocco</RoccoDice>)
    expect(screen.getByText('Consejo de Rocco')).toBeInTheDocument()
  })

  it('el estado vacío muestra a Rocco solo si se le pide', () => {
    const { container, rerender } = render(<EstadoVacio titulo="Vacío" rocco="sorprendido" />)
    expect(container.querySelector('[data-rocco="sorprendido"]')).toBeInTheDocument()
    expect(container.querySelector('.estado-vacio__icono')).not.toBeInTheDocument()
    rerender(<EstadoVacio titulo="Vacío" />)
    expect(container.querySelector('[data-rocco]')).not.toBeInTheDocument()
    expect(container.querySelector('.estado-vacio__icono')).toBeInTheDocument()
  })
})
