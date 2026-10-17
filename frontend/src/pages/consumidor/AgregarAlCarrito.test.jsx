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

  it('muestra en la barra el error de cantidad y el minimo de las ofertas solo mayoristas', async () => {
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

    // CCPIP-134: la barra arranca en el minimo y, bajo el, dice cuanto falta y no deja agregar
    servidor({ ...conSesion, ...ficha({ modalidad: 'MAYORISTA', precio: null, precioMayorista: 30, cantidadMinimaMayorista: 10 }) })
    abrirEn('/productos/1')
    await screen.findByRole('button', { name: 'Agregar al carrito' })
    moverPanel('arriba')
    const mayorista = await screen.findByRole('region', { name: 'Compra rápida' })
    const cantidad = within(mayorista).getByLabelText('Cantidad a agregar')
    expect(cantidad).toHaveValue('10')
    expect(within(mayorista).getByRole('status')).toHaveTextContent('S/ 30.00 desde 10 und')
    await user.clear(cantidad)
    await user.type(cantidad, '4')
    expect(within(mayorista).getByRole('status')).toHaveTextContent('Agrega 6 und más para poder agregarlo al carrito')
    expect(within(mayorista).getByRole('button', { name: 'Agregar al carrito' })).toBeDisabled()
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

    // Desde un campo vacio, "+" lleva a una unidad (no a dos)
    await user.clear(screen.getByLabelText('Cantidad'))
    expect(quitar).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Agregar una unidad' }))
    expect(screen.getByLabelText('Cantidad')).toHaveValue('1')
    await user.click(screen.getByRole('button', { name: 'Agregar una unidad' }))

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

})

describe('CW-03c Tarifa mayorista en el panel de compra (CCPIP-134)', () => {
  const soloMayorista = ficha({ modalidad: 'MAYORISTA', precio: null, precioMayorista: 30, cantidadMinimaMayorista: 10 })
  const ambas = ficha({ modalidad: 'AMBAS', precioMayorista: 35, cantidadMinimaMayorista: 12 })

  it('una oferta solo mayorista arranca en su minimo y bajo el no se puede agregar', async () => {
    const fetchMock = servidor({
      ...conSesion,
      ...soloMayorista,
      'POST /api/carrito/items': [200, { productoId: 1, cantidad: 10, articulos: 1 }],
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    const campo = await screen.findByLabelText('Cantidad')
    expect(campo).toHaveValue('10')
    // No baja de su minimo
    expect(screen.getByRole('button', { name: 'Quitar una unidad' })).toBeDisabled()

    await escribirCantidad(user, '4')
    expect(screen.getByText('Este producto se vende solo por mayor: mínimo 10 und')).toBeInTheDocument()
    expect(screen.getByText('Agrega 6 und más para poder agregarlo al carrito')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Agregar al carrito' })).toBeDisabled()

    // "+" lleva directo al minimo
    await user.click(screen.getByRole('button', { name: 'Agregar una unidad' }))
    expect(campo).toHaveValue('10')
    expect(screen.queryByText(/para poder agregarlo al carrito/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Agregar al carrito' }))
    expect(await screen.findByText('Agregaste 10 unidades a tu carrito')).toBeInTheDocument()
    const enviados = llamadas(fetchMock, 'POST /api/carrito/items')
    expect(enviados).toHaveLength(1)
    expect(JSON.parse(enviados[0][1].body).cantidad).toBe(10)
  })

  it('con ambas modalidades el mayorista se aplica solo desde el minimo y se informa cuanto falta', async () => {
    servidor({ ...conSesion, ...ambas })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    expect(await screen.findByText('Desde 12 und pagas S/ 35.00 c/u (precio mayorista).')).toBeInTheDocument()
    const minorista = screen.getByRole('radio', { name: /Precio minorista/ })
    const mayorista = screen.getByRole('radio', { name: /Precio mayorista/ })
    expect(minorista).toHaveAttribute('aria-checked', 'true')

    // Elegir el mayorista bajo el minimo dice cuanto falta, sin aplicarlo
    await user.click(mayorista)
    expect(mayorista).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('Agrega 11 unidades más para acceder al precio mayorista')).toBeInTheDocument()
    expect(screen.getByText('1 de 12 und · el precio cambia solo al llegar al mínimo')).toBeInTheDocument()
    await escribirCantidad(user, '11')
    expect(screen.getByText('Agrega 1 unidad más para acceder al precio mayorista')).toBeInTheDocument()

    await escribirCantidad(user, '12')
    expect(screen.getByText('Precio mayorista aplicado: S/ 35.00 c/u')).toBeInTheDocument()
    expect(screen.getByText('Ahorras S/ 84.00 frente al precio minorista')).toBeInTheDocument()
    expect(screen.queryByText(/para acceder al precio mayorista/)).not.toBeInTheDocument()

    // Volver al minorista es volver a comprar por unidad
    await user.click(minorista)
    expect(minorista).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByLabelText('Cantidad')).toHaveValue('1')
    expect(screen.getByText('Desde 12 und pagas S/ 35.00 c/u (precio mayorista).')).toBeInTheDocument()
  })

  it('llegar al minimo con el selector marca el precio mayorista aunque se haya elegido el minorista', async () => {
    servidor({ ...conSesion, ...ambas })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await escribirCantidad(user, '20')
    expect(screen.getByRole('radio', { name: /Precio mayorista/ })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('Ahorras S/ 140.00 frente al precio minorista')).toBeInTheDocument()
  })

  it('si el servidor rechaza la cantidad por el minimo mayorista, explica el motivo', async () => {
    servidor({
      ...conSesion,
      ...soloMayorista,
      'POST /api/carrito/items': [422, {
        code: 'MINIMO_MAYORISTA',
        error: 'Este producto se vende solo por mayor: mínimo 12 und. Te faltan 2 und para poder comprarlo.',
      }],
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await user.click(await screen.findByRole('button', { name: 'Agregar al carrito' }))
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No pudimos agregarlo')
    expect(alerta).toHaveTextContent('mínimo 12 und. Te faltan 2 und para poder comprarlo.')
  })
})

describe('CW-03c Venta pausada (CCPIP-269)', () => {
  it('sin cuenta bancaria activa o autorizacion vigente la ficha se ve completa pero sin compra', async () => {
    const fetchMock = servidor({ ...conSesion, ...ficha({ comprable: false }) })
    abrirEn('/productos/1')

    expect(await screen.findByText('● Venta pausada temporalmente')).toBeInTheDocument()
    expect(
      screen.getByText(`${DATOS.detalle.tienda.nombre} pausó sus ventas por un tema administrativo. Puedes ver todo el producto, pero por ahora no se puede comprar.`),
    ).toBeInTheDocument()
    // La oferta sigue consultable: nombre y precio a la vista
    expect(screen.getByRole('heading', { level: 1, name: DATOS.detalle.nombre })).toBeInTheDocument()
    expect(screen.getAllByText('S/ 42.00').length).toBeGreaterThan(0)
    expect(screen.queryByText('● Disponible')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Cantidad')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Agregar al carrito' })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/carrito/items')).toHaveLength(0)
  })

  it('si la venta se pausa mientras mira la ficha, el servidor lo rechaza y se explica', async () => {
    servidor({
      ...conSesion,
      ...ficha(),
      'POST /api/carrito/items': [409, {
        code: 'VENTA_PAUSADA',
        error: 'La venta de este producto está pausada por ahora. Puedes verlo, pero aún no comprarlo.',
      }],
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await user.click(await screen.findByRole('button', { name: 'Agregar al carrito' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No pudimos agregarlo')
    expect(alerta).toHaveTextContent('La venta de este producto está pausada por ahora.')
  })
})
