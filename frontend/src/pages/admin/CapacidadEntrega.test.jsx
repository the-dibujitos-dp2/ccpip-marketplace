import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const CAPACIDADES = '/api/admin/capacidades-entrega'
const CUSCO = {
  puntoId: 3, punto: 'Centro Regional Cusco', departamento: 'Cusco', maximoDiario: 20, usadosHoy: 20,
  actualizadoEn: '2026-11-05T19:30:00Z', actualizadoPor: 'Ana Shuñaqui',
}
const LIMA = {
  puntoId: 1, punto: 'Centro Nacional Lima', departamento: 'Lima', maximoDiario: null, usadosHoy: 4,
  actualizadoEn: null, actualizadoPor: null,
}

const comoSuper = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUPER],
  'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
  [`GET ${CAPACIDADES}`]: () => [200, [copia(LIMA), copia(CUSCO)]],
  ...rutas,
})

const lista = async () => within(await screen.findByRole('list', { name: 'Puntos de acopio' }))
const fila = (l, punto) => within(l.getByText(punto).closest('li'))

describe('CCPIP-484 Capacidad diaria de entrega por punto', () => {
  it('lista los puntos con su capacidad, lo usado hoy y si ya se completó', async () => {
    servidor(comoSuper())
    abrirEn('/admin/capacidad-entrega')

    expect(await screen.findByRole('heading', { level: 1, name: 'Capacidad de entrega' })).toBeInTheDocument()
    const l = await lista()
    expect(fila(l, 'Centro Regional Cusco').getByText('Cusco · Hoy: 20 de 20')).toBeInTheDocument()
    expect(fila(l, 'Centro Regional Cusco').getByText('20 por día')).toBeInTheDocument()
    expect(fila(l, 'Centro Regional Cusco').getByText('Completa por hoy')).toBeInTheDocument()
    expect(fila(l, 'Centro Nacional Lima').getByText('Lima · Hoy: 4 pedidos a domicilio')).toBeInTheDocument()
    expect(fila(l, 'Centro Nacional Lima').getByText('Sin límite')).toBeInTheDocument()

    const nav = within(screen.getByRole('navigation', { name: 'Super administrador' }))
    expect(nav.getByRole('link', { name: 'Capacidad de entrega' })).toHaveAttribute('aria-current', 'page')
  })

  it('fija la capacidad con PUT y la muestra', async () => {
    const fetchMock = servidor(comoSuper({
      [`PUT ${CAPACIDADES}/1`]: (o) => [200, { ...LIMA, maximoDiario: JSON.parse(o.body).maximoDiario }],
    }))
    const user = userEvent.setup()
    abrirEn('/admin/capacidad-entrega')
    await user.click((await lista()).getByRole('button', { name: 'Cambiar la capacidad de Centro Nacional Lima' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.queryByRole('button', { name: 'Quitar el límite' })).not.toBeInTheDocument()
    await user.type(dialogo.getByLabelText('Pedidos a domicilio por día'), '15')
    await user.click(dialogo.getByRole('button', { name: 'Guardar capacidad' }))

    await waitFor(() => expect(llamadas(fetchMock, `PUT ${CAPACIDADES}/1`)).toHaveLength(1))
    expect(JSON.parse(llamadas(fetchMock, `PUT ${CAPACIDADES}/1`)[0][1].body)).toEqual({ maximoDiario: 15 })
    expect(await screen.findByText('Centro Nacional Lima puede despachar ahora 15 pedidos a domicilio por día.')).toBeInTheDocument()
    expect(fila(await lista(), 'Centro Nacional Lima').getByText('15 por día')).toBeInTheDocument()
  })

  it('quita el límite con DELETE', async () => {
    const fetchMock = servidor(comoSuper({
      [`DELETE ${CAPACIDADES}/3`]: [200, { ...CUSCO, maximoDiario: null }],
    }))
    const user = userEvent.setup()
    abrirEn('/admin/capacidad-entrega')
    await user.click((await lista()).getByRole('button', { name: 'Cambiar la capacidad de Centro Regional Cusco' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByLabelText('Pedidos a domicilio por día')).toHaveValue('20')
    await user.click(dialogo.getByRole('button', { name: 'Quitar el límite' }))

    await waitFor(() => expect(llamadas(fetchMock, `DELETE ${CAPACIDADES}/3`)).toHaveLength(1))
    expect(await screen.findByText('Centro Regional Cusco ya no tiene límite de pedidos a domicilio por día.')).toBeInTheDocument()
    expect(fila(await lista(), 'Centro Regional Cusco').queryByText('Completa por hoy')).not.toBeInTheDocument()
  })

  it('valida la capacidad sin enviar nada', async () => {
    const fetchMock = servidor(comoSuper())
    const user = userEvent.setup()
    abrirEn('/admin/capacidad-entrega')
    await user.click((await lista()).getByRole('button', { name: 'Cambiar la capacidad de Centro Nacional Lima' }))
    const dialogo = within(screen.getByRole('dialog'))
    const campo = dialogo.getByLabelText('Pedidos a domicilio por día')

    await user.click(dialogo.getByRole('button', { name: 'Guardar capacidad' }))
    expect(dialogo.getByText('Indica la capacidad diaria')).toBeInTheDocument()
    for (const malo of ['0', '2.5', '10001', 'diez']) {
      await user.clear(campo)
      await user.type(campo, malo)
      await user.click(dialogo.getByRole('button', { name: 'Guardar capacidad' }))
      expect(dialogo.getByText('Escribe un número entero de 1 a 10000')).toBeInTheDocument()
    }
    expect(llamadas(fetchMock, `PUT ${CAPACIDADES}/1`)).toHaveLength(0)
  })

  it('si falla la carga lo avisa', async () => {
    servidor(comoSuper({ [`GET ${CAPACIDADES}`]: [500, { code: 'ERROR' }] }))
    abrirEn('/admin/capacidad-entrega')
    expect(await screen.findByText('No pudimos cargar las capacidades de entrega')).toBeInTheDocument()
  })
})
