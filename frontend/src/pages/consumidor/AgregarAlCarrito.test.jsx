import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const conSesion = { ...PUBLICAS, 'GET /api/auth/me': [200, SESION] }
const ficha = (extra = {}) => ({ 'GET /api/productos/1': [200, { ...copia(DATOS.detalle), ...extra }] })

async function escribirCantidad(user, valor) {
  const campo = await screen.findByLabelText('Cantidad')
  await user.clear(campo)
  if (valor !== '') await user.type(campo, valor)
}

describe('CCPIP-58 Agregar al carrito desde la ficha', () => {
  it('el comprador agrega la cantidad elegida y ve cuanto tiene en su carrito', async () => {
    const fetchMock = servidor({
      ...conSesion,
      ...ficha(),
      'POST /api/carrito/items': [200, { productoId: 1, cantidad: 5, articulos: 1 }],
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await escribirCantidad(user, '3')
    await user.click(screen.getByRole('button', { name: 'Agregar al carrito' }))

    expect(await screen.findByText('Agregaste 3 unidades a tu carrito')).toBeInTheDocument()
    expect(screen.getByText('Ahora tienes 5 de este producto en tu carrito.')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, 'POST /api/carrito/items')
    expect(JSON.parse(opciones.body)).toEqual({ productoId: 1, cantidad: 3 })
  })

  it('el selector sube y baja la cantidad sin bajar de una unidad', async () => {
    const fetchMock = servidor({
      ...conSesion,
      ...ficha(),
      'POST /api/carrito/items': [200, { productoId: 1, cantidad: 2, articulos: 1 }],
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    const quitar = await screen.findByRole('button', { name: 'Quitar una unidad' })
    expect(quitar).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Agregar una unidad' }))
    await user.click(screen.getByRole('button', { name: 'Agregar una unidad' }))
    expect(screen.getByLabelText('Cantidad')).toHaveValue('3')
    await user.click(quitar)
    expect(screen.getByLabelText('Cantidad')).toHaveValue('2')

    await user.click(screen.getByRole('button', { name: 'Agregar al carrito' }))
    expect(await screen.findByText('Agregaste 2 unidades a tu carrito')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, 'POST /api/carrito/items')[0][1].body).cantidad).toBe(2)
  })

  it('sin sesion lleva a ingresar y vuelve a la ficha, sin llamar al carrito', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, ...ficha() })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await user.click(await screen.findByRole('button', { name: 'Agregar al carrito' }))

    expect(window.location.pathname).toBe('/ingresar')
    expect(new URLSearchParams(window.location.search).get('volver')).toBe('/productos/1')
    expect(llamadas(fetchMock, 'POST /api/carrito/items')).toHaveLength(0)
  })

  it('una cantidad menor a una unidad o con decimales se corrige antes de enviar', async () => {
    const fetchMock = servidor({ ...conSesion, ...ficha() })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    for (const valor of ['0', '1.5', '']) {
      await escribirCantidad(user, valor)
      await user.click(screen.getByRole('button', { name: 'Agregar al carrito' }))
      expect(screen.getByText('La cantidad mínima es una unidad')).toBeInTheDocument()
      expect(screen.getByLabelText('Cantidad')).toHaveAttribute('aria-invalid', 'true')
    }
    expect(llamadas(fetchMock, 'POST /api/carrito/items')).toHaveLength(0)
  })

  it('muestra el motivo cuando el servidor no lo agrega, por ejemplo sin stock suficiente', async () => {
    servidor({
      ...conSesion,
      ...ficha(),
      'POST /api/carrito/items': [409, { code: 'STOCK_INSUFICIENTE', error: 'Solo quedan 5 unidades disponibles.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await escribirCantidad(user, '9')
    await user.click(screen.getByRole('button', { name: 'Agregar al carrito' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Solo quedan 5 unidades disponibles.')
    // Al cambiar la cantidad el aviso anterior desaparece
    await escribirCantidad(user, '2')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('no se ofrece a otros roles ni en productos agotados', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_PRODUCTOR], ...ficha() })
    const { unmount } = abrirEn('/productos/1')
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Agregar al carrito' })).not.toBeInTheDocument()
    unmount()

    servidor({ ...conSesion, ...ficha({ disponible: false }) })
    const agotado = abrirEn('/productos/1')
    expect(await screen.findByText('Agotado por ahora')).toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad')).not.toBeInTheDocument()
  })

  it('una oferta solo mayorista se muestra pero aun no se puede agregar (CW-03c)', async () => {
    const fetchMock = servidor({
      ...conSesion,
      ...ficha({ modalidad: 'MAYORISTA', precio: null, precioMayorista: 30, cantidadMinimaMayorista: 10 }),
    })
    abrirEn('/productos/1')
    expect(await screen.findByRole('alert')).toHaveTextContent('Este producto se vende solo por mayor: mínimo 10 und')
    expect(screen.getByRole('button', { name: 'Agregar al carrito' })).toBeDisabled()
    expect(screen.getByLabelText('Cantidad')).toBeDisabled()
    expect(llamadas(fetchMock, 'POST /api/carrito/items')).toHaveLength(0)
  })

  it('en una oferta con ambas modalidades avisa que por ahora se compra al precio minorista', async () => {
    servidor({ ...conSesion, ...ficha({ modalidad: 'AMBAS', precioMayorista: 35, cantidadMinimaMayorista: 12 }) })
    abrirEn('/productos/1')
    expect(await screen.findByText(/Desde 12 und el precio mayorista \(S\/\s?35\.00 c\/u\)/)).toBeInTheDocument()
    expect(screen.getByText(/por ahora el carrito usa el precio minorista/)).toBeInTheDocument()
  })
})
