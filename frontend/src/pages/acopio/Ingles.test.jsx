import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, PUBLICAS, servidor, SESION_ACOPIO } from '../../test/servidor.js'

// CCPIP-98: el panel del punto de acopio en ingles, sin restos en espanol
function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const comoAcopio = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_ACOPIO],
  'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)],
  'GET /api/acopio/movimientos': () => [200, copia(DATOS.movimientos)],
  ...rutas,
})

describe('Punto de acopio en inglés (CCPIP-98)', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('los pedidos salen por columna con sus acciones en inglés', async () => {
    servidor(comoAcopio())
    abrirEn('/acopio/pedidos')
    expect(await screen.findByRole('heading', { level: 1, name: 'Orders' })).toBeInTheDocument()
    const confirmados = within(await screen.findByRole('region', { name: 'Confirmed' }))
    expect(confirmados.getByRole('button', { name: /^Record the preparation of order .+$/ })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Being prepared' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Ready for pickup' })).toBeInTheDocument()
    expect(screen.queryByText(/Listo para recojo/)).not.toBeInTheDocument()
  })

  it('el registro del recojo explica el rechazo del servidor en inglés', async () => {
    const listo = copia(DATOS.pedidos).items.find((p) => p.estado === 'LISTO_PARA_RECOJO')
    servidor(
      comoAcopio({
        [`POST /api/acopio/pedidos/${listo.numero}/recojo`]: [422, { code: 'CELULAR_NO_COINCIDE', error: 'El celular no coincide con el registrado en el pedido.' }],
      }),
    )
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: `Record pickup for order ${listo.numero}` }))
    await user.type(screen.getByLabelText('Name of the person picking up'), 'Ana Quispe')
    await user.type(screen.getByLabelText('Contact mobile number'), '987654321')
    await user.click(screen.getByRole('button', { name: 'Record pickup' }))
    expect(await screen.findByText("The mobile number doesn't match the one on the order.")).toBeInTheDocument()
  })

  it('los movimientos muestran pestañas, columnas y tipos en inglés', async () => {
    servidor(comoAcopio())
    abrirEn('/acopio/movimientos')
    expect(await screen.findByRole('heading', { level: 1, name: 'Stock movements' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Incoming' })).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Point movements' })).toBeInTheDocument()
  })
})
