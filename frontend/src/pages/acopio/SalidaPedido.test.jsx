import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_ACOPIO } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const LISTAR = 'GET /api/acopio/pedidos'
const DESPACHO = 'POST /api/acopio/pedidos/MC-00155/despacho'
const BOTON = 'Registrar la salida del pedido MC-00155 para despacho'
const ADMIN = 'Administrador de Acopio de Prueba'

/** El primer pedido del tablero, a domicilio y en el estado pedido. */
function aDomicilio(estado) {
  const pedido = copia(DATOS.pedidos.items[0])
  pedido.modalidad = 'ENVIO'
  pedido.estado = estado
  pedido.historial.push({ estado: 'EN_PREPARACION', registradoEn: '2026-10-13T15:33:58.109980Z', responsable: ADMIN })
  if (estado === 'LISTO_PARA_DESPACHO') {
    pedido.historial.push({ estado, registradoEn: '2026-10-13T16:10:00Z', responsable: ADMIN })
  }
  return pedido
}

function tablero(pedido) {
  const datos = copia(DATOS.pedidos)
  datos.items[0] = pedido
  return datos
}

const comoAcopio = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_ACOPIO],
  [LISTAR]: () => [200, tablero(aDomicilio('EN_PREPARACION'))],
  ...rutas,
})

const columna = (nombre) => screen.getByRole('region', { name: nombre })

describe('A-05 Salida de un pedido a domicilio (CCPIP-320)', () => {
  it('registra la salida por API y pasa el pedido a "Listo para despacho", donde espera a un distribuidor', async () => {
    const fetchMock = servidor(comoAcopio({ [DESPACHO]: () => [200, aDomicilio('LISTO_PARA_DESPACHO')] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')

    const vacia = within(await screen.findByRole('region', { name: 'Listo para despacho' }))
    expect(vacia.getByRole('heading', { name: 'Listo para despacho (0)' })).toBeInTheDocument()
    expect(vacia.getByText('Ningún pedido espera a un distribuidor.')).toBeInTheDocument()

    await user.click(within(columna('En preparación')).getByRole('button', { name: BOTON }))

    expect(await screen.findByText('El pedido MC-00155 está listo para despacho')).toBeInTheDocument()
    expect(llamadas(fetchMock, DESPACHO)).toHaveLength(1)
    const despacho = within(columna('Listo para despacho'))
    expect(despacho.getByRole('heading', { name: 'Listo para despacho (1)' })).toBeInTheDocument()
    expect(despacho.getByText('MC-00155')).toBeInTheDocument()
    expect(despacho.getByText('A domicilio')).toBeInTheDocument()
    expect(despacho.getByText(`Listo para despacho 13/10 11:10 · ${ADMIN} · S/ 96.00`)).toBeInTheDocument()
    expect(despacho.getByText('Espera a que un distribuidor lo retire.')).toBeInTheDocument()
    // Ya no tiene acciones en el punto: ni recojo ni otra salida
    expect(despacho.queryByRole('button')).not.toBeInTheDocument()
    expect(within(columna('En preparación')).getByRole('heading', { name: 'En preparación (0)' })).toBeInTheDocument()
    // Solo se cambio ese pedido: la lista no se volvio a pedir
    expect(llamadas(fetchMock, LISTAR)).toHaveLength(1)
  })

  it('si otra persona ya registró la salida, explica el conflicto y vuelve a leer la lista', async () => {
    let lecturas = 0
    servidor(comoAcopio({
      [LISTAR]: () => [200, tablero(aDomicilio(++lecturas === 1 ? 'EN_PREPARACION' : 'LISTO_PARA_DESPACHO'))],
      [DESPACHO]: [409, { code: 'ESTADO_INVALIDO', error: 'Este pedido ya está listo para despacho.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: BOTON }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No pudimos registrar la salida del pedido')
    expect(alerta).toHaveTextContent('Este pedido ya está listo para despacho.')
    await waitFor(() => expect(within(columna('Listo para despacho')).getByText('MC-00155')).toBeInTheDocument())
    expect(lecturas).toBe(2)
  })

  it('un pedido para recojo rechazado por el servidor se explica con su mensaje', async () => {
    servidor(comoAcopio({ [DESPACHO]: [409, { code: 'PEDIDO_PARA_RECOJO' }] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: BOTON }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Este pedido se recoge en el punto, no se despacha a domicilio.')
  })

  it('mientras registra, el botón queda deshabilitado', async () => {
    let terminar
    servidor(comoAcopio({
      [DESPACHO]: () => new Promise((resolver) => { terminar = () => resolver([200, aDomicilio('LISTO_PARA_DESPACHO')]) }),
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: BOTON }))

    const boton = screen.getByRole('button', { name: BOTON })
    expect(boton).toBeDisabled()
    expect(boton).toHaveTextContent('Registrando salida…')
    terminar()
    expect(await screen.findByText('El pedido MC-00155 está listo para despacho')).toBeInTheDocument()
  })
})

describe('CW-09 Mis pedidos con un pedido listo para despacho (CCPIP-320)', () => {
  it('sigue en curso y dice que está listo para despacho', async () => {
    const pedido = {
      ...DATOS.misPedidos[1], id: 90, numero: 'CCP-000090', estado: 'LISTO_PARA_DESPACHO', modalidad: 'ENVIO',
      historial: [
        { estado: 'CONFIRMADO', registradoEn: '2026-10-23T15:01:02Z' },
        { estado: 'LISTO_PARA_DESPACHO', registradoEn: '2026-10-23T18:00:00Z' },
      ],
    }
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION],
      'GET /api/carrito': [200, copia(DATOS.carrito)],
      'GET /api/pedidos': [200, [pedido]],
    })
    abrirEn('/cuenta/pedidos')

    const tarjeta = within(await screen.findByRole('article', { name: 'Pedido CCP-000090' }))
    expect(tarjeta.getByText('Listo para despacho')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /En curso/, selected: true })).toBeInTheDocument()
  })
})
