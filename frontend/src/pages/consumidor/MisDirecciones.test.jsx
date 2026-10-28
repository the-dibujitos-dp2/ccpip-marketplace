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
const RETIRAR = 'DELETE /api/cuenta/direcciones/'

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

  it('permite editar una dirección guardada y refresca los datos', async () => {
    let lista = copia(GUARDADAS)
    const fetchMock = servidor(comoComprador({
      [DIRECCIONES]: () => [200, lista],
      'GET /api/territorio/departamentos': [200, ['Lima', 'Cusco']],
      'GET /api/territorio/provincias': [200, ['Lima']],
      'GET /api/territorio/distritos': [200, [{ ubigeo: '150122', departamento: 'Lima', provincia: 'Lima', distrito: 'Miraflores' }]],
      'PUT /api/cuenta/direcciones/3': (op) => { const body = JSON.parse(op.body); lista = lista.map(d => d.id === 3 ? { ...d, ...body, distrito: 'Miraflores', provincia: 'Lima', departamento: 'Lima' } : d); return [200, lista[1]] },
    }))
    const usuario = userEvent.setup()
    abrirEn('/cuenta/direcciones')
    const tarjeta = within(await screen.findByRole('list', { name: 'Mis direcciones' })).getByText('Casa').closest('li')
    await usuario.click(within(tarjeta).getByRole('button', { name: 'Editar dirección' }))
    const formulario = await screen.findByRole('form', { name: 'Editar dirección' })
    expect(within(formulario).getByLabelText('Dirección')).toHaveValue('Av. Larco 123, dpto. 4')
    await usuario.clear(within(formulario).getByLabelText('Dirección'))
    await usuario.type(within(formulario).getByLabelText('Dirección'), 'Calle nueva 10')
    await usuario.click(within(formulario).getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByText('Calle nueva 10')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'PUT /api/cuenta/direcciones/3')).toHaveLength(1)
    expect(llamadas(fetchMock, DIRECCIONES)).toHaveLength(2)
  })

  it('edita con el catalogo territorial y reinicia descendientes al cambiar departamento', async () => {
    servidor(comoComprador({
      [DIRECCIONES]: [200, copia(GUARDADAS)],
      'GET /api/territorio/departamentos': [200, ['Lima', 'Cusco']],
      'GET /api/territorio/provincias': (_, url) => [200, [new URL(url, 'http://qa.local').searchParams.get('departamento')]],
      'GET /api/territorio/distritos': (_, url) => [200, new URL(url, 'http://qa.local').searchParams.get('departamento') === 'Lima'
        ? [{ ubigeo: '150122', departamento: 'Lima', provincia: 'Lima', distrito: 'Miraflores' }]
        : [{ ubigeo: '080108', departamento: 'Cusco', provincia: 'Cusco', distrito: 'Wanchaq' }]],
    }))
    const usuario = userEvent.setup()
    abrirEn('/cuenta/direcciones')
    const tarjeta = (await screen.findByText('Casa')).closest('li')
    await usuario.click(within(tarjeta).getByRole('button', { name: 'Editar dirección' }))
    const formulario = within(await screen.findByRole('form', { name: 'Editar dirección' }))
    const departamento = await formulario.findByRole('combobox', { name: 'Departamento' })
    await waitFor(() => expect(departamento).toHaveValue('Lima'))
    await usuario.selectOptions(departamento, 'Cusco')
    expect(formulario.getByRole('combobox', { name: 'Provincia' })).toHaveValue('')
    expect(formulario.getByRole('combobox', { name: 'Distrito de entrega' })).toHaveValue('')
    expect(formulario.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled()
  })

  it('permite confirmar el retiro de una dirección propia y recarga la lista sin afectar las demás', async () => {
    let lista = [...GUARDADAS]
    const fetchMock = servidor(comoComprador({
      [DIRECCIONES]: () => [200, lista],
      [`${RETIRAR}7`]: () => { lista = lista.filter((d) => d.id !== 7); return [204, null] },
    }))
    const usuario = userEvent.setup()
    abrirEn('/cuenta/direcciones')

    const tarjeta = within(await screen.findByRole('list', { name: 'Mis direcciones' })).getByText('Av. de la Cultura 1234').closest('li')
    await usuario.click(within(tarjeta).getByRole('button', { name: 'Retirar dirección de entrega' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Retirar dirección' })
    expect(within(dialogo).getByText(/¿Quieres retirar Av\. de la Cultura 1234/)).toBeInTheDocument()
    await usuario.click(within(dialogo).getByRole('button', { name: 'Retirar dirección' }))

    expect(await screen.findByText('Dirección retirada')).toBeInTheDocument()
    expect(screen.queryByText('Av. de la Cultura 1234')).not.toBeInTheDocument()
    expect(screen.getByText('Av. Larco 123, dpto. 4')).toBeInTheDocument()
    expect(llamadas(fetchMock, `${RETIRAR}7`)).toHaveLength(1)
    expect(llamadas(fetchMock, DIRECCIONES)).toHaveLength(2)
  })

  it('deshabilita el retiro mientras espera y no permite doble envío', async () => {
    let resolver
    const fetchMock = servidor(comoComprador({
      [`${RETIRAR}7`]: () => new Promise((resolve) => { resolver = resolve }),
    }))
    const usuario = userEvent.setup()
    abrirEn('/cuenta/direcciones')
    const tarjeta = within(await screen.findByRole('list', { name: 'Mis direcciones' })).getByText('Av. de la Cultura 1234').closest('li')
    await usuario.click(within(tarjeta).getByRole('button', { name: 'Retirar dirección de entrega' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Retirar dirección' })
    const retirar = within(dialogo).getByRole('button', { name: 'Retirar dirección' })
    await usuario.click(retirar)
    await waitFor(() => expect(retirar).toBeDisabled())
    expect(llamadas(fetchMock, `${RETIRAR}7`)).toHaveLength(1)
    resolver([204, null])
    expect(await screen.findByText('Dirección retirada')).toBeInTheDocument()
  })

  it('si falla el retiro conserva la tarjeta y permite reintentar', async () => {
    let intentos = 0
    let lista = [...GUARDADAS]
    const fetchMock = servidor(comoComprador({
      [DIRECCIONES]: () => [200, lista],
      [`${RETIRAR}7`]: () => {
        intentos += 1
        if (intentos === 1) return [500, { code: 'ERROR', error: 'Falla' }]
        lista = lista.filter((d) => d.id !== 7)
        return [204, null]
      },
    }))
    const usuario = userEvent.setup()
    abrirEn('/cuenta/direcciones')
    const tarjeta = within(await screen.findByRole('list', { name: 'Mis direcciones' })).getByText('Av. de la Cultura 1234').closest('li')
    await usuario.click(within(tarjeta).getByRole('button', { name: 'Retirar dirección de entrega' }))
    let dialogo = await screen.findByRole('dialog', { name: 'Retirar dirección' })
    await usuario.click(within(dialogo).getByRole('button', { name: 'Retirar dirección' }))
    expect(await within(dialogo).findByText(/No pudimos retirar la dirección/)).toBeInTheDocument()
    expect(screen.getByText('Av. de la Cultura 1234')).toBeInTheDocument()
    await usuario.click(within(dialogo).getByRole('button', { name: 'Retirar dirección' }))
    expect(await screen.findByText('Dirección retirada')).toBeInTheDocument()
    expect(screen.queryByText('Av. de la Cultura 1234')).not.toBeInTheDocument()
    expect(llamadas(fetchMock, `${RETIRAR}7`)).toHaveLength(2)
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
