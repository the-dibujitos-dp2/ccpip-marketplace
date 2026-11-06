import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { abrirPestana, copia, DATOS, llamadas, panel, PUBLICAS, servidor, SESION, SESION_ACOPIO } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

// CCPIP-483: el monto depende del envio del distrito elegido (812 en productos, mas 12 a Wanchaq o 10 a Miraflores)
const PAGAR = /^Pagar S\/\s8(12|22|24)\.00$/

const LIMA = { id: 1, nombre: 'Centro Nacional Lima', departamento: 'Lima', provincia: 'Lima', distrito: 'Lima', direccion: 'Sede central CCPIP' }
const CUSCO = { id: 3, nombre: 'Centro Regional Cusco', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Cusco', direccion: 'Centro regional andino' }

const WANCHAQ = { ubigeo: '080108', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Wanchaq' }
const MIRAFLORES = { ubigeo: '150122', departamento: 'Lima', provincia: 'Lima', distrito: 'Miraflores' }
// Wanchaq lo cubre solo Cusco; Miraflores lo cubren dos puntos
const DISTRITOS = [
  { distrito: WANCHAQ, envio: 12, puntos: [CUSCO] },
  { distrito: MIRAFLORES, envio: 10, puntos: [LIMA, CUSCO] },
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
  costoEnvio: 12,
  totalAPagar: 824,
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
  pagos: [{ id: 1, estado: 'APROBADO', monto: 824, marca: 'VISA', referencia: 'SIM-123', mensaje: null, creadoEn: '2026-10-23T15:01:00Z', resueltoEn: '2026-10-23T15:01:02Z' }],
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
  'GET /api/cuenta/direcciones': [200, []],
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
    // CCPIP-482: la tarjeta dice desde cuanto cuesta el envio; el resumen pide elegir el distrito
    expect(screen.getByRole('radio', { name: /Desde S\/\s10\.00/ })).toBeChecked()
    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    expect(within(resumen).getByText('A domicilio')).toBeInTheDocument()
    expect(within(resumen).getByText('Elige tu distrito')).toBeInTheDocument()
  })

  it('CCPIP-483: suma la tarifa del distrito elegido al total y al botón de pagar', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    const resumen = within(screen.getByRole('complementary', { name: 'Resumen del pedido' }))
    expect(screen.getByRole('button', { name: /^Pagar S\/\s812\.00$/ })).toBeInTheDocument()

    await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')
    expect(resumen.getByText('Envío').nextElementSibling).toHaveTextContent(/S\/\s12\.00/)
    expect(resumen.getByText('Productos').nextElementSibling).toHaveTextContent(/S\/\s812\.00/)
    expect(screen.getByRole('button', { name: /^Pagar S\/\s824\.00$/ })).toBeInTheDocument()

    await elegirDestino(usuario, 'Lima', 'Lima', '150122')
    expect(screen.getByRole('button', { name: /^Pagar S\/\s822\.00$/ })).toBeInTheDocument()

    // El recojo no tiene costo de envio
    await usuario.click(screen.getByRole('radio', { name: /^Recojo en punto de acopio/ }))
    expect(resumen.getByText('Envío').nextElementSibling).toHaveTextContent(/S\/\s0\.00/)
    expect(screen.getByRole('button', { name: /^Pagar S\/\s812\.00$/ })).toBeInTheDocument()
  })

  it('CCPIP-483: si la tarifa cambió al crear el pedido, no cobra y avisa el nuevo monto', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas({ 'POST /api/pedidos': [201, { ...PEDIDO, costoEnvio: 15, totalAPagar: 827 }] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await prepararDomicilio(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(await screen.findByText(/^La tarifa de envío cambió: ahora pagas S\/\s827\.00\./)).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/pedidos/78/pagos')).toHaveLength(0)
  })

  it('CCPIP-484: si el punto completó su capacidad del día lo explica y recarga las opciones', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas({ 'POST /api/pedidos': [409, { code: 'CAPACIDAD_AGOTADA', message: 'x' }] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await prepararDomicilio(usuario)
    const antes = llamadas(fetchMock, 'GET /api/compra/distritos').length
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(await screen.findByText('Ese punto ya completó sus entregas de hoy')).toBeInTheDocument()
    expect(screen.getByText('Este punto de acopio ya no puede entregar más pedidos a domicilio hoy. Elige otro punto o el recojo.')).toBeInTheDocument()
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/compra/distritos').length).toBeGreaterThan(antes))
    expect(llamadas(fetchMock, 'POST /api/pedidos/78/pagos')).toHaveLength(0)
  })

  it('CCPIP-482: un distrito que perdió su tarifa al confirmar lo explica y recarga', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas({ 'POST /api/pedidos': [409, { code: 'SIN_TARIFA_ENVIO', message: 'x' }] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await prepararDomicilio(usuario)
    const antes = llamadas(fetchMock, 'GET /api/compra/distritos').length
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(await screen.findByText('Aún no entregamos a domicilio en ese distrito')).toBeInTheDocument()
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/compra/distritos').length).toBeGreaterThan(antes))
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

  it('CCPIP-60: consulta la factibilidad con el distrito y avisa antes de pagar si el punto ya no lo cubre', async () => {
    const FACTIBILIDAD = 'GET /api/compra/factibilidad?punto=3&ubigeo=080108'
    const fetchMock = servidor(rutas({
      [FACTIBILIDAD]: [200, { factible: false, existencias: true, cobertura: false, motivos: ['SIN_COBERTURA'] }],
    }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await elegirDestino(usuario, 'Cusco', 'Cusco', '080108')

    expect(await screen.findByText('Este punto ya no puede entregar tu pedido')).toBeInTheDocument()
    expect(screen.getByText(/No entrega a domicilio en el distrito que elegiste/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: PAGAR })).toBeDisabled()
    expect(llamadas(fetchMock, FACTIBILIDAD)).toHaveLength(1)
    expect(llamadas(fetchMock, 'POST /api/pedidos')).toHaveLength(0)
  })

  it('CCPIP-60: si al pagar el punto dejó de cubrir el distrito, no hubo cobro: lo explica y recarga', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(rutas({
      'POST /api/pedidos/78/pagos': [409, { code: 'SIN_COBERTURA', error: 'El punto de acopio ya no entrega a domicilio en ese distrito. No se hizo ningún cobro; elige otro punto o el recojo.' }],
    }))
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
    expect(within(alerta).getByText(/No se hizo ningún cobro/)).toBeInTheDocument()
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
    expect(within(screen.getByRole('complementary', { name: 'Resumen del pedido' })).getByText('Recojo en el punto')).toBeInTheDocument()
    expect(screen.getByLabelText('Celular de contacto')).toBeInTheDocument()
  })
})

describe('CW-06 Pedido a domicilio (CCPIP-59)', () => {
  it('muestra a dónde se entrega y su seguimiento a domicilio, sin el de recojo', async () => {
    servidor(rutas())
    abrirEn('/pedidos/78')
    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()

    const entrega = screen.getByRole('heading', { name: 'Entrega a domicilio' }).closest('section')
    // CCPIP-442: la forma de entrega en el resumen
    expect(within(entrega).getByText('Envío a domicilio')).toBeInTheDocument()
    expect(within(entrega).getByText('Centro Regional Cusco')).toBeInTheDocument()
    expect(within(entrega).getByText('Av. de la Cultura 1234')).toBeInTheDocument()
    expect(within(entrega).getByText('Wanchaq · Cusco · Cusco')).toBeInTheDocument()
    expect(within(entrega).getByText('Frente al grifo')).toBeInTheDocument()
    expect(within(entrega).getByText('María Quispe')).toBeInTheDocument()
    expect(within(entrega).getByText('987654321')).toBeInTheDocument()
    // Sus etapas son las del reparto (CCPIP-318, CCPIP-319): nada de "disponible en acopio"
    const etapas = within(screen.getByRole('list', { name: /Seguimiento/ })).getAllByRole('listitem')
    expect(etapas.map((e) => e.querySelector('.seguimiento__nombre').textContent))
      .toEqual(['Pago confirmado', 'En preparación', 'Listo para despacho', 'En camino', 'Entregado'])
    expect(screen.queryByText('Disponible en acopio')).not.toBeInTheDocument()
    // CCPIP-483: el envio va aparte de los productos y se suma al total pagado
    expect(screen.getByText('Envío').nextElementSibling).toHaveTextContent(/S\/\s12\.00/)
    expect(screen.getByText('Total').nextElementSibling).toHaveTextContent(/S\/\s824\.00/)
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
  it('dice que se entrega a domicilio, de qué punto sale y en qué etapa del reparto está', async () => {
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
    const etapas = within(tarjeta.getByRole('list', { name: 'Seguimiento del pedido CCP-000078' })).getAllByRole('listitem')
    expect(etapas[0]).toHaveAttribute('aria-current', 'step')
    expect(etapas[3]).toHaveTextContent('En camino')
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
    const confirmado = await panel('Confirmado')
    expect(confirmado.getByText('A domicilio')).toBeInTheDocument()
    expect(confirmado.getByRole('button', { name: 'Registrar la preparación del pedido MC-00155' })).toBeEnabled()
  })

  it('en preparación no ofrece marcarlo listo para recojo, sino registrar su salida (CCPIP-320)', async () => {
    servidor(comoAcopio(tablero('EN_PREPARACION')))
    abrirEn('/acopio/pedidos')
    const preparacion = await abrirPestana(userEvent.setup(), 'En preparación')
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
    const preparacion = await abrirPestana(userEvent.setup(), 'En preparación')
    expect(preparacion.queryByText('A domicilio')).not.toBeInTheDocument()
    expect(preparacion.getByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' })).toBeEnabled()
  })
})

describe('CW-06 Elegir una dirección guardada al comprar (CCPIP-446)', () => {
  const aprobado = { 'POST /api/pedidos/78/pagos': [200, { estado: 'APROBADO', mensaje: 'Pago aprobado.', pedido: CONFIRMADO }] }
  const OFICINA = { id: 9, alias: null, ubigeo: '150140', departamento: 'Lima', provincia: 'Lima', distrito: 'Santiago de Surco', direccion: 'Av. Primavera 500', referencia: 'Piso 3' }
  const conGuardadas = (extra = {}) => rutas({ 'GET /api/cuenta/direcciones': [200, [{ ...GUARDADA, referencia: 'Frente al grifo' }, OFICINA]], ...extra })
  const selector = () => screen.getByRole('combobox', { name: 'Mis direcciones guardadas' })

  it('sin direcciones guardadas no muestra el selector', async () => {
    servidor(rutas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await screen.findByLabelText('Dirección de entrega')
    expect(screen.queryByRole('combobox', { name: 'Mis direcciones guardadas' })).not.toBeInTheDocument()
  })

  it('elegir una guardada llena el destino y el punto, y el pedido sale con ella sin volver a guardarla', { timeout: 15_000 }, async () => {
    const fetchMock = servidor(conGuardadas(aprobado))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)

    await within(await screen.findByRole('combobox', { name: 'Mis direcciones guardadas' })).findByRole('option', { name: 'Casa · Av. de la Cultura 1234, Wanchaq' })
    // La de un distrito sin entrega con este carrito tambien se lista, marcada
    expect(within(selector()).getByRole('option', { name: 'Dirección de entrega · Av. Primavera 500, Santiago de Surco (sin entrega con tu carrito)' })).toBeInTheDocument()

    await usuario.selectOptions(selector(), '5')
    expect(screen.getByLabelText('Departamento')).toHaveValue('Cusco')
    await waitFor(() => expect(screen.getByLabelText('Distrito de entrega')).toHaveValue('080108'))
    expect(screen.getByLabelText('Provincia')).toHaveValue('Cusco')
    expect(screen.getByLabelText('Dirección de entrega')).toHaveValue('Av. de la Cultura 1234')
    expect(screen.getByLabelText('Referencia (opcional)')).toHaveValue('Frente al grifo')
    // Ya esta guardada: no ofrece guardarla otra vez
    expect(screen.queryByLabelText('Guardar esta dirección para próximas compras')).not.toBeInTheDocument()

    await datosDeTarjeta(usuario)
    await usuario.click(screen.getByRole('button', { name: PAGAR }))
    expect(await screen.findByRole('heading', { name: '¡Gracias por tu compra!' })).toBeInTheDocument()
    const [, pedido] = llamadas(fetchMock, 'POST /api/pedidos')[0]
    expect(JSON.parse(pedido.body)).toMatchObject({
      puntoId: 3, modalidad: 'ENVIO', entrega: { ubigeo: '080108', direccion: 'Av. de la Cultura 1234', referencia: 'Frente al grifo' },
    })
    expect(llamadas(fetchMock, GUARDAR)).toHaveLength(0)
  })

  it('una guardada en un distrito sin entrega avisa como si se eligiera a mano', async () => {
    servidor(conGuardadas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await within(await screen.findByRole('combobox', { name: 'Mis direcciones guardadas' })).findByRole('option', { name: /Av. Primavera 500/ })

    await usuario.selectOptions(selector(), '9')
    expect(await screen.findByText('Por ahora no entregamos en Santiago de Surco con tu carrito')).toBeInTheDocument()
  })

  it('cambiar la dirección a mano deja de usar la guardada y vuelve a ofrecer guardarla', async () => {
    servidor(conGuardadas())
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    await within(await screen.findByRole('combobox', { name: 'Mis direcciones guardadas' })).findByRole('option', { name: /Casa/ })

    await usuario.selectOptions(selector(), '5')
    await usuario.type(screen.getByLabelText('Dirección de entrega'), ', dpto. 2')
    expect(selector()).toHaveValue('')
    expect(screen.getByLabelText('Guardar esta dirección para próximas compras')).toBeInTheDocument()
  })

  it('si no se pueden leer las guardadas, se escribe la dirección como siempre', async () => {
    servidor(rutas({ 'GET /api/cuenta/direcciones': [500, { code: 'ERROR', error: 'Falla' }] }))
    const usuario = userEvent.setup()
    abrirEn('/compra')
    await elegirDomicilio(usuario)
    expect(await screen.findByLabelText('Dirección de entrega')).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Mis direcciones guardadas' })).not.toBeInTheDocument()
  })
})
