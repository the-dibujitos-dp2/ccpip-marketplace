import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { llamadas, PUBLICAS, servidor } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const SESION_DISTRIBUIDOR = {
  user: { id: 8, email: 'distribuidor@ccpip.test', fullName: 'Julio Paredes', role: 'DISTRIBUIDOR', roleName: 'Distribuidor' },
  expiresInSeconds: 120,
}

const LIMA = { id: 1, nombre: 'Centro Nacional Lima', distrito: 'Lima', direccion: 'Sede central CCPIP' }
const CUSCO = { id: 3, nombre: 'Centro Regional Cusco', distrito: 'Cusco', direccion: 'Centro regional andino' }
const destino = (distrito, ubigeo, provincia = 'Lima', departamento = 'Lima') => ({ ubigeo, departamento, provincia, distrito })

const PEDIDOS = [
  { numero: 'MC-00139', origen: LIMA, destino: destino('San Isidro', '150131'), disponibleDesde: '2026-10-21T15:00:00Z', esperaMinutos: 3010 },
  { numero: 'MC-00151', origen: CUSCO, destino: destino('Wanchaq', '080108', 'Cusco', 'Cusco'), disponibleDesde: '2026-10-23T09:00:00Z', esperaMinutos: 365 },
  { numero: 'MC-00153', origen: LIMA, destino: destino('Miraflores', '150122'), disponibleDesde: '2026-10-23T14:15:00Z', esperaMinutos: 45 },
]

const DISPONIBLES = 'GET /api/distribuidor/pedidos/disponibles'
const asumir = (numero) => `POST /api/distribuidor/pedidos/${numero}/asumir`

/**
 * El servidor del reparto con estado: la cola lista los pedidos que siguen libres y asumir uno lo saca de ella.
 * {@code respuestas} fija lo que responde asumir un pedido en particular (p. ej. que otro se adelanto).
 */
function reparto(respuestas = {}) {
  const libres = PEDIDOS.map((p) => ({ ...p }))
  const quitar = (numero) => libres.splice(libres.findIndex((p) => p.numero === numero), 1)
  const rutas = {
    [DISPONIBLES]: (_opciones, url) => {
      const punto = new URL(url, 'http://servidor').searchParams.get('punto')
      const items = libres.filter((p) => !punto || p.origen.id === Number(punto))
      const porPunto = new Map()
      libres.forEach((p) => porPunto.set(p.origen.id, { id: p.origen.id, nombre: p.origen.nombre, total: (porPunto.get(p.origen.id)?.total ?? 0) + 1 }))
      return [200, { items, siguiente: null, total: items.length, puntos: [...porPunto.values()] }]
    },
  }
  PEDIDOS.forEach(({ numero, origen, destino: d }) => {
    rutas[asumir(numero)] = () => {
      const respuesta = respuestas[numero]
      if (respuesta instanceof Error) return respuesta
      if (respuesta) {
        // Otro distribuidor se lo llevo, o dejo de estar disponible: ya no esta en la cola
        quitar(numero)
        return respuesta
      }
      quitar(numero)
      return [200, { numero, origen, destino: d, asignadaEn: '2026-10-25T20:00:00Z' }]
    }
  })
  return rutas
}

const comoDistribuidor = (rutas) => ({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_DISTRIBUIDOR], ...rutas })
const tabla = () => screen.getByRole('table', { name: 'Pedidos disponibles' })
const casilla = (numero) => screen.getByRole('checkbox', { name: `Elegir el pedido ${numero}` })

describe('DW-01 Tomar pedidos disponibles (CCPIP-108)', () => {
  it('elige pedidos con sus casillas y los toma: uno por uno, y la cola se actualiza sin ellos', async () => {
    const fetchMock = servidor(comoDistribuidor(reparto()))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')
    await screen.findByText('MC-00139')
    // Sin elegidos no hay nada que tomar
    expect(screen.queryByRole('button', { name: /Tomar/ })).not.toBeInTheDocument()

    await usuario.click(casilla('MC-00139'))
    expect(screen.getByText('1 pedido seleccionado')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tomar 1 pedido' })).toBeEnabled()
    await usuario.click(casilla('MC-00153'))
    expect(screen.getByText('2 pedidos seleccionados')).toBeInTheDocument()

    await usuario.click(screen.getByRole('button', { name: 'Tomar 2 pedidos' }))

    expect(await screen.findByText('Tomaste 2 pedidos: MC-00139, MC-00153.')).toBeInTheDocument()
    expect(llamadas(fetchMock, asumir('MC-00139'))).toHaveLength(1)
    expect(llamadas(fetchMock, asumir('MC-00153'))).toHaveLength(1)
    expect(llamadas(fetchMock, asumir('MC-00151'))).toHaveLength(0)
    await waitFor(() => expect(within(tabla()).queryByText('MC-00139')).not.toBeInTheDocument())
    expect(within(tabla()).queryByText('MC-00153')).not.toBeInTheDocument()
    expect(within(tabla()).getByText('MC-00151')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Tomar/ })).not.toBeInTheDocument()
    expect(casilla('MC-00151')).not.toBeChecked()
  })

  it('si otro distribuidor se adelantó con uno, avisa cuál y los demás quedan tomados', async () => {
    servidor(comoDistribuidor(reparto({ 'MC-00151': [409, { code: 'PEDIDO_YA_ASUMIDO', error: 'Otro distribuidor ya asumió este pedido.' }] })))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')
    await screen.findByText('MC-00139')
    for (const numero of ['MC-00139', 'MC-00151', 'MC-00153']) await usuario.click(casilla(numero))

    await usuario.click(screen.getByRole('button', { name: 'Tomar 3 pedidos' }))

    expect(await screen.findByText('Tomaste 2 pedidos: MC-00139, MC-00153.')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Otro distribuidor ya tomó el pedido MC-00151.')
    // Ninguno de los tres sigue en la cola
    expect(await screen.findByText('No hay pedidos disponibles para repartir')).toBeInTheDocument()
  })

  it('un pedido que dejó de estar disponible se avisa aparte', async () => {
    servidor(comoDistribuidor(reparto({ 'MC-00153': [409, { code: 'PEDIDO_NO_DISPONIBLE', error: 'Este pedido no está disponible para asumir.' }] })))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')
    await screen.findByText('MC-00153')
    await usuario.click(casilla('MC-00153'))
    await usuario.click(screen.getByRole('button', { name: 'Tomar 1 pedido' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('El pedido MC-00153 ya no está disponible.')
    expect(screen.queryByText(/Tomaste/)).not.toBeInTheDocument()
  })

  it('si falla la red, lo dice y el pedido sigue elegido para reintentar', async () => {
    let intentos = 0
    const rutas = reparto()
    const asumirBien = rutas[asumir('MC-00139')]
    rutas[asumir('MC-00139')] = (...args) => (++intentos === 1 ? new Error('sin red') : asumirBien(...args))
    servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')
    await screen.findByText('MC-00139')
    await usuario.click(casilla('MC-00139'))
    await usuario.click(screen.getByRole('button', { name: 'Tomar 1 pedido' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos tomar el pedido MC-00139. Sigue elegido para que lo intentes otra vez.')
    await waitFor(() => expect(casilla('MC-00139')).toBeChecked())
    await usuario.click(screen.getByRole('button', { name: 'Tomar 1 pedido' }))
    expect(await screen.findByText('Tomaste el pedido MC-00139.')).toBeInTheDocument()
    expect(intentos).toBe(2)
  })

  it('mientras toma, el botón y las casillas quedan deshabilitados', async () => {
    let soltar
    const rutas = reparto()
    const asumirBien = rutas[asumir('MC-00139')]
    rutas[asumir('MC-00139')] = (...args) => new Promise((resolver) => { soltar = () => resolver(asumirBien(...args)) })
    servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')
    await screen.findByText('MC-00139')
    await usuario.click(casilla('MC-00139'))
    await usuario.click(screen.getByRole('button', { name: 'Tomar 1 pedido' }))

    expect(screen.getByRole('button', { name: 'Tomando…' })).toBeDisabled()
    expect(casilla('MC-00153')).toBeDisabled()
    soltar()
    expect(await screen.findByText('Tomaste el pedido MC-00139.')).toBeInTheDocument()
  })

  it('desmarcar quita el pedido de la selección y filtrar por otro punto suelta los que ya no se ven', async () => {
    servidor(comoDistribuidor(reparto()))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/pedidos')
    await screen.findByText('MC-00139')
    await usuario.click(casilla('MC-00139'))
    await usuario.click(casilla('MC-00151'))
    await usuario.click(casilla('MC-00139'))
    expect(screen.getByText('1 pedido seleccionado')).toBeInTheDocument()

    // MC-00151 es de Cusco: al ver solo Lima deja de estar elegido
    await usuario.click(screen.getByRole('button', { name: 'Centro Nacional Lima (2)' }))
    await waitFor(() => expect(within(tabla()).queryByText('MC-00151')).not.toBeInTheDocument())
    expect(screen.queryByText(/seleccionado/)).not.toBeInTheDocument()
  })
})
