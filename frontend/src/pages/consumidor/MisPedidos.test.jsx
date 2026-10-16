import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

// Intl separa la moneda con un espacio no separable (U+00A0)
const normal = (texto) => texto.replace(/ /g, ' ')

const comoComprador = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  'GET /api/carrito': [200, copia(DATOS.carrito)],
  'GET /api/pedidos': () => [200, copia(DATOS.misPedidos)],
  ...rutas,
})

const PUNTO = DATOS.misPedidos[1].punto
const DETALLE = {
  ...DATOS.misPedidos[1],
  personaAutorizada: null,
  celularContacto: '987654321',
  lineas: [{ productoId: 8, nombre: 'Aceite de aguaje 250 ml', tienda: 'Asoc. Nii Joi', cantidad: 1, precioUnitario: 92.5, subtotal: 92.5 }],
  pagos: [],
  punto: PUNTO,
}

function etapas(numero) {
  return within(screen.getByRole('list', { name: `Seguimiento del pedido ${numero}` })).getAllByRole('listitem')
}

describe('CW-09 Mis pedidos (CCPIP-112, CCPIP-70, CCPIP-340)', () => {
  it('en curso muestra cada pedido con su estado, resumen pagado y en qué etapa está', async () => {
    servidor(comoComprador())
    abrirEn('/cuenta/pedidos')

    expect(await screen.findByRole('heading', { level: 1, name: 'Mi cuenta' })).toBeInTheDocument()
    expect(await screen.findByRole('tab', { name: 'En curso (2)', selected: true })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Historial (2)' })).toBeInTheDocument()

    const listo = within(screen.getByRole('article', { name: 'Pedido CCP-000077' }))
    expect(normal(listo.getByText(/productos de 3 comunidades/).textContent)).toBe('10 oct · 4 productos de 3 comunidades · S/ 92.50')
    expect(listo.getByText('Disponible en acopio', { selector: '.badge-estado' })).toBeInTheDocument()
    // Pago confirmado (hecho, con fecha en hora de Lima), disponible en acopio (actual) y entregado (pendiente)
    const [pago, acopio, entregado] = etapas('CCP-000077')
    expect(pago).toHaveClass('seguimiento__etapa--hecho')
    expect(within(pago).getByText('10/10 10:42')).toBeInTheDocument()
    expect(acopio).toHaveAttribute('aria-current', 'step')
    expect(within(acopio).getByText('12/10 09:30')).toBeInTheDocument()
    expect(entregado).toHaveClass('seguimiento__etapa--pendiente')
    expect(within(entregado).getByText('—')).toBeInTheDocument()
    expect(listo.getByText('Centro Nacional Lima')).toBeInTheDocument()
    expect(listo.getByRole('link', { name: 'Ver detalle del pedido CCP-000077' })).toHaveAttribute('href', '/pedidos/77')

    // Sin pagar: sin seguimiento y con la salida para completar el pago
    const pendiente = within(screen.getByRole('article', { name: 'Pedido CCP-000081' }))
    expect(pendiente.getByText('Este pedido espera su pago')).toBeInTheDocument()
    expect(pendiente.getByRole('link', { name: 'Ir a entrega y pago' })).toHaveAttribute('href', '/compra')
    expect(screen.queryByRole('list', { name: 'Seguimiento del pedido CCP-000081' })).not.toBeInTheDocument()

    // Lo entregado hace poco, debajo
    const recientes = within(screen.getByRole('region', { name: 'Entregados recientemente' }))
    expect(recientes.getByText(/entregado el 3 oct/)).toBeInTheDocument()
  })

  it('el historial lista entregados y cancelados con su estado y el total pagado', async () => {
    servidor(comoComprador())
    abrirEn('/cuenta/pedidos')
    await userEvent.click(await screen.findByRole('tab', { name: 'Historial (2)' }))

    const filas = within(screen.getByRole('tabpanel')).getAllByRole('listitem')
    expect(filas).toHaveLength(2)
    expect(within(filas[0]).getByText('Pedido CCP-000060')).toBeInTheDocument()
    expect(normal(within(filas[0]).getByText(/entregado el/).textContent)).toBe('entregado el 3 oct · 1 producto · S/ 48.00')
    expect(within(filas[0]).getByText('Entregado')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Cancelado')).toBeInTheDocument()
    expect(within(filas[1]).getByRole('link', { name: 'Ver detalle del pedido CCP-000055' })).toHaveAttribute('href', '/pedidos/55')
  })

  it('sin pedidos en curso abre directo en el historial', async () => {
    const solo = copia(DATOS.misPedidos).filter((p) => ['ENTREGADO', 'CANCELADO'].includes(p.estado))
    servidor(comoComprador({ 'GET /api/pedidos': [200, solo] }))
    abrirEn('/cuenta/pedidos')
    expect(await screen.findByRole('tab', { name: 'Historial (2)', selected: true })).toBeInTheDocument()
  })

  it('sin compras invita a explorar, y si falla deja reintentar', async () => {
    let falla = true
    servidor(comoComprador({ 'GET /api/pedidos': () => (falla ? new Error('red') : [200, []]) }))
    abrirEn('/cuenta/pedidos')
    expect(await screen.findByText('No pudimos cargar tus pedidos')).toBeInTheDocument()
    falla = false
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Aún no tienes pedidos')).toBeInTheDocument()
    expect(within(screen.getByRole('status')).getByRole('link', { name: 'Explorar productos' })).toHaveAttribute('href', '/buscar')
  })

  it('"Mi cuenta" del comprador lleva a sus pedidos', async () => {
    servidor(comoComprador())
    abrirEn('/')
    await userEvent.click(await screen.findByRole('button', { name: 'Mi cuenta' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Mi cuenta' }))
    expect(await screen.findByRole('tab', { name: 'En curso (2)' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/cuenta/pedidos')
  })

  it('otros roles no tienen esta pantalla ni piden la lista', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_PRODUCTOR], 'GET /api/productor/panel': [200, copia(DATOS.panelProductor)] })
    abrirEn('/cuenta/pedidos')
    await screen.findByRole('heading', { level: 1 })
    expect(window.location.pathname).toBe('/inicio')
    expect(llamadas(fetchMock, 'GET /api/pedidos')).toHaveLength(0)
  })
})

describe('Detalle del pedido con seguimiento e historial (CCPIP-70, CCPIP-340)', () => {
  it('muestra la etapa actual y el historial del más antiguo al más reciente', async () => {
    servidor(comoComprador({ 'GET /api/pedidos/77': [200, DETALLE] }))
    abrirEn('/pedidos/77')

    // Ya listo para recojo: el pedido con su numero, sin los pasos de la compra
    expect(await screen.findByRole('heading', { level: 1, name: 'Pedido CCP-000077' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Pasos de la compra' })).not.toBeInTheDocument()
    const seguimiento = within(await screen.findByRole('region', { name: 'Seguimiento' }))
    expect(seguimiento.getAllByRole('listitem')[1]).toHaveAttribute('aria-current', 'step')

    const historial = within(screen.getByRole('region', { name: 'Historial del pedido' })).getAllByRole('listitem')
    expect(historial.map((li) => li.textContent)).toEqual([
      'Pedido creado, en espera de pago10/10 10:40',
      'Pago aprobado y pedido confirmado10/10 10:42',
      'Listo para recoger en el punto de acopio12/10 09:30',
    ])
    expect(screen.getByRole('link', { name: 'Ver mis pedidos' })).toHaveAttribute('href', '/cuenta/pedidos')
  })

  it('un pedido cancelado no tiene seguimiento pero sí su historial', async () => {
    const cancelado = { ...DETALLE, ...DATOS.misPedidos[3], punto: DATOS.misPedidos[3].punto }
    servidor(comoComprador({ 'GET /api/pedidos/55': [200, cancelado] }))
    abrirEn('/pedidos/55')
    expect(await screen.findByText('Este pedido fue cancelado')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Seguimiento' })).not.toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Historial del pedido' })).getByText('Pedido cancelado')).toBeInTheDocument()
  })
})

describe('Condiciones de la compra confirmada (CCPIP-167)', () => {
  const condiciones = () => within(screen.getByRole('region', { name: 'Condiciones de tu compra' }))

  it('muestra la fecha en que se fijaron los precios, la modalidad y la comisión de ese momento', async () => {
    const confirmado = { ...DETALLE, comision: { porcentaje: 12.5, base: 'PRECIO_SIN_ENVIO', monto: 11.56 } }
    servidor(comoComprador({ 'GET /api/pedidos/77': [200, confirmado] }))
    abrirEn('/pedidos/77')
    await screen.findByRole('region', { name: 'Condiciones de tu compra' })
    expect(condiciones().getByText('Fijados al confirmar, el 10/10 10:42')).toBeInTheDocument()
    expect(condiciones().getByText('Recojo en punto de acopio')).toBeInTheDocument()
    expect(normal(condiciones().getByText(/^12\.5 %/).textContent)).toBe('12.5 % · S/ 11.56')
    expect(condiciones().getByText('Incluida en el total: no se suma a lo que pagaste.')).toBeInTheDocument()
  })

  it('si se confirmó sin comisión configurada lo dice', async () => {
    servidor(comoComprador({ 'GET /api/pedidos/77': [200, { ...DETALLE, comision: null }] }))
    abrirEn('/pedidos/77')
    await screen.findByRole('region', { name: 'Condiciones de tu compra' })
    expect(condiciones().getByText('No se aplicó comisión a esta compra')).toBeInTheDocument()
  })

  it('un pedido que espera su pago todavía no tiene condiciones fijadas', async () => {
    const pendiente = { ...DETALLE, estado: 'PENDIENTE_PAGO', confirmadoEn: null, historial: [], comision: null }
    servidor(comoComprador({ 'GET /api/pedidos/77': [200, pendiente] }))
    abrirEn('/pedidos/77')
    expect(await screen.findByText('Este pedido espera su pago')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Condiciones de tu compra' })).not.toBeInTheDocument()
  })
})

describe('Mis pedidos en inglés (CCPIP-112)', () => {
  it('pestañas, etapas y fechas en inglés', async () => {
    await i18n.changeLanguage('en')
    servidor(comoComprador())
    abrirEn('/cuenta/pedidos')
    expect(await screen.findByRole('heading', { level: 1, name: 'My account' })).toBeInTheDocument()
    expect(await screen.findByRole('tab', { name: 'In progress (2)' })).toBeInTheDocument()
    const listo = within(screen.getByRole('article', { name: 'Order CCP-000077' }))
    expect(normal(listo.getByText(/products from 3 communities/).textContent)).toBe('10 Oct · 4 products from 3 communities · S/ 92.50')
    expect(listo.getAllByText('Ready at collection point').length).toBeGreaterThan(0)
    expect(screen.queryByText(/Disponible|Entregado|productos/)).not.toBeInTheDocument()
  })
})
