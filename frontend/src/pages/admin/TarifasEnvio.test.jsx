import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const TARIFAS = '/api/admin/tarifas-envio'
const MIRAFLORES = {
  ubigeo: '150122', departamento: 'Lima', provincia: 'Lima', distrito: 'Miraflores', monto: 10, puntos: 2,
  actualizadoEn: '2026-11-05T19:30:00Z', actualizadoPor: 'Ana Shuñaqui',
}
const WANCHAQ = {
  ubigeo: '080108', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Wanchaq', monto: null, puntos: 1,
  actualizadoEn: null, actualizadoPor: null,
}

const comoSuper = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUPER],
  'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
  [`GET ${TARIFAS}`]: () => [200, [copia(WANCHAQ), copia(MIRAFLORES)]],
  ...rutas,
})

const lista = async () => within(await screen.findByRole('list', { name: 'Distritos con cobertura' }))
const fila = (l, distrito) => within(l.getByText(distrito).closest('li'))

describe('CCPIP-482 Tarifas de envío por distrito', () => {
  it('lista los distritos con cobertura con su tarifa, y avisa los que aún no la tienen', async () => {
    servidor(comoSuper())
    abrirEn('/admin/tarifas-envio')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tarifas de envío' })).toBeInTheDocument()
    const l = await lista()
    expect(fila(l, 'Miraflores').getByText(/S\/\s10\.00/)).toBeInTheDocument()
    expect(fila(l, 'Miraflores').getByText('Lima, Lima · 2 puntos lo cubren')).toBeInTheDocument()
    expect(fila(l, 'Miraflores').getByText(/Último cambio: .* · Ana Shuñaqui/)).toBeInTheDocument()
    expect(fila(l, 'Wanchaq').getByText('Sin tarifa')).toBeInTheDocument()
    expect(fila(l, 'Wanchaq').getByText('Cusco, Cusco · 1 punto lo cubre')).toBeInTheDocument()
    expect(screen.getByText('1 distrito con cobertura aún no tiene tarifa')).toBeInTheDocument()

    const nav = within(screen.getByRole('navigation', { name: 'Super administrador' }))
    expect(nav.getByRole('link', { name: 'Tarifas de envío' })).toHaveAttribute('aria-current', 'page')
  })

  it('fija la tarifa de un distrito con PUT y la muestra', async () => {
    const fetchMock = servidor(comoSuper({
      [`PUT ${TARIFAS}/080108`]: (o) => [200, { ...WANCHAQ, monto: JSON.parse(o.body).monto, actualizadoEn: '2026-11-06T15:00:00Z', actualizadoPor: 'Ana Shuñaqui' }],
    }))
    const user = userEvent.setup()
    abrirEn('/admin/tarifas-envio')
    await user.click((await lista()).getByRole('button', { name: 'Fijar la tarifa de Wanchaq' }))

    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Tarifa de envío a Wanchaq' })).toBeInTheDocument()
    await user.type(dialogo.getByLabelText('Tarifa (S/)'), '12,5')
    await user.click(dialogo.getByRole('button', { name: 'Guardar tarifa' }))

    await waitFor(() => expect(llamadas(fetchMock, `PUT ${TARIFAS}/080108`)).toHaveLength(1))
    expect(JSON.parse(llamadas(fetchMock, `PUT ${TARIFAS}/080108`)[0][1].body)).toEqual({ monto: 12.5 })
    expect(await screen.findByText(/^La tarifa de envío a Wanchaq es ahora S\/\s12\.50\.$/)).toBeInTheDocument()
    expect(fila(await lista(), 'Wanchaq').queryByText('Sin tarifa')).not.toBeInTheDocument()
    expect(screen.queryByText(/aún no tiene tarifa/)).not.toBeInTheDocument()
  })

  it('valida el monto sin enviar nada', async () => {
    const fetchMock = servidor(comoSuper())
    const user = userEvent.setup()
    abrirEn('/admin/tarifas-envio')
    await user.click((await lista()).getByRole('button', { name: 'Cambiar la tarifa de Miraflores' }))
    const dialogo = within(screen.getByRole('dialog'))
    const campo = dialogo.getByLabelText('Tarifa (S/)')
    expect(campo).toHaveValue('10.00')

    await user.clear(campo)
    await user.click(dialogo.getByRole('button', { name: 'Guardar tarifa' }))
    expect(dialogo.getByText('Indica la tarifa')).toBeInTheDocument()
    for (const malo of ['-1', '10.555', '1000', 'diez']) {
      await user.clear(campo)
      await user.type(campo, malo)
      await user.click(dialogo.getByRole('button', { name: 'Guardar tarifa' }))
      expect(dialogo.getByText('Escribe un monto de 0.00 a 999.99, con dos decimales como máximo')).toBeInTheDocument()
    }
    expect(llamadas(fetchMock, `PUT ${TARIFAS}/150122`)).toHaveLength(0)
  })

  it('si falla la carga lo avisa', async () => {
    servidor(comoSuper({ [`GET ${TARIFAS}`]: [500, { code: 'ERROR' }] }))
    abrirEn('/admin/tarifas-envio')
    expect(await screen.findByText('No pudimos cargar las tarifas de envío')).toBeInTheDocument()
  })
})
