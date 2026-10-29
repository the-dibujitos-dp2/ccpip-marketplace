import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { TablaTodosLosLotes, TablaLotes } from './Lotes.jsx'

const lotes = Array.from({ length: 21 }, (_, i) => ({
  id: i + 1,
  productoId: 71,
  producto: `Producto ${i + 1}`,
  codigo: `LOTE-${String(i + 1).padStart(2, '0')}`,
  fechaVencimiento: null,
  cantidadActual: 10 + i,
  unidad: 'kg',
  tipoUbicacion: 'ALMACEN_PRODUCTOR',
  ubicacion: 'Almacén productor',
  estado: 'DISPONIBLE',
}))

describe('Paginación local de lotes (CCPIP-433)', () => {
  it('muestra total y cambia a la página 2 en todos los lotes', async () => {
    const user = userEvent.setup()
    render(<TablaTodosLosLotes lotes={lotes} />)
    expect(screen.getByRole('navigation', { name: 'Paginación' })).toHaveTextContent('Página 1 · 21 registros')
    expect(screen.getByText('LOTE-01')).toBeInTheDocument()
    expect(screen.queryByText('LOTE-21')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))
    expect(screen.getByRole('navigation', { name: 'Paginación' })).toHaveTextContent('Página 2 · 21 registros')
    expect(screen.getByText('LOTE-21')).toBeInTheDocument()
    expect(screen.queryByText('LOTE-01')).not.toBeInTheDocument()
  })

  it('también pasa el total y navega en los lotes de un producto', async () => {
    const user = userEvent.setup()
    render(<TablaLotes lotes={{ productoId: 71, items: lotes }} />)
    const lista = within(screen.getByRole('list', { name: 'Lotes de este producto' }))
    expect(screen.getByRole('navigation', { name: 'Paginación' })).toHaveTextContent('Página 1 · 21 registros')
    expect(lista.getByText('LOTE-01')).toBeInTheDocument()
    expect(lista.queryByText('LOTE-21')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))
    expect(screen.getByRole('navigation', { name: 'Paginación' })).toHaveTextContent('Página 2 · 21 registros')
    expect(lista.getByText('LOTE-21')).toBeInTheDocument()
    expect(lista.queryByText('LOTE-01')).not.toBeInTheDocument()
  })
})
