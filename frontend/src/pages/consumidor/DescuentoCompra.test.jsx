import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import DescuentoCompra from './DescuentoCompra.jsx'
import { servidor } from '../../test/servidor.js'
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
describe('DescuentoCompra', () => {
 it('ignora Enter durante una previsualizacion pendiente', async () => {
  const pending = deferred()
  const fetchMock = servidor({ 'GET /api/puntos': [200, { cupones: [] }], 'POST /api/compra/descuento': () => pending.promise })
  const onAplicar = vi.fn()
  const user = userEvent.setup()
  render(<DescuentoCompra aplicado={null} onAplicar={onAplicar} onQuitar={vi.fn()} />)
  const input = await screen.findByLabelText('Código de descuento')
  await user.type(input, 'CACAO10')
  fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' })
  await waitFor(() => expect(fetchMock.mock.calls.filter(([, o]) => o.method === 'POST')).toHaveLength(1))
  fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' })
  expect(fetchMock.mock.calls.filter(([, o]) => o.method === 'POST')).toHaveLength(1)
  pending.resolve([200, { nombre: 'CACAO10', monto: 10 }])
  await waitFor(() => expect(onAplicar).toHaveBeenCalledTimes(1))
  expect(onAplicar).toHaveBeenCalledWith(expect.objectContaining({ solicitud: { codigo: 'CACAO10' } }))
 })
})
