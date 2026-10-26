import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const DIRECCIONES = 'GET /api/cuenta/direcciones'

// Como las entrega el servidor: la ultima que se guardo primero
const GUARDADAS = [
  { id: 7, alias: null, ubigeo: '080108', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Wanchaq', direccion: 'Av. de la Cultura 1234', referencia: null },
  { id: 3, alias: 'Casa', ubigeo: '150122', departamento: 'Lima', provincia: 'Lima', distrito: 'Miraflores', direccion: 'Av. Larco 123, dpto. 4', referencia: 'Frente al parque Kennedy' },
]

const comoComprador = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  'GET /api/carrito': [200, copia(DATOS.carrito)],
  [DIRECCIONES]: [200, GUARDADAS],
  ...rutas,
})

describe('CW-13 Mis direcciones (CCPIP-393)', () => {
  it('lista sus direcciones guardadas, la última primero, con su distrito y su referencia', async () => {
    servidor(comoComprador())
    abrirEn('/cuenta/direcciones')

    expect(await screen.findByRole('heading', { level: 2, name: 'Mis direcciones' })).toBeInTheDocument()
    const tarjetas = within(await screen.findByRole('list', { name: 'Mis direcciones' })).getAllByRole('listitem')
    expect(tarjetas).toHaveLength(2)
    // Sin alias, un nombre generico
    expect(within(tarjetas[0]).getByText('Dirección de entrega')).toBeInTheDocument()
    expect(within(tarjetas[0]).getByText('Av. de la Cultura 1234')).toBeInTheDocument()
    expect(within(tarjetas[0]).getByText('Wanchaq · Cusco · Cusco')).toBeInTheDocument()
    expect(within(tarjetas[0]).queryByText(/Referencia/)).not.toBeInTheDocument()
    expect(within(tarjetas[1]).getByText('Casa')).toBeInTheDocument()
    expect(within(tarjetas[1]).getByText('Av. Larco 123, dpto. 4')).toBeInTheDocument()
    expect(within(tarjetas[1]).getByText('Miraflores · Lima · Lima')).toBeInTheDocument()
    expect(within(tarjetas[1]).getByText('Referencia: Frente al parque Kennedy')).toBeInTheDocument()
  })

  it('sin direcciones explica cómo se guardan', async () => {
    servidor(comoComprador({ [DIRECCIONES]: [200, []] }))
    abrirEn('/cuenta/direcciones')

    const vacio = within((await screen.findByText('Aún no guardaste direcciones')).closest('[role="status"]'))
    expect(vacio.getByText('Al comprar con entrega a domicilio puedes guardar la dirección para la próxima vez.')).toBeInTheDocument()
    expect(vacio.getByRole('link', { name: 'Explorar productos' })).toHaveAttribute('href', '/buscar')
  })

  it('si falla la carga lo dice y deja reintentar', async () => {
    let vez = 0
    const fetchMock = servidor(comoComprador({ [DIRECCIONES]: () => (++vez === 1 ? [500, { code: 'ERROR', error: 'Falla' }] : [200, GUARDADAS]) }))
    const usuario = userEvent.setup()
    abrirEn('/cuenta/direcciones')

    expect(await screen.findByText('No pudimos cargar tus direcciones')).toBeInTheDocument()
    await usuario.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Av. Larco 123, dpto. 4')).toBeInTheDocument()
    expect(llamadas(fetchMock, DIRECCIONES)).toHaveLength(2)
  })

  it('desde el menú de Mi cuenta se llega a Mis direcciones', async () => {
    servidor(comoComprador({ 'GET /api/cuenta/datos': [200, { nombres: 'Consumidor', apellidos: 'de Prueba', correo: 'consumidor@ccpip.test', correoConfirmado: true, celular: null }] }))
    const usuario = userEvent.setup()
    abrirEn('/cuenta/datos')

    await usuario.click(await screen.findByRole('link', { name: 'Mis direcciones' }))
    await waitFor(() => expect(window.location.pathname).toBe('/cuenta/direcciones'))
    expect(await screen.findByText('Av. Larco 123, dpto. 4')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mis direcciones' })).toHaveAttribute('aria-current', 'page')
  })

  it('solo entra el comprador; sin sesión pide ingresar', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_PRODUCTOR],
      'GET /api/panel/productor': [200, copia(DATOS.panelProductor)],
    })
    const otro = abrirEn('/cuenta/direcciones')
    await waitFor(() => expect(window.location.pathname).not.toBe('/cuenta/direcciones'))
    expect(llamadas(fetchMock, DIRECCIONES)).toHaveLength(0)
    otro.unmount()

    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/cuenta/direcciones')
    await waitFor(() => expect(window.location.pathname).toBe('/ingresar'))
  })
})
