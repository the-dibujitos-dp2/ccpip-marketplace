import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import Logotipo from './Logotipo.jsx'

describe('Logotipo oficial', () => {
  it('muestra el archivo oficial en variantes normal e inversa', () => {
    const { rerender } = render(<Logotipo />)
    expect(screen.getByRole('img', { name: /mercado ccpip/i })).toBeInTheDocument()
    rerender(<Logotipo inverso />)
    expect(screen.getByRole('img', { name: /mercado ccpip/i })).toBeInTheDocument()
  })
})
