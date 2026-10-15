import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

// El precio usa espacio no separable (Intl): el nombre accesible del boton no lo normaliza
const PAGAR = /^Pagar S\/\s812\.00$/

const PUNTO = { id: 1, nombre: 'Centro Nacional Lima', departamento: 'Lima', provincia: 'Lima', distrito: 'Lima', direccion: 'Sede central CCPIP' }

const PEDIDO = {
  id: 77,
  numero: 'CCP-000077',
  estado: 'PENDIENTE_PAGO',
  modalidad: 'RECOJO',
  total: 812,
  creadoEn: '2026-10-14T15:00:00Z',
  confirmadoEn: null,
  punto: PUNTO,
  personaAutorizada: null,
  celularContacto: '987654321',
  lineas: [
    { productoId: 8, nombre: 'Aceite de aguaje 250 ml', tienda: 'Asoc. Nii Joi', cantidad: 14, precioUnitario: 52, subtotal: 728 },
    { productoId: 5, nombre: 'Cacao en grano fermentado 1 kg', tienda: 'C.N. Yarina', cantidad: 2, precioUnitario: 42, subtotal: 84 },
  ],
  pagos: [],
}

const intento = (id, estado, extra = {}) => ({
  id,
  estado,
  monto: 812,
  marca: 'VISA',
  referencia: estado === 'APROBADO' ? 'SIM-123' : null,
  mensaje: null,
  creadoEn: '2026-10-14T15:01:00Z',
  resueltoEn: '2026-10-14T15:01:02Z',
  ...extra,
})

const CONFIRMADO = { ...PEDIDO, estado: 'CONFIRMADO', confirmadoEn: '2026-10-14T15:01:02Z', pagos: [intento(1, 'APROBADO')] }

const rutas = (extra = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  'GET /api/carrito': [200, copia(DATOS.carrito)],
  'GET /api/compra/puntos': [200, [PUNTO]],
  'POST /api/pedidos': [201, PEDIDO],
  'GET /api/pedidos/77': [200, CONFIRMADO],
  ...extra,
})

async function llenarYPagar(usuario, numero = '4111 1111 1111 1111') {
  await usuario.type(screen.getByLabelText('Celular de contacto'), '987 654 321')
  await usuario.type(screen.getByLabelText('Número de tarjeta'), numero)
  await usuario.type(screen.getByLabelText('Nombre del titular'), 'Ana Quispe')
  await usuario.selectOptions(screen.getByLabelText('Mes'), '12')
  await usuario.selectOptions(screen.getByLabelText('Año'), String(new Date().getFullYear() + 2))
  await usuario.type(screen.getByLabelText('CVV'), '123')
  await usuario.click(screen.getByRole('button', { name: PAGAR }))
}

describe('CW-05 Entrega y pago (CCPIP-157, CCPIP-62) y confirmación (CCPIP-64)', () => {
  it('muestra el productor de cada oferta antes de pagar (CCPIP-172)', async () => {
    const carrito = copia(DATOS.carrito)
    carrito.items[1].nombre = carrito.items[0].nombre
    servidor(rutas({ 'GET /api/carrito': [200, carrito] }))
    abrirEn('/compra')
    const resumen = await screen.findByRole('complementary', { name: 'Resumen del pedido' })
    const lineas = within(resumen).getAllByRole('listitem')
    expect(lineas).toHaveLength(2)
    expect(lineas[0]).toHaveTextContent(carrito.items[0].tienda.nombre)
    expect(lineas[1]).toHaveTextContent(carrito.items[1].tienda.nombre)
  })

  it('el carrito lleva a entrega y pago', async () => {
    servidor(rutas())
    abrirEn('/carrito')
    const continuar = await screen.findByRole('link', { name: 'Continuar con la entrega y pago' })
    expect(continuar).toHaveAttribute('href', '/compra')
  })

  it('con un pago aprobado crea el pedido, paga y muestra la confirmación', async () => {
    const fetchMock = servidor(
      rutas({ 'POST /api/pedidos/77/pagos': [200, { estado: 'APROBADO', mensaje: 'Pago aprobado.', pedido: CONFIRMADO }] }),
    )
    const usuario = userEvent.setup()
    abrirEn('/compra')

    expect(await screen.findByRole('heading', { level: 1, name: 'Entrega y pago' })).toBeInTheDocument()
    // Con un solo punto posible, ya viene elegido
    expect(await screen.findByRole('radio', { name: /Centro Nacional Lima/ })).toBeChecked()
    await llenarYPagar(usuario)

    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/pedidos/77')
    expect(screen.getByText('CCP-000077')).toBeInTheDocument()
    expect(screen.getByText(/^Pagaste S\/\s812\.00 con VISA/)).toBeInTheDocument()

    const [, pedido] = llamadas(fetchMock, 'POST /api/pedidos')[0]
    expect(JSON.parse(pedido.body)).toEqual({ puntoId: 1, personaAutorizada: null, celularContacto: '987654321' })
    const [, pago] = llamadas(fetchMock, 'POST /api/pedidos/77/pagos')[0]
    const cuerpo = JSON.parse(pago.body)
    expect(cuerpo.claveIdempotencia).toMatch(/^[0-9a-f-]{36}$/)
    expect(cuerpo.tarjeta).toMatchObject({ numero: '4111 1111 1111 1111', titular: 'Ana Quispe', mes: 12, cvv: '123' })
  })

  it('un rechazo avisa, deja reintentar con el mismo pedido y otra clave de intento', async () => {
    let intentos = 0
    const fetchMock = servidor(
      rutas({
        'POST /api/pedidos/77/pagos': () =>
          ++intentos === 1
            ? [200, { estado: 'RECHAZADO', mensaje: 'Tu banco rechazó el pago por fondos insuficientes.', pedido: PEDIDO }]
            : [200, { estado: 'APROBADO', mensaje: 'Pago aprobado.', pedido: CONFIRMADO }],
      }),
    )
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await screen.findByRole('radio', { name: /Centro Nacional Lima/ })
    await llenarYPagar(usuario, '4000 0000 0000 0002')

    const alerta = await screen.findByRole('alert')
    expect(within(alerta).getByText('Tu pago fue rechazado')).toBeInTheDocument()
    // Junto al boton "Pagar", donde el comprador acaba de hacer clic, y no al inicio del formulario
    expect(screen.getByRole('complementary', { name: 'Resumen del pedido' })).toContainElement(alerta)
    expect(within(alerta).getByText(/fondos insuficientes/)).toBeInTheDocument()
    // Se borra el CVV; lo demas se conserva
    expect(screen.getByLabelText('CVV')).toHaveValue('')
    expect(screen.getByLabelText('Número de tarjeta')).toHaveValue('4000 0000 0000 0002')

    await usuario.type(screen.getByLabelText('CVV'), '123')
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()

    expect(llamadas(fetchMock, 'POST /api/pedidos')).toHaveLength(1)
    const claves = llamadas(fetchMock, 'POST /api/pedidos/77/pagos').map(([, o]) => JSON.parse(o.body).claveIdempotencia)
    expect(claves).toHaveLength(2)
    expect(claves[0]).not.toBe(claves[1])
  })

  it('no deja escribir de más: celular de 9 dígitos, tarjeta agrupada y CVV según la marca', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    const celular = await screen.findByLabelText('Celular de contacto')

    await usuario.type(celular, '98765432199')
    expect(celular).toHaveValue('987654321')
    await usuario.clear(celular)
    await usuario.paste('+51 987-654-321')
    expect(celular).toHaveValue('987654321')

    const numero = screen.getByLabelText('Número de tarjeta')
    const cvv = screen.getByLabelText('CVV')
    await usuario.type(numero, '41111111111111119999')
    expect(numero).toHaveValue('4111 1111 1111 1111 999')
    expect(screen.getByText('Visa')).toBeInTheDocument()
    await usuario.type(cvv, '12345')
    expect(cvv).toHaveValue('123')

    // American Express: 15 dígitos en grupos 4-6-5 y CVV de 4
    await usuario.clear(numero)
    await usuario.type(numero, '378282246310005')
    expect(numero).toHaveValue('3782 822463 10005')
    await usuario.clear(cvv)
    await usuario.type(cvv, '12345')
    expect(cvv).toHaveValue('1234')

    // Volver a Visa recorta el CVV a 3
    await usuario.clear(numero)
    await usuario.type(numero, '4111111111111111')
    expect(cvv).toHaveValue('123')
  })

  it('avisa si la tarjeta no es válida o ya venció, sin enviar nada', async () => {
    const fetchMock = servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await usuario.type(await screen.findByLabelText('Celular de contacto'), '987654321')
    await usuario.type(screen.getByLabelText('Número de tarjeta'), '4111 1111 1111 1112')
    await usuario.type(screen.getByLabelText('Nombre del titular'), 'Ana Quispe')
    const anio = new Date().getFullYear()
    const mesPasado = new Date().getMonth() // 0-11: el mes anterior al actual, en 1-12
    await usuario.selectOptions(screen.getByLabelText('Año'), String(anio))
    if (mesPasado > 0) await usuario.selectOptions(screen.getByLabelText('Mes'), String(mesPasado))
    await usuario.type(screen.getByLabelText('CVV'), '123')
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(screen.getByText('El número de tarjeta no es válido')).toBeInTheDocument()
    if (mesPasado > 0) expect(screen.getByText('La tarjeta está vencida')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos')).toHaveLength(0)
  })

  it('sin datos completos no envía nada y marca lo que falta', async () => {
    const fetchMock = servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await usuario.click(await screen.findByRole('button', { name: PAGAR }))

    expect(screen.getByText('Ingresa un celular de 9 dígitos que empiece con 9')).toBeInTheDocument()
    expect(screen.getByText('Ingresa el número de tu tarjeta')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos')).toHaveLength(0)
  })

  it('si otra persona recoge, pide su nombre y lo envía', async () => {
    const fetchMock = servidor(
      rutas({ 'POST /api/pedidos/77/pagos': [200, { estado: 'APROBADO', mensaje: 'Pago aprobado.', pedido: CONFIRMADO }] }),
    )
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await usuario.click(await screen.findByRole('checkbox', { name: 'Otra persona recogerá el pedido' }))
    await usuario.type(screen.getByLabelText('Nombre de la persona autorizada'), 'Rosa Quispe')
    await llenarYPagar(usuario)

    await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })
    const [, pedido] = llamadas(fetchMock, 'POST /api/pedidos')[0]
    expect(JSON.parse(pedido.body).personaAutorizada).toBe('Rosa Quispe')
  })

  it('con productos que ya no se pueden comprar, pide volver al carrito', async () => {
    servidor(
      rutas({
        'GET /api/compra/puntos': [409, { code: 'CARRITO_CON_PROBLEMAS', error: 'Algunos productos de tu carrito no se pueden comprar como están.' }],
      }),
    )
    abrirEn('/compra')
    expect(await screen.findByText('Revisa tu carrito')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Volver al carrito' })).toHaveAttribute('href', '/carrito')
  })

  it('la confirmación muestra artículos, recojo e intentos de pago (CCPIP-163)', async () => {
    servidor(
      rutas({
        'GET /api/pedidos/77': [
          200,
          { ...CONFIRMADO, personaAutorizada: 'Rosa Quispe', pagos: [intento(1, 'RECHAZADO'), intento(2, 'APROBADO')] },
        ],
      }),
    )
    abrirEn('/pedidos/77')
    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: /Aceite de aguaje 250 ml/ })).toBeInTheDocument()
    expect(screen.getByText('Rosa Quispe')).toBeInTheDocument()
    expect(screen.getByText('Sede central CCPIP · Lima · Lima')).toBeInTheDocument()

    const intentos = within(screen.getByRole('list', { name: 'Intentos de pago' })).getAllByRole('listitem')
    expect(intentos).toHaveLength(2)
    expect(intentos[0]).toHaveTextContent('Rechazado')
    expect(intentos[1]).toHaveTextContent('Aprobado')
  })

  it('un pedido ajeno o inexistente no se muestra', async () => {
    servidor(rutas({ 'GET /api/pedidos/77': [404, { code: 'PEDIDO_NO_ENCONTRADO', error: 'No encontramos ese pedido.' }] }))
    abrirEn('/pedidos/77')
    await waitFor(() => expect(screen.getByText('No encontramos ese pedido')).toBeInTheDocument())
  })
})
