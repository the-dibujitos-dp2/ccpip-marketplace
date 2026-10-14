import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, PUBLICAS, servidor, SESION, SIN_SESION } from '../../test/servidor.js'

// CCPIP-98: las pantallas de la tienda en ingles, de punta a punta, sin restos en espanol
function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const sinSesion = { ...PUBLICAS, 'GET /api/auth/me': SIN_SESION }

describe('Tienda en inglés (CCPIP-98)', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('el inicio usa los textos en inglés', async () => {
    servidor(sinSesion)
    abrirEn('/')
    expect(await screen.findByRole('heading', { name: 'Products with a story, straight from the Amazon' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Explore products' }).length).toBeGreaterThan(0)
    expect(screen.queryByText('Explorar productos')).not.toBeInTheDocument()
  })

  it('la búsqueda cuenta productos y comunidades en inglés', async () => {
    servidor(sinSesion)
    abrirEn('/buscar?q=cacao')
    expect(await screen.findByRole('heading', { name: 'Results for “cacao”' })).toBeInTheDocument()
    const { total, totalComunidades } = DATOS.buscar
    const productos = `${total} ${total === 1 ? 'product' : 'products'}`
    const comunidades = `${totalComunidades} ${totalComunidades === 1 ? 'community' : 'communities'}`
    expect(await screen.findByText(`${productos} from ${comunidades}`)).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Sort: Relevance' })).toBeInTheDocument()
  })

  it('el inicio de sesión valida y explica los errores en inglés', async () => {
    servidor({ ...sinSesion, 'POST /api/auth/login': [401, { code: 'CREDENCIALES_INVALIDAS', error: 'Correo o contrasena incorrectos' }] })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await user.click(await screen.findByRole('button', { name: 'Log in' }))
    expect(screen.getByText('Enter a valid email, for example name@email.com')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Email'), 'consumidor@ccpip.test')
    await user.type(screen.getByLabelText('Password'), 'incorrecta')
    await user.click(screen.getByRole('button', { name: 'Log in' }))
    expect(await screen.findByText('The email or password is incorrect.')).toBeInTheDocument()
  })

  it('un error del servidor se muestra por su código, no con el mensaje en español', async () => {
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION],
      'GET /api/carrito': () => [200, copia(DATOS.carrito)],
      ['PUT /api/carrito/items/' + DATOS.carrito.items[0].productoId]: [409, { code: 'VENTA_PAUSADA', error: 'La venta de este producto está pausada por ahora.' }],
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    expect(await screen.findByRole('heading', { name: 'Your cart' })).toBeInTheDocument()
    expect(await screen.findByText('Order summary')).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Add one unit' })[0])
    expect(await screen.findByText(/Sales of this product are paused for now/, {}, { timeout: 2000 })).toBeInTheDocument()
    expect(screen.queryByText(/pausada/)).not.toBeInTheDocument()
  })
})
