import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_ACOPIO } from '../../test/servidor.js'

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

const SURCO = { ubigeo: '150140', departamento: 'Lima', provincia: 'Lima', distrito: 'Santiago de Surco' }
const CUSCO_CENTRO = { ubigeo: '080101', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Cusco' }

// Catalogo territorial (CCPIP-61). Surco y Cusco-ciudad estan en el catalogo, pero ningun punto los cubre
const TERRITORIO = {
  'GET /api/territorio/departamentos': [200, ['Cusco', 'Lima']],
  'GET /api/territorio/provincias?departamento=Cusco': [200, ['Cusco']],
  'GET /api/territorio/provincias?departamento=Lima': [200, ['Lima']],
  'GET /api/territorio/distritos?departamento=Cusco&provincia=Cusco': [200, [CUSCO_CENTRO, WANCHAQ]],
  'GET /api/territorio/distritos?departamento=Lima&provincia=Lima': [200, [MIRAFLORES, SURCO]],
}

const MIS_DATOS = { nombres: 'Lucía', apellidos: 'Ramírez Torres', correo: 'lucia@correo.pe', correoConfirmado: true, celular: '+51 987 654 321' }

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
  ...TERRITORIO,
  'GET /api/cuenta/datos': [200, MIS_DATOS],
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

/** Departamento, provincia y distrito: cada nivel aparece al elegir el anterior. */
async function elegirDestino(usuario, departamento, provincia, ubigeo) {
  await usuario.selectOptions(screen.getByLabelText('Departamento'), departamento)
  await within(screen.getByLabelText('Provincia')).findByRole('option', { name: provincia })
  await usuario.selectOptions(screen.getByLabelText('Provincia'), provincia)
  await waitFor(() => expect(screen.getByLabelText('Distrito de entrega').querySelector(`option[value="${ubigeo}"]`)).not.toBeNull())
  await usuario.selectOptions(screen.getByLabelText('Distrito de entrega'), ubigeo)
}

async function elegirDomicilio(usuario) {
  await usuario.click(await screen.findByRole('radio', { name: /^Entrega a domicilio/ }))
}

describe('CW-05 Entrega a domicilio (CCPIP-59)', () => {
  it('sin distritos con entrega solo ofrece el recojo', async () => {
    servidor(rutas({ 'GET /api/compra/distritos': [200, []] }))
    abrirEn('/compra')
    expect(await screen.findByRole('heading', { name: '1. Punto de recojo' })).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: /^Entrega a domicilio/ })).not.toBeInTheDocument()
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

    expect(screen.getByRole('heading', { name: '1. ¿Cómo quieres recibirlo?' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '2. ¿Quién recibe?' })).toBeInTheDocument()
    expect(screen.getByLabelText('Distrito de entrega')).toBeInTheDocument()
    expect(screen.getByLabelText('Dirección de entrega')).toBeInTheDocument()
    expect(screen.getByLabelText('Nombre de quien recibe')).toBeInTheDocument()
    expect(screen.getByLabelText('Celular de quien recibe')).toBeInTheDocument()
    expect(screen.queryByLabelText('Otra persona recogerá el pedido')).not.toBeInTheDocument()
    // Hasta elegir el distrito no hay puntos que ofrecer
    expect(screen.queryByRole('radio', { name: /Centro Regional Cusco/ })).not.toBeInTheDocument()
    // El costo del envio aun no esta definido: se muestra por confirmar, en la tarjeta y en el resumen
    expect(screen.getByRole('radio', { name: /Costo por confirmar/ })).toBeChecked()
    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    expect(within(resumen).getByText('A domicilio · por confirmar')).toBeInTheDocument()
  })

  it('el destino se elige por niveles: cada uno se habilita con el anterior y cambiarlo vacía los de abajo', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)

    expect(screen.getByLabelText('Provincia')).toBeDisabled()
    expect(screen.getByLabelText('Distrito de entrega')).toBeDisabled()
    await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')
    expect(screen.getByLabelText('Distrito de entrega')).toHaveValue('080108')
    expect(screen.getByRole('radio', { name: /Centro Regional Cusco/ })).toBeChecked()

    // Cambiar de departamento vacia provincia, distrito y el punto, que dependian de el
    await usuario.selectOptions(screen.getByLabelText('Departamento'), 'Lima')
    expect(screen.getByLabelText('Provincia')).toHaveValue('')
    expect(screen.getByLabelText('Distrito de entrega')).toBeDisabled()
    expect(screen.queryByRole('radio', { name: /Centro Regional Cusco/ })).not.toBeInTheDocument()
  })

  it('un distrito del catálogo que ningún punto cubre se avisa y no deja avanzar', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await elegirDestino(usuario, 'Lima', 'Lima', '150140')

    expect(await screen.findByText('Por ahora no entregamos en Santiago de Surco con tu carrito')).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: /Centro Nacional Lima/ })).not.toBeInTheDocument()
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(screen.getByText('Ningún punto entrega en este distrito con tu carrito')).toBeInTheDocument()
    // No se pide ademas elegir un punto: ninguno se ofrece
    expect(screen.queryByText('Elige un punto de acopio')).not.toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos')).toHaveLength(0)
  })

  it('precarga quién recibe y su celular con los datos del comprador, sin pisar lo que ya escribió', { timeout: 15_000 }, async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    // Escribe su celular en el recojo: al cambiar a domicilio se conserva
    await usuario.type(await screen.findByLabelText('Celular de contacto'), '999888777')
    await elegirDomicilio(usuario)

    expect(await screen.findByLabelText('Nombre de quien recibe')).toHaveValue('Lucía Ramírez Torres')
    expect(screen.getByLabelText('Celular de quien recibe')).toHaveValue('999888777')
    // Puede cambiarlos
    await usuario.clear(screen.getByLabelText('Nombre de quien recibe'))
    await usuario.clear(screen.getByLabelText('Nombre de quien recibe'))
    await usuario.type(screen.getByLabelText('Nombre de quien recibe'), 'Rosa Quispe')
    expect(screen.getByLabelText('Nombre de quien recibe')).toHaveValue('Rosa Quispe')
  })

  it('con el celular vacío lo precarga del comprador, sin el prefijo +51', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await waitFor(() => expect(screen.getByLabelText('Celular de quien recibe')).toHaveValue('987654321'))
  })

  it('si no se pueden leer sus datos, el contacto queda vacío y la compra sigue', async () => {
    servidor(rutas({ 'GET /api/cuenta/datos': [500, { code: 'ERROR' }] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    expect(await screen.findByLabelText('Nombre de quien recibe')).toHaveValue('')
    expect(screen.getByLabelText('Celular de quien recibe')).toHaveValue('')
  })

  it('si falla el catálogo territorial lo avisa', async () => {
    servidor(rutas({ 'GET /api/territorio/departamentos': [500, { code: 'ERROR' }] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    expect(await screen.findByText('No pudimos cargar los departamentos y distritos. Inténtalo otra vez.')).toBeInTheDocument()
  })

  it('ofrece solo los puntos que cubren el distrito, y deja elegir entre varios', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)

    // Wanchaq lo cubre solo Cusco: ya viene elegido
    await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')
    expect(screen.getByRole('radio', { name: /Centro Regional Cusco/ })).toBeChecked()
    expect(screen.queryByRole('radio', { name: /Centro Nacional Lima/ })).not.toBeInTheDocument()

    // Miraflores lo cubren dos: hay que elegir
    await elegirDestino(usuario, 'Lima', 'Lima', '150122')
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
    await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')
    await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Av. de la Cultura 1234')
    await usuario.type(screen.getByLabelText('Referencia (opcional)'), 'Frente al grifo')
    await usuario.clear(screen.getByLabelText('Nombre de quien recibe'))
    await usuario.type(screen.getByLabelText('Nombre de quien recibe'), 'María Quispe')
    await usuario.clear(screen.getByLabelText('Celular de quien recibe'))
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
    const fetchMock = servidor(rutas({ 'GET /api/cuenta/datos': [200, { nombres: '', apellidos: '', celular: null }] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(screen.getByText('Elige el departamento de entrega')).toBeInTheDocument()
    // Sin distrito no hay puntos entre los que elegir: no se pide un punto
    expect(screen.queryByText('Elige un punto de acopio')).not.toBeInTheDocument()
    expect(screen.getByText('Indica la dirección de entrega')).toBeInTheDocument()
    expect(screen.getByText('Indica quién recibirá el pedido')).toBeInTheDocument()
    expect(screen.getByText('Ingresa un celular de 9 dígitos que empiece con 9')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos')).toHaveLength(0)
  })

  it('con varios puntos que cubren el distrito pide elegir uno, y el aviso se va al elegirlo', { timeout: 15_000 }, async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await elegirDestino(usuario, 'Lima', 'Lima', '150122')
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(screen.getByText('Elige un punto de acopio')).toBeInTheDocument()

    await usuario.click(screen.getByRole('radio', { name: /Centro Nacional Lima/ }))
    expect(screen.queryByText('Elige un punto de acopio')).not.toBeInTheDocument()
  })

  it('al elegir un distrito con un solo punto no queda el aviso de punto de un envio anterior', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await elegirDestino(usuario, 'Lima', 'Lima', '150122')
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(screen.getByText('Elige un punto de acopio')).toBeInTheDocument()
    // Wanchaq lo cubre un solo punto: queda elegido y el aviso anterior ya no corresponde
    await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')
    expect(screen.getByRole('radio', { name: /Centro Regional Cusco/ })).toBeChecked()
    expect(screen.queryByText('Elige un punto de acopio')).not.toBeInTheDocument()
  })

  it('si el punto ya no cubre el distrito lo explica y vuelve a cargar las opciones', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(
      rutas({ 'POST /api/pedidos': [409, { code: 'SIN_COBERTURA', error: 'Este punto de acopio no entrega a domicilio en ese distrito. Elige otro punto o el recojo.' }] }),
    )
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')
    await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Av. de la Cultura 1234')
    await usuario.clear(screen.getByLabelText('Nombre de quien recibe'))
    await usuario.type(screen.getByLabelText('Nombre de quien recibe'), 'María Quispe')
    await usuario.clear(screen.getByLabelText('Celular de quien recibe'))
    await usuario.type(screen.getByLabelText('Celular de quien recibe'), '987654321')
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    const alerta = await screen.findByRole('alert')
    expect(within(alerta).getByText('Ese punto no entrega en tu distrito')).toBeInTheDocument()
    expect(within(alerta).getByText(/no entrega a domicilio en ese distrito/)).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos/78/pagos')).toHaveLength(0)
    expect(llamadas(fetchMock, 'GET /api/compra/distritos').length).toBeGreaterThanOrEqual(2)
  })

  it('al recargar las opciones no conserva un punto que ya no cubre el distrito: queda el que sigue', { timeout: 15_000 }, async () => {
    let vez = 0
    servidor(rutas({
      // Miraflores lo cubrian Lima y Cusco; tras el error, solo Cusco
      'GET /api/compra/distritos': () => [200, ++vez === 1 ? DISTRITOS : [{ distrito: MIRAFLORES, puntos: [CUSCO] }, { distrito: WANCHAQ, puntos: [CUSCO] }]],
      'POST /api/pedidos': [409, { code: 'PUNTO_SIN_EXISTENCIAS', error: 'Este punto de acopio ya no tiene existencias para todo tu carrito. Elige otro punto.' }],
    }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await elegirDestino(usuario, 'Lima', 'Lima', '150122')
    await usuario.click(screen.getByRole('radio', { name: /Centro Nacional Lima/ }))
    await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Av. Larco 123')
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(await screen.findByText('Tu carrito cambió')).toBeInTheDocument()
    // Lima ya no es una opcion: no queda elegido un punto que no corresponde
    expect(await screen.findByRole('radio', { name: /Centro Regional Cusco/ })).toBeChecked()
    expect(screen.queryByRole('radio', { name: /Centro Nacional Lima/ })).not.toBeInTheDocument()
  })

  it('en el recojo tampoco conserva un punto que dejó de tener todo el carrito', { timeout: 15_000 }, async () => {
    let vez = 0
    servidor(rutas({
      'GET /api/compra/puntos': () => [200, ++vez === 1 ? [LIMA, CUSCO] : [CUSCO]],
      'POST /api/pedidos': [409, { code: 'PUNTO_SIN_EXISTENCIAS', error: 'Este punto de acopio ya no tiene existencias para todo tu carrito. Elige otro punto.' }],
    }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await usuario.click(await screen.findByRole('radio', { name: /Centro Nacional Lima/ }))
    await usuario.type(screen.getByLabelText('Celular de contacto'), '987654321')
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(await screen.findByText('Tu carrito cambió')).toBeInTheDocument()
    expect(await screen.findByRole('radio', { name: /Centro Regional Cusco/ })).toBeChecked()
    expect(screen.queryByRole('radio', { name: /Centro Nacional Lima/ })).not.toBeInTheDocument()
  })

  it('al recargar sin un punto que lo reemplace deja la elección vacía', { timeout: 15_000 }, async () => {
    let vez = 0
    const NUEVO = { ...LIMA, id: 9, nombre: 'Centro Nuevo' }
    const fetchMock = servidor(rutas({
      'GET /api/compra/distritos': () => [200, ++vez === 1 ? DISTRITOS : [{ distrito: MIRAFLORES, puntos: [CUSCO, NUEVO] }, { distrito: WANCHAQ, puntos: [CUSCO] }]],
      'POST /api/pedidos': [409, { code: 'PUNTO_SIN_EXISTENCIAS', error: 'Este punto de acopio ya no tiene existencias para todo tu carrito. Elige otro punto.' }],
    }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await elegirDestino(usuario, 'Lima', 'Lima', '150122')
    await usuario.click(screen.getByRole('radio', { name: /Centro Nacional Lima/ }))
    await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Av. Larco 123')
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(await screen.findByText('Tu carrito cambió')).toBeInTheDocument()
    // Siguen dos puntos y Lima ya no esta: hay que elegir de nuevo, sin dejar uno elegido por error
    expect(await screen.findByRole('radio', { name: /Centro Regional Cusco/ })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: /Centro Nuevo/ })).not.toBeChecked()
    // Y no se envia el punto viejo: sin elegir otro, pide elegir
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(screen.getByText('Elige un punto de acopio')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos')).toHaveLength(1)
  })

  it('una dirección incompleta se avisa en su campo sin enviar nada', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')
    await usuario.type(screen.getByLabelText('Dirección de entrega'), '12345')
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(screen.getByText('Indica una dirección completa: calle y número, por ejemplo Av. Larco 123.')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos')).toHaveLength(0)
    // Una manzana y lote no lleva numero de calle, pero es una direccion completa
    await usuario.clear(screen.getByLabelText('Dirección de entrega'))
    await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Mz. A Lt. 3')
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(screen.queryByText('Indica una dirección completa: calle y número, por ejemplo Av. Larco 123.')).not.toBeInTheDocument()
  })

  it('si el servidor rechaza la dirección lo marca en su campo, no como error de pago', { timeout: 15_000 }, async () => {
    servidor(rutas({ 'POST /api/pedidos': [422, { code: 'DIRECCION_INVALIDA', error: 'Indica una dirección completa: calle y número, por ejemplo Av. Larco 123.' }] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')
    await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Av. Siempre Viva 742')
    await usuario.click(screen.getByLabelText('Nombre de quien recibe'))
    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(await screen.findByText('Indica una dirección completa: calle y número, por ejemplo Av. Larco 123.')).toBeInTheDocument()
    expect(screen.queryByText('No pudimos completar el pago')).not.toBeInTheDocument()
  })

  it('cambiar de nuevo a recojo vuelve a pedir quién recoge', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await usuario.click(screen.getByRole('radio', { name: /^Recojo en punto de acopio/ }))
    expect(screen.getByRole('radio', { name: /Sin costo/ })).toBeChecked()
    expect(screen.getByLabelText('Otra persona recogerá el pedido')).toBeInTheDocument()
    expect(within(screen.getByRole('complementary', { name: 'Resumen del pedido' })).getByText('Recojo · S/ 0.00')).toBeInTheDocument()
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

const GUARDADA = { id: 5, alias: 'Casa', ubigeo: '080108', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Wanchaq', direccion: 'Av. de la Cultura 1234', referencia: null }
const GUARDAR = 'POST /api/cuenta/direcciones'

/** Un pedido a domicilio listo para pagar: destino, direccion y tarjeta (el contacto viene de Mis datos). */
async function prepararDomicilio(usuario) {
  await elegirDomicilio(usuario)
  await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')
  await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Av. de la Cultura 1234')
  await datosDeTarjeta(usuario)
}

describe('CW-06 Guardar la dirección habitual (CCPIP-65)', () => {
  const aprobado = { 'POST /api/pedidos/78/pagos': [200, { estado: 'APROBADO', mensaje: 'Pago aprobado.', pedido: CONFIRMADO }] }

  it('ofrece guardar la dirección en la entrega a domicilio y pide el nombre solo si se marca', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    // En el recojo no hay direccion que guardar
    await screen.findByLabelText('Celular de contacto')
    expect(screen.queryByLabelText('Guardar esta dirección para próximas compras')).not.toBeInTheDocument()

    await elegirDomicilio(usuario)
    const guardar = screen.getByLabelText('Guardar esta dirección para próximas compras')
    expect(guardar).not.toBeChecked()
    expect(screen.queryByLabelText('Nombre de la dirección (opcional)')).not.toBeInTheDocument()
    await usuario.click(guardar)
    expect(screen.getByLabelText('Nombre de la dirección (opcional)')).toBeInTheDocument()
  })

  it('guarda la dirección con su nombre cuando el pago se aprueba y la compra sigue', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas({ ...aprobado, [GUARDAR]: [201, GUARDADA] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await prepararDomicilio(usuario)
    await usuario.type(screen.getByLabelText('Referencia (opcional)'), 'Frente al grifo')
    await usuario.click(screen.getByLabelText('Guardar esta dirección para próximas compras'))
    await usuario.type(screen.getByLabelText('Nombre de la dirección (opcional)'), 'Casa')
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()
    const guardadas = llamadas(fetchMock, GUARDAR)
    expect(guardadas).toHaveLength(1)
    expect(JSON.parse(guardadas[0][1].body)).toEqual({
      alias: 'Casa', ubigeo: '080108', direccion: 'Av. de la Cultura 1234', referencia: 'Frente al grifo',
    })
  })

  it('sin nombre ni referencia las envía vacías', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas({ ...aprobado, [GUARDAR]: [201, GUARDADA] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await prepararDomicilio(usuario)
    await usuario.click(screen.getByLabelText('Guardar esta dirección para próximas compras'))
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })
    expect(JSON.parse(llamadas(fetchMock, GUARDAR)[0][1].body)).toEqual({
      alias: null, ubigeo: '080108', direccion: 'Av. de la Cultura 1234', referencia: null,
    })
  })

  it('sin marcarla no guarda nada', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas({ ...aprobado, [GUARDAR]: [201, GUARDADA] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await prepararDomicilio(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })
    expect(llamadas(fetchMock, GUARDAR)).toHaveLength(0)
  })

  it('si no se puede guardar, la compra sigue igual', { timeout: 15_000 }, async () => {
    servidor(rutas({ ...aprobado, [GUARDAR]: [409, { code: 'DIRECCION_YA_GUARDADA', error: 'Ya guardaste esa dirección.' }] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await prepararDomicilio(usuario)
    await usuario.click(screen.getByLabelText('Guardar esta dirección para próximas compras'))
    await usuario.click(screen.getByRole('button', { name: PAGAR }))

    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/pedidos/78')
  })

  it('si el pago se rechaza no guarda la dirección, y al corregirla y pagar guarda solo la corregida', { timeout: 15_000 }, async () => {
    let intentos = 0
    const fetchMock = servidor(rutas({
      [GUARDAR]: [201, GUARDADA],
      'POST /api/pedidos/78/pagos': () => (++intentos === 1
        ? [200, { estado: 'RECHAZADO', mensaje: 'Tu banco rechazó el pago.', pedido: PEDIDO }]
        : [200, { estado: 'APROBADO', mensaje: 'Pago aprobado.', pedido: CONFIRMADO }]),
    }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await prepararDomicilio(usuario)
    await usuario.click(screen.getByLabelText('Guardar esta dirección para próximas compras'))
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(await screen.findByText('Tu pago fue rechazado')).toBeInTheDocument()
    // Rechazado el pago, la compra no se confirmo: nada que guardar todavia
    expect(llamadas(fetchMock, GUARDAR)).toHaveLength(0)

    await usuario.clear(screen.getByLabelText('Dirección de entrega'))
    await usuario.type(screen.getByLabelText('Dirección de entrega'), 'Av. de la Cultura 4321')
    await usuario.type(screen.getByLabelText('CVV'), '123')
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()
    const guardadas = llamadas(fetchMock, GUARDAR)
    expect(guardadas).toHaveLength(1)
    expect(JSON.parse(guardadas[0][1].body).direccion).toBe('Av. de la Cultura 4321')
  })
})

describe('CW-09 Mis pedidos con un pedido a domicilio (CCPIP-59)', () => {
  it('dice que se entrega a domicilio y de qué punto sale, sin el seguimiento de recojo', async () => {
    const resumen = {
      id: 78, numero: 'CCP-000078', estado: 'CONFIRMADO', modalidad: 'ENVIO', total: 812,
      creadoEn: '2026-10-23T15:00:00Z', confirmadoEn: '2026-10-23T15:01:02Z', punto: CUSCO,
      productos: 2, comunidades: 2, historial: [{ estado: 'CONFIRMADO', registradoEn: '2026-10-23T15:01:02Z' }],
    }
    servidor(rutas({ 'GET /api/pedidos': [200, [resumen]] }))
    abrirEn('/cuenta/pedidos')

    const tarjeta = within(await screen.findByRole('article', { name: 'Pedido CCP-000078' }))
    expect(tarjeta.getByText('Entrega a domicilio')).toBeInTheDocument()
    expect(tarjeta.getByText('Sale de Centro Regional Cusco')).toBeInTheDocument()
    expect(tarjeta.queryByText('Sede central CCPIP')).not.toBeInTheDocument()
    expect(tarjeta.queryByText('Listo para recojo')).not.toBeInTheDocument()
  })
})

describe('A-05 Tablero del punto con un pedido a domicilio (CCPIP-59)', () => {
  const tablero = (estado) => {
    const datos = copia(DATOS.pedidos)
    datos.items[0].estado = estado
    datos.items[0].modalidad = 'ENVIO'
    return datos
  }
  const comoAcopio = (datos) => ({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_ACOPIO],
    'GET /api/acopio/pedidos': [200, datos],
  })

  it('lo marca como a domicilio y deja registrar su preparación', async () => {
    servidor(comoAcopio(tablero('CONFIRMADO')))
    abrirEn('/acopio/pedidos')
    const confirmado = within(await screen.findByRole('region', { name: 'Confirmado' }))
    expect(confirmado.getByText('A domicilio')).toBeInTheDocument()
    expect(confirmado.getByRole('button', { name: 'Registrar la preparación del pedido MC-00155' })).toBeEnabled()
  })

  it('en preparación no ofrece marcarlo listo para recojo, sino registrar su salida (CCPIP-320)', async () => {
    servidor(comoAcopio(tablero('EN_PREPARACION')))
    abrirEn('/acopio/pedidos')
    const preparacion = within(await screen.findByRole('region', { name: 'En preparación' }))
    expect(preparacion.getByText('A domicilio')).toBeInTheDocument()
    expect(preparacion.queryByRole('button', { name: /listo para recojo/ })).not.toBeInTheDocument()
    expect(preparacion.getByRole('button', { name: 'Registrar la salida del pedido MC-00155 para despacho' })).toBeEnabled()
  })

  it('un pedido para recojo sigue sin la marca y con su acción', async () => {
    const datos = copia(DATOS.pedidos)
    datos.items[0].estado = 'EN_PREPARACION'
    datos.items[0].modalidad = 'RECOJO'
    servidor(comoAcopio(datos))
    abrirEn('/acopio/pedidos')
    const preparacion = within(await screen.findByRole('region', { name: 'En preparación' }))
    expect(preparacion.queryByText('A domicilio')).not.toBeInTheDocument()
    expect(preparacion.getByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' })).toBeEnabled()
  })
})
