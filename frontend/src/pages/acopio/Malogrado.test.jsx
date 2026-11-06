import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, llamadas, panel, PUBLICAS, servidor, SESION_ACOPIO } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const CAUSAS = [
  { id: 2, nombre: 'Daño', nombreEn: 'Damage', requiereDetalle: false },
  { id: 6, nombre: 'Otra', nombreEn: 'Other', requiereDetalle: true },
]

const LINEA = {
  id: 41, producto: 'Aguaymanto deshidratado', comunidad: 'Asoc. Nii Joi', cantidad: 2, porEntregar: 2, incidencias: [],
  lotes: [{ id: 7, codigo: 'L-0007', cantidad: 2, fechaVencimiento: '2027-10-13' }],
}
const PEDIDO = {
  numero: 'MC-00155', estado: 'CONFIRMADO', modalidad: 'RECOJO', confirmadoEn: '2026-11-06T11:08:05Z',
  actualizadoEn: '2026-11-06T11:08:05Z', total: 40, lineas: [LINEA], historial: [{ estado: 'CONFIRMADO', registradoEn: '2026-11-06T11:08:05Z' }],
}
const OPCIONES = (reemplazos) => ({
  itemId: 41, producto: LINEA.producto, cantidad: 2, porEntregar: 2, precioUnitario: 20, lotes: LINEA.lotes, reemplazos,
})
const L_0009 = { id: 9, codigo: 'L-0009', disponible: 4, fechaVencimiento: '2027-12-13' }

const OPCIONES_RUTA = 'GET /api/acopio/pedidos/MC-00155/lineas/41/malogrado'
const REGISTRAR = 'POST /api/acopio/pedidos/MC-00155/lineas/41/malogrado'

const comoAcopio = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_ACOPIO],
  'GET /api/acopio/pedidos': () => [200, { ubicacion: 'Centro Nacional Lima', items: [copia(PEDIDO)] }],
  'GET /api/merma/causas': [200, CAUSAS],
  ...rutas,
})

async function abrirDialogo(user) {
  abrirEn('/acopio/pedidos')
  const confirmado = await panel('Confirmado')
  await user.click(confirmado.getByRole('button', { name: 'Registrar Aguaymanto deshidratado malogrado del pedido MC-00155' }))
  const dialogo = within(await screen.findByRole('dialog'))
  await dialogo.findByLabelText('Lote malogrado')
  return dialogo
}

describe('CCPIP-485 Reemplazar con otro lote un producto malogrado', () => {
  it('propone reemplazar con el lote vigente del punto y actualiza el pedido', async () => {
    const reemplazado = {
      ...copia(PEDIDO),
      lineas: [{
        ...LINEA,
        lotes: [{ id: 7, codigo: 'L-0007', cantidad: 1, fechaVencimiento: '2027-10-13' }, { id: 9, codigo: 'L-0009', cantidad: 1, fechaVencimiento: '2027-12-13' }],
        incidencias: [{ tipo: 'REEMPLAZO', cantidad: 1, loteOriginal: 'L-0007', loteNuevo: 'L-0009', causa: 'Daño', registradaEn: '2026-11-06T12:00:00Z' }],
      }],
    }
    const fetchMock = servidor(comoAcopio({
      [OPCIONES_RUTA]: [200, OPCIONES([L_0009])],
      [REGISTRAR]: [200, { tipo: 'REEMPLAZO', cantidad: 1, reembolsado: 0, pedidoAnulado: false, repetida: false, pedido: reemplazado }],
    }))
    const user = userEvent.setup()
    const dialogo = await abrirDialogo(user)

    // Un solo lote en la linea: ya viene elegido; primero se intenta reemplazar
    expect(dialogo.getByLabelText('Lote malogrado')).toHaveValue('7')
    expect(dialogo.getByLabelText('Cómo se resuelve')).toHaveValue('REEMPLAZAR')
    expect(dialogo.getByLabelText('Lote con que se reemplaza')).toHaveValue('9')
    expect(dialogo.getByText('El lote nuevo cubre lo malogrado; el precio del pedido no cambia.')).toBeInTheDocument()

    await user.type(dialogo.getByLabelText('Cantidad malograda (und)'), '1')
    await user.selectOptions(dialogo.getByLabelText('Causa (obligatoria)'), '2')
    await user.click(dialogo.getByRole('button', { name: 'Registrar y reemplazar' }))

    await waitFor(() => expect(llamadas(fetchMock, REGISTRAR)).toHaveLength(1))
    const cuerpo = JSON.parse(llamadas(fetchMock, REGISTRAR)[0][1].body)
    expect(cuerpo).toMatchObject({ loteId: 7, cantidad: '1', motivoId: 2, accion: 'REEMPLAZAR', loteReemplazoId: 9, detalle: null })
    expect(cuerpo.clave).toMatch(/^[0-9a-f-]{36}$/)
    expect(await screen.findByText('Se reemplazó lo malogrado del pedido MC-00155')).toBeInTheDocument()
    const lotes = within(screen.getByRole('list', { name: 'Productos malogrados de Aguaymanto deshidratado' }))
    expect(lotes.getByText('Malogrado: 1 und (Daño) · reemplazado con el lote L-0009')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('valida antes de enviar y muestra el rechazo del servidor junto al campo', async () => {
    const fetchMock = servidor(comoAcopio({
      [OPCIONES_RUTA]: [200, OPCIONES([L_0009])],
      [REGISTRAR]: [409, { code: 'SALDO_INSUFICIENTE', error: 'Ese lote tiene 4: no alcanza para el reemplazo.' }],
    }))
    const user = userEvent.setup()
    const dialogo = await abrirDialogo(user)

    await user.click(dialogo.getByRole('button', { name: 'Registrar y reemplazar' }))
    expect(dialogo.getByText('Elige la causa.')).toBeInTheDocument()
    await user.type(dialogo.getByLabelText('Cantidad malograda (und)'), '3')
    await user.selectOptions(dialogo.getByLabelText('Causa (obligatoria)'), '6')
    await user.click(dialogo.getByRole('button', { name: 'Registrar y reemplazar' }))
    expect(dialogo.getByText('De ese lote la línea tiene 2 und')).toBeInTheDocument()
    expect(dialogo.getByText('Describe la causa de la merma.')).toBeInTheDocument()
    expect(llamadas(fetchMock, REGISTRAR)).toHaveLength(0)

    await user.clear(dialogo.getByLabelText('Cantidad malograda (und)'))
    await user.type(dialogo.getByLabelText('Cantidad malograda (und)'), '2')
    await user.type(dialogo.getByLabelText('Describe la causa'), 'Se mojó la caja')
    await user.click(dialogo.getByRole('button', { name: 'Registrar y reemplazar' }))
    expect(await dialogo.findByText('Ese lote tiene 4: no alcanza para el reemplazo.')).toBeInTheDocument()
  })
})

describe('CCPIP-486 Entregar lo disponible y reembolsar lo faltante', () => {
  it('sin otro lote solo ofrece el reembolso, con su monto, y avisa el resultado', async () => {
    const reembolsado = {
      ...copia(PEDIDO),
      lineas: [{
        ...LINEA, porEntregar: 1, lotes: [{ id: 7, codigo: 'L-0007', cantidad: 1, fechaVencimiento: '2027-10-13' }],
        incidencias: [{ tipo: 'FALTANTE', cantidad: 1, loteOriginal: 'L-0007', causa: 'Daño', reembolsado: 20, registradaEn: '2026-11-06T12:00:00Z' }],
      }],
    }
    const fetchMock = servidor(comoAcopio({
      [OPCIONES_RUTA]: [200, OPCIONES([])],
      [REGISTRAR]: [200, { tipo: 'FALTANTE', cantidad: 1, reembolsado: 20, pedidoAnulado: false, repetida: false, pedido: reembolsado }],
    }))
    const user = userEvent.setup()
    const dialogo = await abrirDialogo(user)

    expect(dialogo.getByLabelText('Cómo se resuelve')).toHaveValue('REEMBOLSAR')
    expect(within(dialogo.getByLabelText('Cómo se resuelve')).getAllByRole('option').map((o) => o.value).filter(Boolean)).toEqual(['REEMBOLSAR'])
    expect(dialogo.getByText('No hay otro lote vigente de este producto en el punto: solo puede reembolsarse.')).toBeInTheDocument()
    expect(dialogo.queryByLabelText('Lote con que se reemplaza')).not.toBeInTheDocument()

    await user.type(dialogo.getByLabelText('Cantidad malograda (und)'), '1')
    expect(dialogo.getByText(/Se devolverá S\/\s20\.00 al comprador/)).toBeInTheDocument()
    await user.selectOptions(dialogo.getByLabelText('Causa (obligatoria)'), '2')
    await user.click(dialogo.getByRole('button', { name: 'Registrar y reembolsar' }))

    await waitFor(() => expect(llamadas(fetchMock, REGISTRAR)).toHaveLength(1))
    expect(JSON.parse(llamadas(fetchMock, REGISTRAR)[0][1].body)).toMatchObject({ accion: 'REEMBOLSAR', loteReemplazoId: null })
    expect(await screen.findByText(/^Se reembolsó S\/\s20\.00 del pedido MC-00155$/)).toBeInTheDocument()
    expect(screen.getByText('Por entregar: 1 und')).toBeInTheDocument()
    expect(screen.getByText(/no se entrega, S\/\s20\.00 reembolsado/)).toBeInTheDocument()
  })

  it('si el pedido ya cambió (entregado o en camino) lo explica y recarga', async () => {
    const fetchMock = servidor(comoAcopio({
      [OPCIONES_RUTA]: [200, OPCIONES([])],
      [REGISTRAR]: [409, { code: 'ESTADO_INVALIDO', error: 'Este pedido ya fue entregado.' }],
    }))
    const user = userEvent.setup()
    const dialogo = await abrirDialogo(user)
    const antes = llamadas(fetchMock, 'GET /api/acopio/pedidos').length
    await user.type(dialogo.getByLabelText('Cantidad malograda (und)'), '1')
    await user.selectOptions(dialogo.getByLabelText('Causa (obligatoria)'), '2')
    await user.click(dialogo.getByRole('button', { name: 'Registrar y reembolsar' }))
    expect(await screen.findByText('No pudimos registrar el producto malogrado')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/acopio/pedidos').length).toBeGreaterThan(antes))
  })

  it('un pedido listo para recojo también lo admite; uno entregado no ofrece la acción', async () => {
    const entregado = { ...copia(PEDIDO), numero: 'MC-00131', estado: 'ENTREGADO', historial: [{ estado: 'ENTREGADO', registradoEn: '2026-11-06T13:00:00Z' }] }
    servidor(comoAcopio({ 'GET /api/acopio/pedidos': () => [200, { ubicacion: 'Centro Nacional Lima', items: [entregado] }] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await panel('Confirmado')
    await user.click(screen.getByRole('tab', { name: /Entregado hoy/ }))
    expect(screen.queryByRole('button', { name: /malogrado del pedido/ })).not.toBeInTheDocument()
  })
})
