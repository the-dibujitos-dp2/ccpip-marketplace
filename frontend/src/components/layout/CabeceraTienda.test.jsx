import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import CabeceraTienda from './CabeceraTienda.jsx'

vi.mock('../../api/catalogo.js', () => ({
  obtenerCatalogos: vi.fn(() => Promise.resolve({ lineas: [{ codigo: 'CAFE', nombre: 'Café' }] })),
}))

describe('Cabecera de la tienda (CCPIP-127)', () => {
  it('al abrir la busqueda enfoca el campo y Escape la cierra devolviendo el foco al boton', async () => {
    const user = userEvent.setup()
    render(<CabeceraTienda />)
    const boton = screen.getByRole('button', { name: 'Buscar productos', expanded: false })
    await user.click(boton)
    expect(boton).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('searchbox')).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(boton).toHaveAttribute('aria-expanded', 'false')
    expect(boton).toHaveFocus()
  })

  it('el panel de lineas se abre con su boton y Escape lo cierra', async () => {
    const user = userEvent.setup()
    render(<CabeceraTienda />)
    expect(screen.queryByRole('navigation', { name: 'Líneas de producto' })).not.toBeInTheDocument()
    const boton = screen.getByRole('button', { name: 'Líneas de producto' })
    await user.click(boton)
    expect(await screen.findByRole('link', { name: 'Café' })).toHaveAttribute('href', '/buscar?linea=CAFE')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('navigation', { name: 'Líneas de producto' })).not.toBeInTheDocument()
    expect(boton).toHaveFocus()
  })

  it('el visitante ve ingresar y carrito con nombre accesible aunque solo se vea el icono', () => {
    render(<CabeceraTienda />)
    expect(screen.getByRole('link', { name: 'Ingresar' })).toHaveAttribute('href', '/ingresar')
    expect(screen.getByRole('link', { name: 'Carrito' })).toHaveAttribute('href', '/carrito')
  })
})
