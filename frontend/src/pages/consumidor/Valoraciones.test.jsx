import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const ficha = (resumen = DATOS.calificacionesProducto) => ({
  ...PUBLICAS,
  'GET /api/productos/1': () => [200, copia(DATOS.detalle)],
  'GET /api/productos/1/calificaciones': () => (resumen instanceof Error ? resumen : [200, copia(resumen)]),
})
const comoVisitante = (rutas = {}) => ({ ...ficha(), 'GET /api/auth/me': SIN_SESION, ...rutas })
const comoComprador = (rutas = {}) => ({
  ...ficha(),
  'GET /api/auth/me': [200, SESION],
  'GET /api/carrito': [200, copia(DATOS.carrito)],
  ...rutas,
})

const seccion = async () => within(await screen.findByRole('region', { name: 'Calificaciones' }))

describe('CW-03 Calificaciones de la oferta (CCPIP-82)', () => {
  afterEach(() => i18n.changeLanguage('es'))

  it('con suficiente evidencia muestra el promedio, la distribución y opiniones sin nombres', async () => {
    servidor(comoVisitante())
    abrirEn('/productos/1')
    const s = await seccion()
    expect(await s.findByText('4.3')).toBeInTheDocument()
    expect(s.getAllByRole('img', { name: '4.3 de 5 estrellas' })).toHaveLength(1)
    expect(s.getByText('Basado en 4 calificaciones')).toBeInTheDocument()
    const niveles = within(s.getByRole('list', { name: 'Calificaciones por estrellas' })).getAllByRole('listitem')
    expect(niveles.map((n) => n.textContent)).toEqual(['5 estrellas2', '4 estrellas1', '3 estrellas1', '2 estrellas0', '1 estrella0'])
    const opiniones = within(s.getByRole('list', { name: 'Opiniones de compradores' })).getAllByRole('listitem')
    expect(opiniones).toHaveLength(2)
    expect(within(opiniones[0]).getByText('Llegó fresco y bien envasado.')).toBeInTheDocument()
    expect(within(opiniones[0]).getByText('Compra verificada · 12 oct 2026')).toBeInTheDocument()
    // Un visitante puede ingresar para denunciar, sin perder la ficha
    expect(s.getByRole('link', { name: 'Ingresa para denunciar este producto' }))
      .toHaveAttribute('href', `/ingresar?volver=${encodeURIComponent('/productos/1')}`)
  })

  it('sin suficiente evidencia no muestra promedio, y sin calificaciones tampoco un cero', async () => {
    servidor(comoVisitante({ 'GET /api/productos/1/calificaciones': [200, { productoId: 1, total: 2, minimo: 3, suficiente: false }] }))
    const { unmount } = abrirEn('/productos/1')
    let s = await seccion()
    expect(await s.findByText('Hay 2 calificaciones. Mostraremos el promedio cuando haya al menos 3.')).toBeInTheDocument()
    expect(s.queryByRole('img', { name: /de 5 estrellas/ })).not.toBeInTheDocument()
    unmount()

    servidor(comoVisitante({ 'GET /api/productos/1/calificaciones': [200, { productoId: 1, total: 0, minimo: 3, suficiente: false }] }))
    abrirEn('/productos/1')
    s = await seccion()
    expect(await s.findByText('Aún no tiene reseñas. Si lo compras, puedes ser la primera persona en valorarlo.')).toBeInTheDocument()
    expect(s.queryByText('0')).not.toBeInTheDocument()
  })

  // CCPIP-409: sin resenas, un mensaje amable en lugar de un bloque vacio
  it('sin resenas invita a ser la primera persona en valorarlo, sin lista vacia ni promedio', async () => {
    servidor(comoVisitante({ 'GET /api/productos/1/calificaciones': [200, { productoId: 1, total: 0, minimo: 3, suficiente: false, resenas: [] }] }))
    const { unmount } = abrirEn('/productos/1')
    let s = await seccion()
    expect(await s.findByText('Aún no tiene reseñas. Si lo compras, puedes ser la primera persona en valorarlo.')).toBeInTheDocument()
    expect(s.queryByRole('list', { name: 'Opiniones de compradores' })).not.toBeInTheDocument()
    expect(s.queryByRole('img', { name: /de 5 estrellas/ })).not.toBeInTheDocument()
    unmount()

    await i18n.changeLanguage('en')
    servidor(comoVisitante({ 'GET /api/productos/1/calificaciones': [200, { productoId: 1, total: 0, minimo: 3, suficiente: false, resenas: [] }] }))
    abrirEn('/productos/1')
    s = within(await screen.findByRole('region', { name: 'Ratings' }))
    expect(await s.findByText('No reviews yet. If you buy it, you can be the first person to rate it.')).toBeInTheDocument()
  })

  it('si el resumen falla, la ficha se ve igual', async () => {
    servidor(comoVisitante({ 'GET /api/productos/1/calificaciones': new TypeError('Failed to fetch') }))
    abrirEn('/productos/1')
    const s = await seccion()
    expect(await screen.findByRole('heading', { level: 1, name: DATOS.detalle.nombre })).toBeInTheDocument()
    expect(s.queryByText(/Basado en/)).not.toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    servidor(comoVisitante())
    abrirEn('/productos/1')
    const s = within(await screen.findByRole('region', { name: 'Ratings' }))
    expect(await s.findByText('Based on 4 ratings')).toBeInTheDocument()
    expect(s.getByRole('img', { name: '4.3 out of 5 stars' })).toBeInTheDocument()
    expect(s.getAllByText(/^Verified purchase/)).toHaveLength(2)
  })
})

describe('Denunciar una oferta (CCPIP-83)', () => {
  it('el comprador elige motivo y describe lo ocurrido; se registra sin aprobación previa', async () => {
    const fetchMock = servidor(comoComprador({
      'POST /api/denuncias': [201, { id: 5, estado: 'RECIBIDA', motivo: 'CALIDAD', productor: { id: 1, nombre: 'Asoc. Nii Joi' } }],
    }))
    const user = userEvent.setup()
    abrirEn('/productos/1')
    const s = await seccion()
    await user.click(await s.findByRole('button', { name: 'Denunciar este producto' }))

    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByText(`Sobre: ${DATOS.detalle.nombre}`)).toBeInTheDocument()
    expect(dialogo.getByText(/El productor la ve sin tu nombre/)).toBeInTheDocument()
    await user.click(dialogo.getByRole('button', { name: 'Enviar denuncia' }))
    expect(dialogo.getByText('Elige el motivo de la denuncia.')).toBeInTheDocument()
    expect(dialogo.getByText('Describe lo que ocurrió (al menos 10 caracteres).')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/denuncias')).toHaveLength(0)

    await user.selectOptions(dialogo.getByLabelText('Motivo'), 'Mala calidad o en mal estado')
    await user.type(dialogo.getByLabelText('Descripción'), '  Llegó rancio y abierto.  ')
    await user.click(dialogo.getByRole('button', { name: 'Enviar denuncia' }))

    expect(await s.findByText('Recibimos tu denuncia')).toBeInTheDocument()
    const [, opciones] = llamadas(fetchMock, 'POST /api/denuncias')[0]
    expect(JSON.parse(opciones.body)).toEqual({ productoId: 1, motivo: 'CALIDAD', descripcion: 'Llegó rancio y abierto.' })
  })

  it('muestra el motivo del servidor cuando ya hay una denuncia abierta', async () => {
    servidor(comoComprador({
      'POST /api/denuncias': [409, { code: 'DENUNCIA_DUPLICADA', error: 'Ya tienes una denuncia abierta sobre esto. La Cámara la está atendiendo.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await user.click(await (await seccion()).findByRole('button', { name: 'Denunciar este producto' }))
    const dialogo = within(screen.getByRole('dialog'))
    await user.selectOptions(dialogo.getByLabelText('Motivo'), 'Otro motivo')
    await user.type(dialogo.getByLabelText('Descripción'), 'Vuelvo a denunciar lo mismo.')
    await user.click(dialogo.getByRole('button', { name: 'Enviar denuncia' }))
    expect(await dialogo.findByText('Ya tienes una denuncia abierta sobre esto. La Cámara la está atendiendo.')).toBeInTheDocument()
    await user.click(dialogo.getByRole('button', { name: 'Cancelar' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})

const PEDIDO = {
  ...DATOS.misPedidos.find((p) => p.id === 60),
  personaAutorizada: null,
  celularContacto: '987654321',
  lineas: [
    { productoId: 1, nombre: 'Aceite de ungurahui 250 ml', tienda: 'Asoc. Nii Joi', cantidad: 1, precioUnitario: 24, subtotal: 24 },
    { productoId: 3, nombre: 'Camu camu en polvo 150 g', tienda: 'Asoc. Nii Joi', cantidad: 2, precioUnitario: 12, subtotal: 24 },
  ],
  pagos: [],
  comision: null,
}

const conPedido = (rutas = {}) => comoComprador({
  'GET /api/pedidos/60': () => [200, copia(PEDIDO)],
  'GET /api/pedidos/60/calificaciones': () => [200, copia(DATOS.calificacionesPedido)],
  ...rutas,
})

describe('Calificar un pedido recibido (CCPIP-81)', () => {
  it('califica con estrellas cada producto aún sin calificar; lo calificado se ve y no se edita', async () => {
    const calificado = copia(DATOS.calificacionesPedido)
    calificado.pendientes = 0
    calificado.lineas[0].calificacion = { estrellas: 5, comentario: 'Excelente', calificadoEn: '2026-10-18T15:00:00Z' }
    const fetchMock = servidor(conPedido({ 'POST /api/pedidos/60/calificaciones': [201, calificado] }))
    const user = userEvent.setup()
    abrirEn('/pedidos/60')

    const s = within(await screen.findByRole('region', { name: 'Califica tus productos' }))
    expect(s.getByText('Te falta calificar 1 producto.')).toBeInTheDocument()
    const camu = within(s.getByRole('article', { name: 'Camu camu en polvo 150 g' }))
    expect(camu.getByRole('img', { name: '4 de 5 estrellas' })).toBeInTheDocument()
    expect(camu.queryByRole('button', { name: 'Enviar calificación' })).not.toBeInTheDocument()

    const aceite = within(s.getByRole('article', { name: 'Aceite de ungurahui 250 ml' }))
    await user.click(aceite.getByRole('button', { name: 'Enviar calificación' }))
    expect(aceite.getByText('Elige de 1 a 5 estrellas.')).toBeInTheDocument()
    await user.click(aceite.getByRole('radio', { name: '5 estrellas' }))
    await user.type(aceite.getByLabelText('Comentario (opcional)'), 'Excelente')
    await user.click(aceite.getByRole('button', { name: 'Enviar calificación' }))

    expect(await s.findByText('Ya calificaste todos los productos de este pedido. ¡Gracias!')).toBeInTheDocument()
    const [, opciones] = llamadas(fetchMock, 'POST /api/pedidos/60/calificaciones')[0]
    expect(JSON.parse(opciones.body)).toEqual({ itemId: 601, estrellas: 5, comentario: 'Excelente' })
    expect(within(s.getByRole('article', { name: 'Aceite de ungurahui 250 ml' })).getByText('Excelente')).toBeInTheDocument()
  })

  it('si el servidor rechaza la calificación, explica por qué', async () => {
    servidor(conPedido({
      'POST /api/pedidos/60/calificaciones': [409, { code: 'LINEA_YA_CALIFICADA', error: 'Ya calificaste este producto de tu pedido.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/pedidos/60')
    const aceite = within(await screen.findByRole('article', { name: 'Aceite de ungurahui 250 ml' }))
    await user.click(aceite.getByRole('radio', { name: '3 estrellas' }))
    await user.click(aceite.getByRole('button', { name: 'Enviar calificación' }))
    expect(await aceite.findByText('Ya calificaste este producto de tu pedido.')).toBeInTheDocument()
  })

  it('desde el pedido recibido también denuncia un producto', async () => {
    const fetchMock = servidor(conPedido({ 'POST /api/denuncias': [201, { id: 6, estado: 'RECIBIDA' }] }))
    const user = userEvent.setup()
    abrirEn('/pedidos/60')
    await user.click(await screen.findByRole('button', { name: 'Denunciar Camu camu en polvo 150 g' }))
    const dialogo = within(screen.getByRole('dialog'))
    await user.selectOptions(dialogo.getByLabelText('Motivo'), 'No corresponde a lo descrito')
    await user.type(dialogo.getByLabelText('Descripción'), 'No era camu camu puro.')
    await user.click(dialogo.getByRole('button', { name: 'Enviar denuncia' }))
    expect(await screen.findByText('Recibimos tu denuncia')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, 'POST /api/denuncias')[0][1].body).productoId).toBe(3)
  })

  it('un pedido que aún no se recibe no ofrece calificar', async () => {
    const fetchMock = servidor(conPedido({ 'GET /api/pedidos/60': [200, { ...copia(PEDIDO), estado: 'LISTO_PARA_RECOJO' }] }))
    abrirEn('/pedidos/60')
    expect(await screen.findByRole('heading', { level: 1, name: 'Pedido CCP-000060' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Califica tus productos' })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/pedidos/60/calificaciones')).toHaveLength(0)
  })
})
