import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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

/** Simula el scroll: ubica el panel de compra en pantalla, arriba (ya se paso) o abajo (aun no se llega). */
function moverPanel(estado) {
  const fila = document.querySelector('.agregar-carrito__fila')
  const top = { visible: 300, arriba: -400, abajo: 1400 }[estado]
  fila.getBoundingClientRect = () => ({ top, bottom: top + 48, left: 0, right: 400, width: 400, height: 48 })
  fireEvent.scroll(window)
}

const barra = () => screen.queryByRole('region', { name: 'Compra rápida' })

describe('CW-03b Barra de compra persistente (CCPIP-58)', () => {
  it('aparece al dejar el panel atras, comparte la cantidad y agrega desde ahi', async () => {
    const fetchMock = servidor({
      ...conSesion,
      ...ficha(),
      'GET /api/carrito': [200, { items: [], unidades: 2, comunidades: 1, total: 84 }],
      'POST /api/carrito/items': [200, { productoId: 1, cantidad: 2, articulos: 1 }],
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await screen.findByRole('button', { name: 'Agregar al carrito' })
    expect(barra()).not.toBeInTheDocument()

    // Aun no se llega al panel (esta abajo): no hay barra
    moverPanel('abajo')
    await new Promise((r) => requestAnimationFrame(r))
    expect(barra()).not.toBeInTheDocument()

    // Salto directo de abajo a arriba, sin pasar por el panel (p. ej. "Leer su historia")
    moverPanel('arriba')
    const compra = await screen.findByRole('region', { name: 'Compra rápida' })
    expect(within(compra).getByText(DATOS.detalle.nombre)).toBeInTheDocument()
    expect(within(compra).getByText(`${DATOS.detalle.tienda.nombre} · S/ 42.00 minorista`)).toBeInTheDocument()

    await user.click(within(compra).getByRole('button', { name: 'Agregar una unidad' }))
    expect(screen.getByLabelText('Cantidad')).toHaveValue('2')
    await user.click(within(compra).getByRole('button', { name: 'Agregar al carrito' }))
    expect(await within(compra).findByText('Agregaste 2 unidades a tu carrito')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, 'POST /api/carrito/items')[0][1].body).cantidad).toBe(2)

    // De vuelta en el panel, la barra se va
    moverPanel('visible')
    await waitFor(() => expect(barra()).not.toBeInTheDocument())
  })

  it('muestra en la barra el error de cantidad y no aparece en ofertas solo mayoristas', async () => {
    servidor({ ...conSesion, ...ficha({ modalidad: 'AMBAS', precioMayorista: 35, cantidadMinimaMayorista: 12 }) })
    const user = userEvent.setup()
    const { unmount } = abrirEn('/productos/1')
    await screen.findByRole('button', { name: 'Agregar al carrito' })
    moverPanel('arriba')
    const compra = await screen.findByRole('region', { name: 'Compra rápida' })
    expect(within(compra).getByText(/S\/ 42\.00 minorista · S\/ 35\.00 desde 12 und/)).toBeInTheDocument()
    await user.clear(within(compra).getByLabelText('Cantidad a agregar'))
    await user.click(within(compra).getByRole('button', { name: 'Agregar al carrito' }))
    expect(within(compra).getByRole('status')).toHaveTextContent('La cantidad mínima es una unidad')
    unmount()

    servidor({ ...conSesion, ...ficha({ modalidad: 'MAYORISTA', precio: null, precioMayorista: 30, cantidadMinimaMayorista: 10 }) })
    abrirEn('/productos/1')
    await screen.findByText(/se vende solo por mayor/)
    moverPanel('arriba')
    await new Promise((r) => requestAnimationFrame(r))
    expect(barra()).not.toBeInTheDocument()
  })
})

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
