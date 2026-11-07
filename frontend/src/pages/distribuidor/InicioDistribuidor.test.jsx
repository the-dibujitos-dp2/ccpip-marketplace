import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { PUBLICAS, servidor } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const SESION_DISTRIBUIDOR = {
  user: { id: 8, email: 'distribuidor@ccpip.test', fullName: 'Julio Paredes', role: 'DISTRIBUIDOR', roleName: 'Distribuidor' },
  expiresInSeconds: 120,
}
const LIMA = { id: 1, nombre: 'Centro Nacional Lima', distrito: 'Lima', direccion: 'Sede central CCPIP' }
const entrega = (numero, distrito, estado) => ({
  numero, origen: LIMA, destino: { ubigeo: '150101', departamento: 'Lima', provincia: 'Lima', distrito },
  estado, asignadaEn: '2026-11-06T15:00:00Z', retiradaEn: null,
})
const DISPONIBLES = (total) => [200, { items: [], siguiente: null, total, puntos: [] }]
const comoDistribuidor = (rutas) => ({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_DISTRIBUIDOR], ...rutas })
const kpi = (nombre) => within(screen.getByRole('region', { name: nombre }))

describe('CCPIP-500 Inicio del distribuidor', () => {
  it('al entrar ve el resumen de su reparto y cada cifra lleva a su pantalla', async () => {
    servidor(comoDistribuidor({
      'GET /api/distribuidor/pedidos/disponibles': DISPONIBLES(5),
      'GET /api/distribuidor/pedidos': [200, [
        entrega('CCP-000011', 'Miraflores', 'ASIGNADA'),
        entrega('CCP-000012', 'Surco', 'EN_CAMINO'),
        entrega('CCP-000013', 'Lince', 'RETIRADA'),
        entrega('CCP-000009', 'Barranco', 'ENTREGADA'),
      ]],
    }))
    // Como al iniciar sesion: el inicio generico lo manda a su inicio
    abrirEn('/inicio')

    expect(await screen.findByRole('region', { name: 'Pedidos para tomar' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Inicio' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/distribuidor')
    expect(kpi('Pedidos para tomar').getByText('5')).toBeInTheDocument()
    expect(kpi('Por retirar').getByText('1')).toBeInTheDocument()
    expect(kpi('En camino').getByText('2')).toBeInTheDocument()
    expect(kpi('Entregados hoy').getByText('1')).toBeInTheDocument()
    expect(kpi('Pedidos para tomar').getByRole('link', { name: 'Ver pedidos disponibles: Pedidos para tomar' }))
      .toHaveAttribute('href', '/distribuidor/pedidos')
    expect(kpi('Por retirar').getByRole('link')).toHaveAttribute('href', '/distribuidor/entregas')
    expect(kpi('Entregados hoy').queryByRole('link')).not.toBeInTheDocument()

    const siguiente = within(screen.getByRole('region', { name: 'Tu siguiente entrega' }))
    expect(siguiente.getByText('CCP-000011')).toBeInTheDocument()
    expect(siguiente.getByText('De Centro Nacional Lima a Miraflores, Lima')).toBeInTheDocument()
    expect(siguiente.getByText(/^Por retirar del punto · tomado el/)).toBeInTheDocument()

    const menu = within(screen.getByRole('navigation', { name: 'Distribuidor' }))
    expect(menu.getByRole('link', { name: 'Inicio' })).toHaveAttribute('aria-current', 'page')
  })

  it('sin entregas en curso invita a tomar pedidos', async () => {
    servidor(comoDistribuidor({
      'GET /api/distribuidor/pedidos/disponibles': DISPONIBLES(2),
      'GET /api/distribuidor/pedidos': [200, []],
    }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor')
    expect(await screen.findByText('No tienes entregas en curso.')).toBeInTheDocument()
    await usuario.click(screen.getByRole('link', { name: 'Tomar pedidos' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Pedidos disponibles' })).toBeInTheDocument()
  })

  it('si no carga, lo dice y deja reintentar', async () => {
    let intentos = 0
    servidor(comoDistribuidor({
      'GET /api/distribuidor/pedidos/disponibles': DISPONIBLES(0),
      'GET /api/distribuidor/pedidos': () => (intentos++ === 0 ? [500, { error: 'falló' }] : [200, []]),
    }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor')
    expect(await screen.findByText('No pudimos cargar tu reparto')).toBeInTheDocument()
    await usuario.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('No tienes entregas en curso.')).toBeInTheDocument()
  })
})
