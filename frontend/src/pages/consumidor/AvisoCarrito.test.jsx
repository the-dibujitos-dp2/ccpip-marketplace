import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, PUBLICAS, servidor, SESION } from '../../test/servidor.js'

// CCPIP-414: aviso lateral al agregar un producto al carrito
const conSesion = {
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  'GET /api/productos/1': [200, copia(DATOS.detalle)],
  'GET /api/carrito': [200, { items: [], unidades: 1, comunidades: 1, total: 42 }],
  'POST /api/carrito/items': [200, { productoId: 1, cantidad: 1, articulos: 1 }],
}

function abrirFicha() {
  window.history.replaceState(null, '', '/productos/1')
  return render(<App />)
}

/** Reloj simulado que sigue avanzando para que las esperas de Testing Library no se cuelguen. */
function relojSimulado() {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  return userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
}

const avisoLateral = () => document.querySelector('.aviso-lateral')

async function agregar(user) {
  await user.click(await screen.findByRole('button', { name: 'Agregar al carrito' }))
  await screen.findByText(`Agregaste «${DATOS.detalle.nombre}» al carrito`)
  return avisoLateral()
}

describe('CCPIP-414 Aviso lateral al agregar al carrito', () => {
  it('muestra que producto se agrego, con enlace al carrito, y se va solo a los segundos', async () => {
    servidor(conSesion)
    const user = relojSimulado()
    abrirFicha()

    const aviso = await agregar(user)
    expect(aviso).toHaveAttribute('role', 'status')
    expect(aviso).toHaveAttribute('aria-live', 'polite')
    expect(within(aviso).getByRole('link', { name: 'Ver carrito' })).toHaveAttribute('href', '/carrito')
    // La confirmacion dentro de la ficha se mantiene
    expect(screen.getByText('Agregaste 1 unidad a tu carrito')).toBeInTheDocument()

    await act(() => vi.advanceTimersByTimeAsync(3500))
    expect(avisoLateral()).toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(600))
    expect(avisoLateral()).not.toBeInTheDocument()
  })

  it('se cierra con su boton', async () => {
    servidor(conSesion)
    const user = userEvent.setup()
    abrirFicha()

    const aviso = await agregar(user)
    await user.click(within(aviso).getByRole('button', { name: 'Cerrar aviso' }))
    expect(avisoLateral()).not.toBeInTheDocument()
    // Cerrar el aviso lateral no quita la confirmacion de la ficha
    expect(screen.getByText('Agregaste 1 unidad a tu carrito')).toBeInTheDocument()
  })

  it('no se va mientras el puntero esta encima y agregar de nuevo reinicia el tiempo', async () => {
    servidor(conSesion)
    const user = relojSimulado()
    abrirFicha()

    const aviso = await agregar(user)
    await user.hover(aviso)
    await act(() => vi.advanceTimersByTimeAsync(6000))
    expect(avisoLateral()).toBeInTheDocument()
    await user.unhover(aviso)

    await act(() => vi.advanceTimersByTimeAsync(3000))
    await user.click(screen.getByRole('button', { name: 'Agregar al carrito' }))
    await screen.findByText('Agregaste 1 unidad a tu carrito')
    await act(() => vi.advanceTimersByTimeAsync(3000))
    expect(avisoLateral()).toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(1100))
    expect(avisoLateral()).not.toBeInTheDocument()
  })

  it('en ingles', async () => {
    servidor(conSesion)
    await i18n.changeLanguage('en')
    const user = userEvent.setup()
    abrirFicha()

    await user.click(await screen.findByRole('button', { name: 'Add to cart' }))
    expect(await screen.findByText(`You added “${DATOS.detalle.nombre}” to your cart`)).toBeInTheDocument()
    expect(within(avisoLateral()).getByRole('link', { name: 'View cart' })).toBeInTheDocument()
  })
})
