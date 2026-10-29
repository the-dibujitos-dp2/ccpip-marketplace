import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import Paginacion from './Paginacion'

describe('Paginacion metadata (433)', () => {
  it.each([
    ['single page', 1],
    ['multiple pages', 3],
  ])('muestra la pagina actual y el total de registros en %s', (_caso, totalPaginas) => {
    render(<Paginacion pagina={0} totalPaginas={totalPaginas} total={3} onCambiar={vi.fn()} />)

    expect(screen.getByText(/página\s*1/i)).toBeInTheDocument()
    expect(screen.getByText(/3\s+registros/i)).toBeInTheDocument()
  })
})
