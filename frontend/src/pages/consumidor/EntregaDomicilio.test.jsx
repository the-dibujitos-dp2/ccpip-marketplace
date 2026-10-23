import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const PAGAR = /^Pagar S\/\s812\.00$/

const LIMA = { id: 1, nombre: 'Centro Nacional Lima', departamento: 'Lima', provincia: 'Lima', distrito: 'Lima', direccion: 'Sede central CCPIP' }
const CUSCO = { id: 3, nombre: 'Centro Regional Cusco', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Cusco', direccion: 'Centro regional andino' }

const WANCHAQ = { ubigeo: '080108', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Wanchaq' }
const MIRAFLORES = { ubigeo: '150122', departamento: 'Lima', provincia: 'Lima', distrito: 'Miraflores' }
// Wanchaq lo cubre solo Cusco; Miraflores lo cubren dos puntos
const DISTRITOS = [
  { distrito: WANCHAQ, puntos: [CUSCO] },
  { distrito: MIRAFLORES, puntos: [LIMA, CUSCO] },
]

const ENTREGA = {
  ubigeo: '080108', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Wanchaq',
  direccion: 'Av. de la Cultura 1234', referencia: 'Frente al grifo', contactoNombre: 'María Quispe',
  contactoCelular: '987654321', estado: 'DISPONIBLE',
}

const PEDIDO = {
  id: 78,
  numero: 'CCP-000078',
  estado: 'PENDIENTE_PAGO',
  modalidad: 'ENVIO',
  total: 812,
  creadoEn: '2026-10-23T15:00:00Z',
  confirmadoEn: null,
  punto: CUSCO,
  personaAutorizada: null,
  celularContacto: null,
  entrega: ENTREGA,
  lineas: [
    { productoId: 8, nombre: 'Aceite de aguaje 250 ml', tienda: 'Asoc. Nii Joi', cantidad: 14, precioUnitario: 52, subtotal: 728 },
    { productoId: 5, nombre: 'Cacao en grano fermentado 1 kg', tienda: 'C.N. Yarina', cantidad: 2, precioUnitario: 42, subtotal: 84 },
  ],
  pagos: [],
  historial: [],
}

const CONFIRMADO = {
  ...PEDIDO,
  estado: 'CONFIRMADO',
  confirmadoEn: '2026-10-23T15:01:02Z',
  pagos: [{ id: 1, estado: 'APROBADO', monto: 812, marca: 'VISA', referencia: 'SIM-123', mensaje: null, creadoEn: '2026-10-23T15:01:00Z', resueltoEn: '2026-10-23T15:01:02Z' }],
  historial: [{ estado: 'CONFIRMADO', registradoEn: '2026-10-23T15:01:02Z' }],
}

const rutas = (extra = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  'GET /api/carrito': [200, copia(DATOS.carrito)],
  'GET /api/compra/puntos': [200, [LIMA, CUSCO]],
  'GET /api/compra/distritos': [200, DISTRITOS],
  'POST /api/pedidos': [201, PEDIDO],
  'GET /api/pedidos/78': [200, CONFIRMADO],
  ...extra,
})

async function datosDeTarjeta(usuario) {
  await usuario.type(screen.getByLabelText('Número de tarjeta'), '4111 1111 1111 1111')
  await usuario.type(screen.getByLabelText('Nombre del titular'), 'Ana Quispe')
  await usuario.selectOptions(screen.getByLabelText('Mes'), '12')
  await usuario.selectOptions(screen.getByLabelText('Año'), String(new Date().getFullYear() + 2))
  await usuario.type(screen.getByLabelText('CVV'), '123')
}

async function elegirDomicilio(usuario) {
  await usuario.click(await screen.findByRole('radio', { name: 'Entrega a domicilio' }))
}

describe('CW-05 Entrega a domicilio (CCPIP-59)', () => {
  it('sin distritos con entrega solo ofrece el recojo', async () => {
    servidor(rutas({ 'GET /api/compra/distritos': [200, []] }))
    abrirEn('/compra')
    expect(await screen.findByRole('heading', { name: '1. Punto de recojo' })).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Entrega a domicilio' })).not.toBeInTheDocument()
  })

  it('si falla la consulta de distritos, la compra para recojo sigue funcionando', async () => {
    servidor(rutas({ 'GET /api/compra/distritos': [500, { code: 'ERROR' }] }))
    abrirEn('/compra')
    expect(await screen.findByRole('heading', { name: '1. Punto de recojo' })).toBeInTheDocument()
    expect(screen.getByLabelText('Celular de contacto')).toBeInTheDocument()
  })

  it('con entrega a domicilio pide el distrito, la dirección y quién recibe en lugar de quién recoge', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)

    expect(screen.getByRole('heading', { name: '1. Entrega a domicilio' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '2. ¿Quién recibe?' })).toBeInTheDocument()
    expect(screen.getByLabelText('Distrito de entrega')).toBeInTheDocument()
    expect(screen.getByLabelText('Dirección de entrega')).toBeInTheDocument()
    expect(screen.getByLabelText('Nombre de quien recibe')).toBeInTheDocument()
    expect(screen.getByLabelText('Celular de quien recibe')).toBeInTheDocument()
    expect(screen.queryByLabelText('Otra persona recogerá el pedido')).not.toBeInTheDocument()
    // Hasta elegir el distrito no hay puntos que ofrecer
    expect(screen.queryByRole('radio', { name: /Centro Regional Cusco/ })).not.toBeInTheDocument()
    // El costo del envio aun no esta definido
    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    expect(within(resumen).getByText('Por definir')).toBeInTheDocument()
  })

  it('ofrece solo los puntos que cubren el distrito, y deja elegir entre varios', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)

    // Wanchaq lo cubre solo Cusco: ya viene elegido
    await usuario.selectOptions(screen.getByLabelText('Distrito de entrega'), '080108')
    expect(screen.getByRole('radio', { name: /Centro Regional Cusco/ })).toBeChecked()
    expect(screen.queryByRole('radio', { name: /Centro Nacional Lima/ })).not.toBeInTheDocument()

    // Miraflores lo cubren dos: hay que elegir
    await usuario.selectOptions(screen.getByLabelText('Distrito de entrega'), '150122')
    expect(screen.getByRole('radio', { name: /Centro Nacional Lima/ })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: /Centro Regional Cusco/ })).not.toBeChecked()
  })

  it('crea el pedido a domicilio con su entrega, paga y muestra la confirmación', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(
      rutas({ 'POST /api/pedidos/78/pagos': [200, { estado: 'APROBADO', mensaje: 'Pago aprobado.', pedido: CONFIRMADO }] }),
    )
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await usuario.selectOptions(screen.getByLabelText('Distrito de entrega'), '080108')
    await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Av. de la Cultura 1234')
    await usuario.type(screen.getByLabelText('Referencia (opcional)'), 'Frente al grifo')
    await usuario.type(screen.getByLabelText('Nombre de quien recibe'), 'María Quispe')
    await usuario.type(screen.getByLabelText('Celular de quien recibe'), '987 654 321')
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/pedidos/78')
    expect(screen.getByText(/Te lo entregaremos a domicilio/)).toBeInTheDocument()

    const [, pedido] = llamadas(fetchMock, 'POST /api/pedidos')[0]
    expect(JSON.parse(pedido.body)).toEqual({
      puntoId: 3,
      modalidad: 'ENVIO',
      entrega: {
        ubigeo: '080108',
        direccion: 'Av. de la Cultura 1234',
        referencia: 'Frente al grifo',
        contactoNombre: 'María Quispe',
        contactoCelular: '987654321',
      },
    })
  })

  it('avisa lo que falta de la entrega sin enviar nada', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(screen.getByText('Elige el distrito de entrega')).toBeInTheDocument()
    expect(screen.getByText('Indica la dirección de entrega')).toBeInTheDocument()
    expect(screen.getByText('Indica quién recibirá el pedido')).toBeInTheDocument()
    expect(screen.getByText('Ingresa un celular de 9 dígitos que empiece con 9')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos')).toHaveLength(0)
  })

  it('si el punto ya no cubre el distrito lo explica y vuelve a cargar las opciones', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(
      rutas({ 'POST /api/pedidos': [409, { code: 'SIN_COBERTURA', error: 'Este punto de acopio no entrega a domicilio en ese distrito. Elige otro punto o el recojo.' }] }),
    )
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await usuario.selectOptions(screen.getByLabelText('Distrito de entrega'), '080108')
    await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Av. de la Cultura 1234')
    await usuario.type(screen.getByLabelText('Nombre de quien recibe'), 'María Quispe')
    await usuario.type(screen.getByLabelText('Celular de quien recibe'), '987654321')
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    const alerta = await screen.findByRole('alert')
    expect(within(alerta).getByText('Ese punto no entrega en tu distrito')).toBeInTheDocument()
    expect(within(alerta).getByText(/no entrega a domicilio en ese distrito/)).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos/78/pagos')).toHaveLength(0)
    expect(llamadas(fetchMock, 'GET /api/compra/distritos').length).toBeGreaterThanOrEqual(2)
  })

  it('cambiar de nuevo a recojo vuelve a pedir quién recoge', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await usuario.click(screen.getByRole('radio', { name: 'Recojo en punto de acopio' }))
    expect(screen.getByRole('heading', { name: '1. Punto de recojo' })).toBeInTheDocument()
    expect(screen.getByLabelText('Otra persona recogerá el pedido')).toBeInTheDocument()
    expect(screen.getByLabelText('Celular de contacto')).toBeInTheDocument()
  })
})

describe('CW-06 Pedido a domicilio (CCPIP-59)', () => {
  it('muestra a dónde se entrega y no el seguimiento de recojo', async () => {
    servidor(rutas())
    abrirEn('/pedidos/78')
    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()

    const entrega = screen.getByRole('heading', { name: 'Entrega a domicilio' }).closest('section')
    expect(within(entrega).getByText('Centro Regional Cusco')).toBeInTheDocument()
    expect(within(entrega).getByText('Av. de la Cultura 1234')).toBeInTheDocument()
    expect(within(entrega).getByText('Wanchaq · Cusco · Cusco')).toBeInTheDocument()
    expect(within(entrega).getByText('Frente al grifo')).toBeInTheDocument()
    expect(within(entrega).getByText('María Quispe')).toBeInTheDocument()
    expect(within(entrega).getByText('987654321')).toBeInTheDocument()
    // El reparto llega con las historias de distribucion: nada de "listo para recojo"
    expect(screen.queryByRole('list', { name: /Seguimiento/ })).not.toBeInTheDocument()
    expect(screen.queryByText('Listo para recojo')).not.toBeInTheDocument()
  })
})
