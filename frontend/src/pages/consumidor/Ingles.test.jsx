import { render, screen, within } from '@testing-library/react'
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

  it('entrega, pago y confirmación en inglés, con el rechazo explicado sin español', async () => {
    const punto = { id: 1, nombre: 'Centro Nacional Lima', departamento: 'Lima', distrito: 'Lima', direccion: 'Sede central CCPIP' }
    const pedido = {
      id: 77, numero: 'CCP-000077', estado: 'PENDIENTE_PAGO', modalidad: 'RECOJO', total: 812, creadoEn: '2026-10-14T15:00:00Z',
      punto, personaAutorizada: null, celularContacto: '987654321',
      lineas: [{ productoId: 8, nombre: 'Aceite de aguaje 250 ml', tienda: 'Asoc. Nii Joi', cantidad: 14, precioUnitario: 58, subtotal: 812 }],
      pagos: [],
    }
    const aprobado = { id: 1, estado: 'APROBADO', monto: 812, marca: 'VISA', referencia: 'SIM-123', creadoEn: '2026-10-14T15:01:00Z' }
    const confirmado = { ...pedido, estado: 'CONFIRMADO', pagos: [aprobado] }
    let intentos = 0
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION],
      'GET /api/carrito': [200, copia(DATOS.carrito)],
      'GET /api/compra/puntos': [200, [punto]],
      'POST /api/pedidos': [201, pedido],
      'GET /api/pedidos/77': [200, confirmado],
      'POST /api/pedidos/77/pagos': () =>
        ++intentos === 1
          ? [200, { estado: 'RECHAZADO', mensaje: 'Tu banco rechazó el pago por fondos insuficientes.', pedido }]
          : [200, { estado: 'APROBADO', mensaje: 'Pago aprobado.', pedido: confirmado }],
    })
    const user = userEvent.setup()
    abrirEn('/compra')
    expect(await screen.findByRole('heading', { name: 'Delivery and payment' })).toBeInTheDocument()
    await screen.findByRole('radio', { name: /Centro Nacional Lima/ })
    await user.type(screen.getByLabelText('Contact mobile number'), '987654321')
    await user.type(screen.getByLabelText('Card number'), '4000 0000 0000 0002')
    await user.type(screen.getByLabelText('Cardholder name'), 'Ana Quispe')
    await user.selectOptions(screen.getByLabelText('Month'), '12')
    await user.selectOptions(screen.getByLabelText('Year'), String(new Date().getFullYear() + 2))
    await user.type(screen.getByLabelText('CVV'), '123')
    await user.click(screen.getByRole('button', { name: /^Pay S\/\s/ }))

    const alerta = await screen.findByRole('alert')
    expect(within(alerta).getByText('Your payment was declined')).toBeInTheDocument()
    expect(within(alerta).getByText('Your bank declined the payment. Try another card.')).toBeInTheDocument()

    await user.type(screen.getByLabelText('CVV'), '123')
    await user.click(screen.getByRole('button', { name: /^Pay S\/\s/ }))
    expect(await screen.findByRole('heading', { name: 'Thank you for your purchase!' })).toBeInTheDocument()
    expect(screen.getByText('CCP-000077')).toBeInTheDocument()
    expect(screen.getByText('Approved')).toBeInTheDocument()
  })
})
