import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { abrirPestana, copia, DATOS, llamadas, PUBLICAS, servidor, SESION_ACOPIO } from '../../test/servidor.js'

const LISTAR = 'GET /api/acopio/pedidos'
const PREPARACION = 'POST /api/acopio/pedidos/MC-00155/preparacion'
const LISTO = 'POST /api/acopio/pedidos/MC-00155/listo'
const DESPACHO = 'POST /api/acopio/pedidos/MC-00155/despacho'
const BOTON_PREPARAR = 'Registrar la preparación del pedido MC-00155'

/** El tablero con el primer pedido en ese estado y modalidad. */
function tablero(estado, modalidad = 'RECOJO') {
  const datos = copia(DATOS.pedidos)
  datos.items[0].estado = estado
  datos.items[0].modalidad = modalidad
  return datos
}

const comoAcopio = (rutas = {}) => ({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_ACOPIO], [LISTAR]: () => [200, copia(DATOS.pedidos)], ...rutas })
const abrir = (ruta = '/acopio/pedidos') => { window.history.replaceState(null, '', ruta); render(<App />) }
const mutaciones = (fetch) => fetch.mock.calls.filter(([, o]) => o?.method && o.method !== 'GET')

afterEach(async () => { await i18n.changeLanguage('es') })

describe('CCPIP-497: el administrador del punto confirma cada cambio de estado', () => {
  it('registrar "en preparación" abre un diálogo con lo que va a pasar; Cancelar no cambia nada', async () => {
    const fetch = servidor(comoAcopio({ [PREPARACION]: [200, copia(DATOS.pedidoListo)] }))
    const user = userEvent.setup(); abrir()
    await user.click(await screen.findByRole('button', { name: BOTON_PREPARAR }))
    const dialogo = await screen.findByRole('dialog', { name: '¿Registrar el pedido MC-00155 en preparación?' })
    expect(dialogo).toHaveTextContent('El pedido pasa a «En preparación» con tu nombre y la hora')
    expect(within(dialogo).getByRole('button', { name: 'Confirmar' })).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('button', { name: 'Cancelar' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(mutaciones(fetch)).toHaveLength(0)
    // El pedido sigue en Confirmado y se puede volver a intentar
    expect(screen.getByRole('tabpanel', { name: 'Confirmado' })).toHaveTextContent('MC-00155')
    expect(screen.getByRole('button', { name: BOTON_PREPARAR })).toBeEnabled()
  })

  it('marcar listo para recojo explica que se avisa al comprador y solo registra tras confirmar', async () => {
    const fetch = servidor(comoAcopio({ [LISTAR]: () => [200, tablero('EN_PREPARACION')], [LISTO]: [200, copia(DATOS.pedidoListo)] }))
    const user = userEvent.setup(); abrir()
    await user.click((await abrirPestana(user, 'En preparación')).getByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' }))
    const dialogo = await screen.findByRole('dialog', { name: '¿Marcar el pedido MC-00155 como listo para recojo?' })
    expect(dialogo).toHaveTextContent('le avisamos por correo al comprador')
    expect(llamadas(fetch, LISTO)).toHaveLength(0)
    await user.click(within(dialogo).getByRole('button', { name: 'Confirmar' }))
    expect(await screen.findByText('El pedido MC-00155 está listo para recojo')).toBeInTheDocument()
    expect(llamadas(fetch, LISTO)).toHaveLength(1)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('registrar la salida a domicilio dice que un distribuidor lo retirará; Cancelar no la registra', async () => {
    const fetch = servidor(comoAcopio({ [LISTAR]: () => [200, tablero('EN_PREPARACION', 'ENVIO')] }))
    const user = userEvent.setup(); abrir()
    await user.click((await abrirPestana(user, 'En preparación')).getByRole('button', { name: 'Registrar la salida del pedido MC-00155 para despacho' }))
    const dialogo = await screen.findByRole('dialog', { name: '¿Registrar la salida del pedido MC-00155?' })
    expect(dialogo).toHaveTextContent('un distribuidor podrá retirarlo')
    await user.click(within(dialogo).getByRole('button', { name: 'Cancelar' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(llamadas(fetch, DESPACHO)).toHaveLength(0)
  })

  it('mientras registra no deja cancelar ni confirmar dos veces; si falla cierra y explica el error', async () => {
    let terminar
    const fetch = servidor(comoAcopio({
      [PREPARACION]: () => new Promise((resolver) => { terminar = () => resolver([500, {}]) }),
    }))
    const user = userEvent.setup(); abrir()
    await user.click(await screen.findByRole('button', { name: BOTON_PREPARAR }))
    const dialogo = await screen.findByRole('dialog')
    await user.click(within(dialogo).getByRole('button', { name: 'Confirmar' }))
    expect(within(dialogo).getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    expect(within(dialogo).getByRole('button', { name: 'Confirmar' })).toBeDisabled()
    expect(llamadas(fetch, PREPARACION)).toHaveLength(1)
    terminar()
    expect(await screen.findByText('No pudimos registrar la preparación')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('en inglés el diálogo usa Confirm y Cancel', async () => {
    await i18n.changeLanguage('en')
    servidor(comoAcopio())
    const user = userEvent.setup(); abrir()
    await user.click(await screen.findByRole('button', { name: /order MC-00155/ }))
    const dialogo = await screen.findByRole('dialog', { name: 'Mark order MC-00155 as being prepared?' })
    expect(within(dialogo).getByRole('button', { name: 'Confirm' })).toBeInTheDocument()
    expect(within(dialogo).getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })
})

describe('CCPIP-497: desactivar una zona de cobertura se confirma', () => {
  const cobertura = [{ puntoId: 7, punto: 'Punto Centro', capacidad: null,
    zonas: [{ id: 2, ubigeo: '150101', departamento: 'Lima', provincia: 'Lima', distrito: 'Lima', activa: true, actualizadoEn: null }] }]

  it('el diálogo dice que deja de ofrecerse a domicilio y Cancelar no la desactiva', async () => {
    const fetch = servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_ACOPIO], 'GET /api/acopio/coberturas': [200, cobertura] })
    const user = userEvent.setup(); abrir('/acopio/cobertura')
    await user.click(await screen.findByRole('button', { name: 'Desactivar zona · Lima · 150101' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Desactivar zona de cobertura' })
    expect(dialogo).toHaveTextContent('dejará de ofrecerse para entregas a domicilio')
    expect(within(dialogo).getByRole('button', { name: 'Confirmar' })).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('button', { name: 'Cancelar' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(mutaciones(fetch)).toHaveLength(0)
    expect(screen.getByText('Activa')).toBeInTheDocument()
  })
})
