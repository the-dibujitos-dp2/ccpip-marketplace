import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_ACOPIO, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const LISTAR = 'GET /api/acopio/pedidos'
const LISTO = 'POST /api/acopio/pedidos/MC-00155/listo'

const comoAcopio = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_ACOPIO],
  [LISTAR]: () => [200, copia(DATOS.pedidos)],
  ...rutas,
})

const columna = (nombre) => screen.getByRole('region', { name: nombre })

describe('A-05 Pedidos del punto de acopio (CCPIP-159)', () => {
  it('muestra cada pedido en la columna de su estado, con sus productos, total y quien lo marco', async () => {
    servidor(comoAcopio())
    abrirEn('/acopio/pedidos')

    expect(await screen.findByRole('heading', { level: 1, name: 'Pedidos' })).toBeInTheDocument()
    expect(await screen.findByText('Centro Nacional Lima / Pedidos')).toBeInTheDocument()

    // Confirmado: espera su preparacion; se puede marcar como listo
    const confirmado = within(columna('Confirmado'))
    expect(confirmado.getByRole('heading', { name: 'Confirmado (1)' })).toBeInTheDocument()
    expect(confirmado.getByText('MC-00155')).toBeInTheDocument()
    expect(confirmado.getByText('Asoc. Nii Joi · Aceite de ungurahui 250 ml × 1')).toBeInTheDocument()
    expect(confirmado.getByText('Asoc. Nii Joi · Aceite de aguaje 250 ml × 1')).toBeInTheDocument()
    expect(confirmado.getByText('Confirmado 13/10 06:08 · S/ 96.00')).toBeInTheDocument()
    expect(confirmado.getByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' })).toBeEnabled()

    // Listo: con el responsable y la hora del historial (hora de Lima), sin boton
    const listo = within(columna('Listo para recojo'))
    expect(listo.getByRole('heading', { name: 'Listo para recojo (1)' })).toBeInTheDocument()
    expect(listo.getByText('MC-00139')).toBeInTheDocument()
    expect(listo.getByText('C.N. Yarina · Cacao en grano fermentado 1 kg × 2')).toBeInTheDocument()
    expect(listo.getByText('Listo 13/10 07:38 · Administrador de Acopio de Prueba · S/ 160.00')).toBeInTheDocument()
    expect(listo.queryByRole('button')).not.toBeInTheDocument()

    const entregado = within(columna('Entregado hoy'))
    expect(entregado.getByText('MC-00131')).toBeInTheDocument()
    expect(entregado.queryByRole('button')).not.toBeInTheDocument()

    // Nunca se nombra al comprador (RNF-05)
    expect(screen.queryByText(/Consumidor de Prueba|consumidor@/)).not.toBeInTheDocument()
  })

  it('cada linea muestra los lotes asignados, el que vence antes primero (CCPIP-74)', async () => {
    servidor(comoAcopio())
    abrirEn('/acopio/pedidos')
    await screen.findByText('MC-00155')

    // Un pedido repartido en dos lotes: primero el que vence antes
    const cafe = within(screen.getByRole('list', { name: 'Lotes de Cafe tostado en grano 500 g' })).getAllByRole('listitem')
    expect(cafe.map((l) => l.textContent)).toEqual([
      'Lote L-0010B · vence 02/11/2026 · 2 und',
      'Lote L-0010 · vence 13/10/2027 · 1 und',
    ])
    // Una linea con un solo lote, en cada columna
    expect(within(columna('Confirmado')).getByText('Lote L-0008 · vence 13/10/2027 · 1 und')).toBeInTheDocument()
    expect(within(columna('Listo para recojo')).getByText('Lote L-0001 · vence 13/10/2027 · 2 und')).toBeInTheDocument()
    // Si el pedido aun no descuenta existencias, lo dice en lugar de callar
    expect(within(columna('Listo para recojo')).getByText('Sin lotes asignados')).toBeInTheDocument()
  })

  it('lo que no vence, como una artesania, lo dice en lugar de mostrar una fecha vacia', async () => {
    const datos = copia(DATOS.pedidos)
    delete datos.items[0].lineas[0].lotes[0].fechaVencimiento
    servidor(comoAcopio({ [LISTAR]: [200, datos] }))
    abrirEn('/acopio/pedidos')

    expect(await screen.findByText('Lote L-0008 · no vence · 1 und')).toBeInTheDocument()
  })

  it('marcar listo pasa el pedido a su columna, lo avisa y no vuelve a ofrecer el boton', async () => {
    const fetchMock = servidor(comoAcopio({ [LISTO]: () => [200, copia(DATOS.pedidoListo)] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' }))

    expect(await screen.findByText('El pedido MC-00155 está listo para recojo')).toBeInTheDocument()
    expect(llamadas(fetchMock, LISTO)).toHaveLength(1)
    const listo = within(columna('Listo para recojo'))
    expect(listo.getByRole('heading', { name: 'Listo para recojo (2)' })).toBeInTheDocument()
    expect(listo.getByText('MC-00155')).toBeInTheDocument()
    expect(listo.getByText('Listo 13/10 10:33 · Administrador de Acopio de Prueba · S/ 96.00')).toBeInTheDocument()
    // Los lotes de la respuesta viajan con el pedido a su nueva columna
    expect(listo.getByText('Lote L-0008 · vence 13/10/2027 · 1 und')).toBeInTheDocument()
    expect(within(columna('Confirmado')).getByRole('heading', { name: 'Confirmado (0)' })).toBeInTheDocument()
    expect(within(columna('Confirmado')).getByText('No hay pedidos por preparar.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Marcar el pedido/ })).not.toBeInTheDocument()
    // Solo se cambio ese pedido: la lista no se volvio a pedir
    expect(llamadas(fetchMock, LISTAR)).toHaveLength(1)
  })

  it('si otra persona ya lo cambio, explica el motivo y vuelve a leer la lista', async () => {
    let lecturas = 0
    servidor(comoAcopio({
      [LISTAR]: () => {
        const datos = copia(DATOS.pedidos)
        // Desde la segunda lectura, MC-00155 ya esta listo
        if (++lecturas > 1) Object.assign(datos.items[0], copia(DATOS.pedidoListo))
        return [200, datos]
      },
      [LISTO]: [409, { code: 'ESTADO_INVALIDO', error: 'Este pedido ya está listo para recojo.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No pudimos marcar el pedido')
    expect(alerta).toHaveTextContent('Este pedido ya está listo para recojo.')
    await waitFor(() => expect(within(columna('Listo para recojo')).getByText('MC-00155')).toBeInTheDocument())
    expect(lecturas).toBe(2)
  })

  it('mientras marca, el boton queda deshabilitado', async () => {
    let terminar
    servidor(comoAcopio({
      [LISTO]: () => new Promise((resolver) => { terminar = () => resolver([200, copia(DATOS.pedidoListo)]) }),
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' }))
    expect(screen.getByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' })).toBeDisabled()
    terminar()
    expect(await screen.findByText('El pedido MC-00155 está listo para recojo')).toBeInTheDocument()
  })

  it('sin pedidos invita a esperar; si falla la carga lo dice y permite reintentar', async () => {
    servidor(comoAcopio({ [LISTAR]: [200, { ubicacion: 'Centro Nacional Lima', items: [] }] }))
    const { unmount } = abrirEn('/acopio/pedidos')
    expect(await screen.findByText('Aún no hay pedidos')).toBeInTheDocument()
    unmount()

    let intentos = 0
    servidor(comoAcopio({ [LISTAR]: () => (++intentos === 1 ? new Error('sin red') : [200, copia(DATOS.pedidos)]) }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    expect(await screen.findByText('No pudimos cargar los pedidos')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('MC-00155')).toBeInTheDocument()
  })

  it('desde el inicio y el menu del punto se llega a Pedidos y a Movimientos', async () => {
    servidor(comoAcopio({
      'GET /api/panel/acopio': [200, { titulo: 'Tu punto', descripcion: 'Panel del punto' }],
      'GET /api/acopio/movimientos?pagina=0': [200, copia(DATOS.movimientos)],
    }))
    const user = userEvent.setup()
    abrirEn('/inicio')
    await user.click(await screen.findByRole('link', { name: 'Ver pedidos del punto' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Pedidos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/acopio/pedidos')
    expect(screen.getByRole('link', { name: 'Pedidos' })).toHaveAttribute('aria-current', 'page')

    await user.click(screen.getByRole('link', { name: 'Movimientos' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Movimientos' })).toBeInTheDocument()
  })

  it('/acopio lleva a Pedidos; solo entra el administrador de punto', async () => {
    servidor(comoAcopio())
    const { unmount } = abrirEn('/acopio')
    await waitFor(() => expect(window.location.pathname).toBe('/acopio/pedidos'))
    unmount()

    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION],
      'GET /api/panel/consumidor': [200, { titulo: 'Tu mercado', descripcion: 'Panel' }],
    })
    const otro = abrirEn('/acopio/pedidos')
    await waitFor(() => expect(window.location.pathname).toBe('/inicio'))
    expect(llamadas(fetchMock, LISTAR)).toHaveLength(0)
    otro.unmount()

    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/acopio/pedidos')
    await waitFor(() => expect(window.location.pathname).toBe('/ingresar'))
  })
})
