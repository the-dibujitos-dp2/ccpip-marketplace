import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const INTERES = '/api/productos/1/interes'
const TIENDA = DATOS.detalle.tienda.nombre
const ficha = (extra = {}) => ({ 'GET /api/productos/1': [200, { ...copia(DATOS.detalle), ...extra }] })
const comoComprador = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  [`GET ${INTERES}`]: [204, null],
  ...ficha({ disponible: false }),
  ...rutas,
})
const registrado = (cantidad) => ({ productoId: 1, cantidad, motivo: 'AGOTADO', registradoEn: '2026-10-16T20:00:00Z', actualizadoEn: '2026-10-16T20:00:00Z' })

describe('CW-11 Me interesa (CCPIP-105)', () => {
  it('en un producto agotado registra cuántas unidades le interesan, sin reserva ni cobro', async () => {
    const fetchMock = servidor(comoComprador({ [`PUT ${INTERES}`]: [200, registrado(3)] }))
    const user = userEvent.setup()
    abrirEn('/productos/1')

    await user.click(await screen.findByRole('button', { name: 'Me interesa' }))
    expect(screen.getByText(`Te preguntamos cuántas unidades te interesan y te avisamos por correo cuando ${TIENDA} vuelva a tener stock. No es una reserva ni un cobro.`)).toBeInTheDocument()

    const dialogo = within(screen.getByRole('dialog', { name: 'Me interesa este producto' }))
    expect(dialogo.getByText(`${DATOS.detalle.nombre} · ${TIENDA}`)).toBeInTheDocument()
    expect(dialogo.getByText(`Está agotado en este productor. Tu interés le llega a ${TIENDA} y te avisamos cuando vuelva a tener stock.`)).toBeInTheDocument()
    expect(dialogo.getByText(/No es una reserva ni un cobro\. Te avisaremos por correo/)).toBeInTheDocument()
    await user.click(dialogo.getByRole('button', { name: 'Agregar una unidad' }))
    await user.click(dialogo.getByRole('button', { name: 'Agregar una unidad' }))
    await user.click(dialogo.getByRole('button', { name: 'Enviar mi interés' }))

    expect(JSON.parse(llamadas(fetchMock, `PUT ${INTERES}`)[0][1].body)).toEqual({ cantidad: 3 })
    expect(await screen.findByText('Registramos tu interés por 3 unidades')).toBeInTheDocument()
    expect(screen.getByText('Registraste tu interés · 3 und')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    // No agrega nada al carrito
    expect(llamadas(fetchMock, 'POST /api/carrito/items')).toHaveLength(0)
  })

  it('en "Venta pausada" explica que avisará cuando se pueda comprar', async () => {
    servidor(comoComprador({ ...ficha({ comprable: false }) }))
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await user.click(await screen.findByRole('button', { name: 'Me interesa' }))
    const dialogo = within(screen.getByRole('dialog', { name: 'Me interesa este producto' }))
    expect(dialogo.getByText(`${TIENDA} pausó sus ventas por un tema administrativo. Tu interés le llega y te avisamos cuando puedas comprarlo.`)).toBeInTheDocument()
  })

  it('con un interés ya registrado lo muestra y permite cambiar la cantidad o retirarlo', async () => {
    const fetchMock = servidor(comoComprador({
      [`GET ${INTERES}`]: [200, registrado(2)],
      [`PUT ${INTERES}`]: [200, registrado(6)],
      [`DELETE ${INTERES}`]: [204, null],
    }))
    const user = userEvent.setup()
    abrirEn('/productos/1')

    expect(await screen.findByText('Registraste tu interés · 2 und')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Me interesa' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Cambiar cantidad' }))
    const dialogo = within(screen.getByRole('dialog', { name: 'Me interesa este producto' }))
    const campo = dialogo.getByLabelText('¿Qué cantidad te interesaría comprar?')
    expect(campo).toHaveValue('2')
    await user.clear(campo)
    await user.type(campo, '6')
    await user.click(dialogo.getByRole('button', { name: 'Guardar cantidad' }))
    expect(await screen.findByText('Cambiamos tu interés a 6 unidades')).toBeInTheDocument()
    expect(screen.getByText('Registraste tu interés · 6 und')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Retirar interés' }))
    expect(await screen.findByText('Retiramos tu interés en este producto')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Me interesa' })).toBeInTheDocument()
    expect(llamadas(fetchMock, `DELETE ${INTERES}`)).toHaveLength(1)
  })

  it('mientras retira el interés, el botón muestra el aro y "Cambiar cantidad" queda bloqueado (CCPIP-413)', async () => {
    let responder
    servidor(comoComprador({
      [`GET ${INTERES}`]: [200, registrado(2)],
      [`DELETE ${INTERES}`]: () => new Promise((r) => { responder = r }),
    }))
    const user = userEvent.setup()
    abrirEn('/productos/1')
    const retirar = await screen.findByRole('button', { name: 'Retirar interés' })
    await user.click(retirar)

    expect(retirar).toBeDisabled()
    expect(retirar).toHaveAttribute('aria-busy', 'true')
    expect(retirar.querySelector('.boton__giro')).not.toBeNull()
    expect(screen.getByRole('button', { name: 'Cambiar cantidad' })).toBeDisabled()
    responder([204, null])
    expect(await screen.findByText('Retiramos tu interés en este producto')).toBeInTheDocument()
  })

  it('valida la cantidad y muestra el rechazo del servidor dentro del modal', async () => {
    const fetchMock = servidor(comoComprador({
      [`PUT ${INTERES}`]: [409, { code: 'OFERTA_DISPONIBLE', error: 'Este producto está disponible: puedes agregarlo a tu carrito.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await user.click(await screen.findByRole('button', { name: 'Me interesa' }))
    const dialogo = within(screen.getByRole('dialog', { name: 'Me interesa este producto' }))
    const campo = dialogo.getByLabelText('¿Qué cantidad te interesaría comprar?')
    await user.clear(campo)
    await user.type(campo, '0')
    await user.click(dialogo.getByRole('button', { name: 'Enviar mi interés' }))
    expect(dialogo.getByText('Indica una cantidad entera de 1 a 9999')).toBeInTheDocument()
    expect(llamadas(fetchMock, `PUT ${INTERES}`)).toHaveLength(0)

    await user.clear(campo)
    await user.type(campo, '2')
    await user.click(dialogo.getByRole('button', { name: 'Enviar mi interés' }))
    expect(await dialogo.findByText('Este producto está disponible: puedes agregarlo a tu carrito.')).toBeInTheDocument()
  })

  it('sin sesión lo lleva a ingresar y lo regresa a la ficha', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, ...ficha({ disponible: false }) })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await user.click(await screen.findByRole('button', { name: 'Me interesa' }))
    expect(window.location.pathname).toBe('/ingresar')
    expect(new URLSearchParams(window.location.search).get('volver')).toBe('/productos/1')
    expect(llamadas(fetchMock, `GET ${INTERES}`)).toHaveLength(0)
  })

  it('no se ofrece en productos disponibles ni a otros roles', async () => {
    const fetchMock = servidor({ ...comoComprador(), ...ficha() })
    const { unmount } = abrirEn('/productos/1')
    await screen.findByRole('button', { name: 'Agregar al carrito' })
    expect(screen.queryByRole('button', { name: 'Me interesa' })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${INTERES}`)).toHaveLength(0)
    unmount()

    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_PRODUCTOR], ...ficha({ disponible: false }) })
    abrirEn('/productos/1')
    expect(await screen.findByText('Agotado por ahora')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Me interesa' })).not.toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    servidor(comoComprador())
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await user.click(await screen.findByRole('button', { name: "I'm interested" }))
    const dialogo = within(screen.getByRole('dialog', { name: "I'm interested in this product" }))
    expect(dialogo.getByRole('button', { name: 'Send my interest' })).toBeInTheDocument()
    expect(dialogo.getByText(/It isn't a reservation or a charge/)).toBeInTheDocument()
  })
})
