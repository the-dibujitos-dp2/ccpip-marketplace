import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import ilustracionAceites from '../../assets/lineas/aceites.svg'
import ilustracionCacao from '../../assets/lineas/cacao.svg'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const conSesion = (carrito = copia(DATOS.carrito)) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  'GET /api/carrito': [200, carrito],
})

describe('CW-05 Carrito (CCPIP-155)', () => {
  it('muestra cada oferta con su comunidad, precio, cantidad y subtotal, y el resumen con el total', async () => {
    servidor(conSesion())
    abrirEn('/carrito')
    expect(await screen.findByRole('link', { name: 'Aceite de aguaje 250 ml' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Tu carrito' })).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Pasos de la compra' })).toBeInTheDocument()

    const filas = within(screen.getByRole('region', { name: 'Productos en tu carrito' })).getAllByRole('listitem')
    expect(filas).toHaveLength(2)
    expect(within(filas[0]).getByRole('link', { name: 'Aceite de aguaje 250 ml' })).toHaveAttribute('href', '/productos/8')
    expect(within(filas[0]).getByText('mililitro · cosecha de setiembre 2026 · de Asoc. Nii Joi')).toBeInTheDocument()
    expect(within(filas[0]).getByText('S/ 52.00 c/u')).toBeInTheDocument()
    expect(within(filas[0]).getByLabelText('Cantidad de Aceite de aguaje 250 ml')).toHaveValue('14')
    expect(within(filas[0]).getByText('S/ 728.00')).toBeInTheDocument()
    expect(within(filas[1]).getByText('de C.N. Yarina', { exact: false })).toBeInTheDocument()
    // Sin foto del productor, cada oferta muestra la ilustracion de su linea (Figma CW-05)
    expect(filas[0].querySelector('img')).toHaveAttribute('src', ilustracionAceites)
    expect(filas[1].querySelector('img')).toHaveAttribute('src', ilustracionCacao)

    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    expect(within(resumen).getByText('Productos (16)')).toBeInTheDocument()
    expect(within(resumen).getByText('Comunidades').nextSibling).toHaveTextContent('2')
    expect(within(resumen).getAllByText('S/ 812.00')).toHaveLength(2)
    expect(within(resumen).getByText('Tu carrito no reserva stock')).toBeInTheDocument()
    // La cabecera usa las unidades de la misma consulta
    expect(screen.getByRole('link', { name: 'Carrito · 16' })).toHaveAttribute('href', '/carrito')
  })

  it('sin lotes vendibles no hay cosecha que mostrar', async () => {
    const carrito = copia(DATOS.carrito)
    carrito.items[0] = { ...carrito.items[0], cosecha: null }
    servidor(conSesion(carrito))
    abrirEn('/carrito')
    expect(await screen.findByText('mililitro · de Asoc. Nii Joi')).toBeInTheDocument()
  })

  it('lo que ya no se puede comprar se marca, avisa y no cuenta en el resumen', async () => {
    const carrito = copia(DATOS.carrito)
    carrito.items[0] = { ...carrito.items[0], estado: 'STOCK_INSUFICIENTE', disponibles: 3, subtotal: null }
    carrito.items.push(
      { ...carrito.items[1], productoId: 5, nombre: 'Café agotado', estado: 'AGOTADO', subtotal: null },
      { ...carrito.items[1], productoId: 6, nombre: 'Aceite retirado', estado: 'NO_DISPONIBLE', subtotal: null, precio: 30 },
      {
        ...carrito.items[1], productoId: 7, nombre: 'Cacao por mayor', estado: 'SOLO_MAYORISTA', subtotal: null,
        tarifa: 'MAYORISTA', precio: 30, precioMinorista: null, precioMayorista: 30, minimoMayorista: 10, faltanteMayorista: 8,
      },
    )
    carrito.total = 84
    carrito.comunidades = 1
    servidor(conSesion(carrito))
    abrirEn('/carrito')

    expect(await screen.findByText('Algunos productos no se pueden comprar como están')).toBeInTheDocument()
    expect(screen.getByText('Solo quedan 3: ajusta la cantidad')).toBeInTheDocument()
    expect(screen.getByText('Agotado por ahora')).toBeInTheDocument()
    expect(screen.getByText('Ya no está disponible')).toBeInTheDocument()
    expect(screen.getByText('Solo por mayor: mínimo 10 und')).toBeInTheDocument()
    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    expect(within(resumen).getByText('Productos (2)')).toBeInTheDocument()
    expect(within(resumen).getAllByText('S/ 84.00')).toHaveLength(2)
  })

  it('una oferta en venta pausada se marca, no se edita y no cuenta en el resumen (CCPIP-269)', async () => {
    const carrito = copia(DATOS.carrito)
    carrito.items[1] = { ...carrito.items[1], estado: 'VENTA_PAUSADA', subtotal: null }
    carrito.total = carrito.items[0].subtotal
    servidor(conSesion(carrito))
    abrirEn('/carrito')

    expect(await screen.findByText('Algunos productos no se pueden comprar como están')).toBeInTheDocument()
    const fila = screen.getByText('Venta pausada temporalmente').closest('li')
    expect(within(fila).getByText(carrito.items[1].nombre)).toBeInTheDocument()
    expect(screen.queryByLabelText(`Cantidad de ${carrito.items[1].nombre}`)).not.toBeInTheDocument()
    expect(screen.getByLabelText(`Cantidad de ${carrito.items[0].nombre}`)).toBeInTheDocument()
    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    // El total es solo el de la oferta que sigue comprable
    expect(within(resumen).getAllByText('S/ 728.00')).toHaveLength(2)
    // Se puede quitar: es la salida para lo que no se puede comprar (CCPIP-156)
    expect(within(fila).getByRole('button', { name: /Quitar/ })).toBeInTheDocument()
  })

  it('un carrito vacio invita a explorar productos', async () => {
    servidor(conSesion({ items: [], unidades: 0, comunidades: 0, total: 0 }))
    abrirEn('/carrito')
    expect(await screen.findByText('Tu carrito está vacío')).toBeInTheDocument()
    expect(within(screen.getByRole('main')).getByRole('link', { name: 'Explorar productos' })).toHaveAttribute('href', '/buscar')
    expect(screen.queryByRole('complementary')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Carrito' })).toBeInTheDocument()
  })

  it('si falla la red lo dice y permite reintentar', async () => {
    let intentos = 0
    servidor({
      ...conSesion(),
      'GET /api/carrito': () => (++intentos <= 2 ? new TypeError('Failed to fetch') : [200, copia(DATOS.carrito)]),
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    await user.click(await screen.findByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Aceite de aguaje 250 ml')).toBeInTheDocument()
  })

  it('sin sesion pide ingresar y vuelve al carrito', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/carrito')
    expect(await screen.findByRole('heading', { name: /Ingresa/ })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/ingresar')
    expect(new URLSearchParams(window.location.search).get('volver')).toBe('/carrito')
    expect(llamadas(fetchMock, 'GET /api/carrito')).toHaveLength(0)
  })

  it('otros roles no tienen carrito: ni pagina ni boton en la cabecera', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_PRODUCTOR] })
    abrirEn('/carrito')
    await screen.findByRole('button', { name: 'Mi cuenta' })
    expect(window.location.pathname).not.toBe('/carrito')
    expect(screen.queryByRole('link', { name: /^Carrito/ })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/carrito')).toHaveLength(0)
  })
})

describe('CCPIP-268 Modificar la cantidad en el carrito', () => {
  /** Respuesta del servidor tras cambiar la cantidad del aceite (precio 52). */
  function recalculado(cantidad) {
    const c = copia(DATOS.carrito)
    c.items[0] = { ...c.items[0], cantidad, subtotal: 52 * cantidad }
    c.unidades = cantidad + 2
    c.total = 52 * cantidad + 84
    return c
  }

  it('cambia la cantidad sin boton de actualizar y recalcula subtotal, total y cabecera', async () => {
    const fetchMock = servidor({
      ...conSesion(),
      'PUT /api/carrito/items/8': (o) => [200, recalculado(JSON.parse(o.body).cantidad)],
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    const cantidad = await screen.findByLabelText('Cantidad de Aceite de aguaje 250 ml')
    const fila = cantidad.closest('li')

    // Tres clics seguidos son una sola peticion, con la cantidad final
    await user.click(within(fila).getByRole('button', { name: 'Quitar una unidad' }))
    await user.click(within(fila).getByRole('button', { name: 'Quitar una unidad' }))
    await user.click(within(fila).getByRole('button', { name: 'Agregar una unidad' }))
    expect(cantidad).toHaveValue('13')
    expect(await within(fila).findByText('S/ 676.00')).toBeInTheDocument()
    const pedidos = llamadas(fetchMock, 'PUT /api/carrito/items/8')
    expect(pedidos).toHaveLength(1)
    expect(JSON.parse(pedidos[0][1].body)).toEqual({ cantidad: 13 })

    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    expect(within(resumen).getAllByText('S/ 760.00')).toHaveLength(2)
    expect(within(resumen).getByText('Productos (15)')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Carrito · 15' })).toBeInTheDocument()
  })

  // Espera el plazo real (6 s): adelantar un reloj simulado resulto inestable con la cobertura activa
  it('si el servidor lo rechaza explica el motivo unos segundos y vuelve a la cantidad anterior', { timeout: 15_000 }, async () => {
    servidor({
      ...conSesion(),
      'PUT /api/carrito/items/8': [409, { code: 'STOCK_INSUFICIENTE', error: 'Solo quedan 15 unidades disponibles.' }],
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    const cantidad = await screen.findByLabelText('Cantidad de Aceite de aguaje 250 ml')
    await user.clear(cantidad)
    await user.type(cantidad, '40')
    expect(await screen.findByRole('alert')).toHaveTextContent('Solo quedan 15 unidades disponibles.')
    expect(cantidad).toHaveValue('14')
    expect(screen.getByText('S/ 728.00')).toBeInTheDocument()

    // La cantidad ya es la correcta: el motivo se va solo
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument(), { timeout: 8000 })
  })

  it('el motivo del rechazo tambien se va al volver a cambiar la cantidad', async () => {
    servidor({
      ...conSesion(),
      'PUT /api/carrito/items/8': (o) =>
        JSON.parse(o.body).cantidad > 100
          ? [409, { code: 'STOCK_INSUFICIENTE', error: 'Solo quedan 100 unidades disponibles.' }]
          : [200, recalculado(JSON.parse(o.body).cantidad)],
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    const cantidad = await screen.findByLabelText('Cantidad de Aceite de aguaje 250 ml')
    await user.clear(cantidad)
    await user.type(cantidad, '999')
    expect(await screen.findByRole('alert')).toHaveTextContent('Solo quedan 100 unidades disponibles.')
    await user.click(within(cantidad.closest('li')).getByRole('button', { name: 'Agregar una unidad' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(await within(cantidad.closest('li')).findByText('S/ 780.00')).toBeInTheDocument()
  })

  it('desde 0 o vacio, "+" lleva a una unidad y borra el error', async () => {
    const fetchMock = servidor({
      ...conSesion(),
      'PUT /api/carrito/items/1': (o) => {
        const c = copia(DATOS.carrito)
        c.items[1] = { ...c.items[1], cantidad: JSON.parse(o.body).cantidad, subtotal: 42 }
        return [200, c]
      },
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    const cantidad = await screen.findByLabelText('Cantidad de Cacao en grano fermentado 1 kg')
    const fila = cantidad.closest('li')
    await user.clear(cantidad)
    await user.type(cantidad, '0')
    expect(await within(fila).findByRole('alert')).toHaveTextContent('La cantidad mínima es una unidad')
    expect(within(fila).getByRole('button', { name: 'Quitar una unidad' })).toBeDisabled()

    await user.click(within(fila).getByRole('button', { name: 'Agregar una unidad' }))
    expect(cantidad).toHaveValue('1')
    expect(within(fila).queryByRole('alert')).not.toBeInTheDocument()
    expect(await within(fila).findByText('S/ 42.00')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, 'PUT /api/carrito/items/1')[0][1].body)).toEqual({ cantidad: 1 })
  })

  it('una cantidad menor a una unidad no se envia', async () => {
    const fetchMock = servidor(conSesion())
    const user = userEvent.setup()
    abrirEn('/carrito')
    const cantidad = await screen.findByLabelText('Cantidad de Aceite de aguaje 250 ml')
    await user.clear(cantidad)
    await user.type(cantidad, '0')
    expect(await screen.findByRole('alert')).toHaveTextContent('La cantidad mínima es una unidad')
    expect(cantidad).toHaveAttribute('aria-invalid', 'true')
    await new Promise((r) => setTimeout(r, 600))
    expect(llamadas(fetchMock, 'PUT /api/carrito/items/8')).toHaveLength(0)
  })

  it('con stock insuficiente se puede bajar la cantidad; lo agotado o retirado no se edita', async () => {
    const carrito = copia(DATOS.carrito)
    carrito.items[0] = { ...carrito.items[0], estado: 'STOCK_INSUFICIENTE', disponibles: 3, subtotal: null }
    carrito.items[1] = { ...carrito.items[1], estado: 'AGOTADO', subtotal: null }
    servidor({
      ...conSesion(carrito),
      'PUT /api/carrito/items/8': () => {
        const c = copia(carrito)
        c.items[0] = { ...DATOS.carrito.items[0], cantidad: 3, subtotal: 156 }
        c.total = 156
        return [200, c]
      },
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    const cantidad = await screen.findByLabelText('Cantidad de Aceite de aguaje 250 ml')
    expect(screen.queryByLabelText('Cantidad de Cacao en grano fermentado 1 kg')).not.toBeInTheDocument()
    expect(screen.getByText('2 und')).toBeInTheDocument()

    await user.clear(cantidad)
    await user.type(cantidad, '3')
    expect(await within(cantidad.closest('li')).findByText('S/ 156.00')).toBeInTheDocument()
    expect(screen.queryByText('Solo quedan 3: ajusta la cantidad')).not.toBeInTheDocument()
  })
})

describe('CW-05 Tarifa mayorista en el carrito (CCPIP-134)', () => {
  /** El cacao (S/ 42) tambien se vende por mayor: S/ 35 desde 12 und. */
  function conCacaoMayorista(cantidad) {
    const c = copia(DATOS.carrito)
    const aplicada = cantidad >= 12
    const precioAplicado = aplicada ? 35 : 42
    c.items[1] = {
      ...c.items[1], cantidad, precio: precioAplicado, subtotal: precioAplicado * cantidad,
      tarifa: aplicada ? 'MAYORISTA' : 'MINORISTA', precioMinorista: 42, precioMayorista: 35, minimoMayorista: 12,
      faltanteMayorista: Math.max(0, 12 - cantidad),
    }
    c.unidades = 14 + cantidad
    c.total = 728 + precioAplicado * cantidad
    return c
  }

  it('bajo el minimo dice cuanto falta y, al editar la cantidad, muestra el precio mayorista aplicado', async () => {
    const fetchMock = servidor({
      ...conSesion(conCacaoMayorista(2)),
      'PUT /api/carrito/items/1': (o) => [200, conCacaoMayorista(JSON.parse(o.body).cantidad)],
    })
    abrirEn('/carrito')
    const cantidad = await screen.findByLabelText('Cantidad de Cacao en grano fermentado 1 kg')
    const fila = cantidad.closest('li')
    expect(within(fila).getByText('S/ 42.00 c/u')).toBeInTheDocument()
    expect(within(fila).getByText('Agrega 10 unidades más para acceder al precio mayorista (S/ 35.00 c/u)')).toBeInTheDocument()
    expect(within(fila).getByText('2 de 12 und · el precio cambia solo al llegar al mínimo que definió C.N. Yarina')).toBeInTheDocument()

    // Un solo cambio: borrar y teclear bajo carga puede cruzarse con la espera antes de guardar
    fireEvent.change(cantidad, { target: { value: '12' } })
    expect(await within(fila).findByText('S/ 35.00 c/u · precio mayorista')).toBeInTheDocument()
    expect(within(fila).getByText('Precio mayorista aplicado: S/ 35.00 c/u')).toBeInTheDocument()
    expect(within(fila).getByText('Ahorras S/ 84.00 frente al precio minorista')).toBeInTheDocument()
    expect(within(fila).getByText('S/ 420.00')).toBeInTheDocument()
    expect(within(fila).queryByText(/para acceder al precio mayorista/)).not.toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, 'PUT /api/carrito/items/1').at(-1)[1].body)).toEqual({ cantidad: 12 })
    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    expect(within(resumen).getAllByText('S/ 1,148.00')).toHaveLength(2)
  })

  it('lo que solo se vende por mayor y no llega al minimo se marca y se resuelve subiendo la cantidad', async () => {
    const carrito = copia(DATOS.carrito)
    const cacao = {
      ...carrito.items[1], cantidad: 4, tarifa: 'MAYORISTA', precio: 35, precioMinorista: null, precioMayorista: 35,
      minimoMayorista: 12, faltanteMayorista: 8,
    }
    carrito.items[1] = { ...cacao, estado: 'SOLO_MAYORISTA', subtotal: null }
    carrito.total = 728
    const fetchMock = servidor({
      ...conSesion(carrito),
      'PUT /api/carrito/items/1': () => {
        const c = copia(carrito)
        c.items[1] = { ...cacao, cantidad: 12, estado: 'DISPONIBLE', subtotal: 420, faltanteMayorista: 0 }
        c.total = 1148
        return [200, c]
      },
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    const cantidad = await screen.findByLabelText('Cantidad de Cacao en grano fermentado 1 kg')
    const fila = cantidad.closest('li')
    expect(within(fila).getByText('Solo por mayor: mínimo 12 und')).toBeInTheDocument()
    expect(within(fila).getByText('Este producto se vende solo por mayor: mínimo 12 und')).toBeInTheDocument()
    expect(within(fila).getByText('Agrega 8 und más para poder comprarlo')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continuar con la entrega y pago' })).toBeDisabled()

    // "+" lleva directo al minimo, y de ahi no se baja
    await user.click(within(fila).getByRole('button', { name: 'Agregar una unidad' }))
    expect(cantidad).toHaveValue('12')
    expect(await within(fila).findByText('S/ 420.00')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, 'PUT /api/carrito/items/1')[0][1].body)).toEqual({ cantidad: 12 })
    expect(within(fila).getByRole('button', { name: 'Quitar una unidad' })).toBeDisabled()
    expect(within(fila).queryByText(/para poder comprarlo/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Continuar con la entrega y pago' })).toBeInTheDocument()
  })
})

describe('CCPIP-156 Quitar una oferta del carrito', () => {
  /** Respuesta del servidor tras quitar el aceite: queda solo el cacao. */
  function sinAceite() {
    const c = copia(DATOS.carrito)
    c.items = c.items.slice(1)
    c.unidades = 2
    c.comunidades = 1
    c.total = 84
    return c
  }

  it('quita la oferta sin confirmacion, recalcula todo y lo avisa', async () => {
    const fetchMock = servidor({ ...conSesion(), 'DELETE /api/carrito/items/8': [200, sinAceite()] })
    const user = userEvent.setup()
    abrirEn('/carrito')
    await user.click(await screen.findByRole('button', { name: 'Quitar Aceite de aguaje 250 ml del carrito' }))

    expect(await screen.findByText('Quitaste Aceite de aguaje 250 ml de tu carrito')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Aceite de aguaje 250 ml' })).not.toBeInTheDocument()
    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    expect(within(resumen).getByText('Productos (2)')).toBeInTheDocument()
    expect(within(resumen).getAllByText('S/ 84.00')).toHaveLength(2)
    expect(screen.getByRole('link', { name: 'Carrito · 2' })).toBeInTheDocument()
    expect(llamadas(fetchMock, 'DELETE /api/carrito/items/8')).toHaveLength(1)
  })

  it('al quitar la ultima oferta queda el carrito vacio', async () => {
    const soloUna = copia(DATOS.carrito)
    soloUna.items = soloUna.items.slice(0, 1)
    servidor({
      ...conSesion(soloUna),
      'DELETE /api/carrito/items/8': [200, { items: [], unidades: 0, comunidades: 0, total: 0 }],
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    await user.click(await screen.findByRole('button', { name: 'Quitar Aceite de aguaje 250 ml del carrito' }))
    expect(await screen.findByText('Tu carrito está vacío')).toBeInTheDocument()
    expect(screen.getByText('Quitaste Aceite de aguaje 250 ml de tu carrito')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Carrito' })).toBeInTheDocument()
  })

  it('lo que ya no se puede comprar tambien se quita', async () => {
    const carrito = copia(DATOS.carrito)
    carrito.items[1] = { ...carrito.items[1], estado: 'AGOTADO', subtotal: null }
    servidor({
      ...conSesion(carrito),
      'DELETE /api/carrito/items/1': () => {
        const c = copia(carrito)
        c.items = c.items.slice(0, 1)
        return [200, c]
      },
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    expect(await screen.findByText('Agotado por ahora')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Quitar Cacao en grano fermentado 1 kg del carrito' }))
    expect(await screen.findByText('Quitaste Cacao en grano fermentado 1 kg de tu carrito')).toBeInTheDocument()
    expect(screen.queryByText('Agotado por ahora')).not.toBeInTheDocument()
  })

  it('si ya no estaba en el carrito, muestra el carrito actual', async () => {
    // Otra pestana ya lo quito: desde el intento, el servidor devuelve el carrito sin el aceite
    let quitadoAntes = false
    servidor({
      ...conSesion(),
      'GET /api/carrito': () => [200, quitadoAntes ? sinAceite() : copia(DATOS.carrito)],
      'DELETE /api/carrito/items/8': () => {
        quitadoAntes = true
        return [404, { code: 'ITEM_NO_ENCONTRADO', error: 'Este producto ya no está en tu carrito.' }]
      },
    })
    const user = userEvent.setup()
    abrirEn('/carrito')
    await user.click(await screen.findByRole('button', { name: 'Quitar Aceite de aguaje 250 ml del carrito' }))
    await waitFor(() => expect(screen.queryByRole('link', { name: 'Aceite de aguaje 250 ml' })).not.toBeInTheDocument())
    expect(screen.getByRole('link', { name: 'Cacao en grano fermentado 1 kg' })).toBeInTheDocument()
  })

  it('si falla, lo dice y la oferta sigue en el carrito', async () => {
    servidor({ ...conSesion(), 'DELETE /api/carrito/items/8': new TypeError('Failed to fetch') })
    const user = userEvent.setup()
    abrirEn('/carrito')
    const quitar = await screen.findByRole('button', { name: 'Quitar Aceite de aguaje 250 ml del carrito' })
    await user.click(quitar)
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos quitar Aceite de aguaje 250 ml')
    expect(screen.getByRole('link', { name: 'Aceite de aguaje 250 ml' })).toBeInTheDocument()
    expect(quitar).toBeEnabled()
  })
})

describe('Boton "Carrito" de la cabecera (CCPIP-155)', () => {
  it('el visitante lo ve sin numero y lleva al carrito', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/')
    expect(await screen.findByRole('link', { name: 'Carrito' })).toHaveAttribute('href', '/carrito')
  })

  it('se actualiza al agregar una oferta desde la ficha', async () => {
    let unidades = 16
    servidor({
      ...conSesion(),
      'GET /api/carrito': () => [200, { ...copia(DATOS.carrito), unidades }],
      'GET /api/productos/1': [200, copia(DATOS.detalle)],
      'POST /api/carrito/items': () => {
        unidades = 18
        return [200, { productoId: 1, cantidad: 4, articulos: 2 }]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    expect(await screen.findByRole('link', { name: 'Carrito · 16' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Agregar una unidad' }))
    await user.click(screen.getByRole('button', { name: 'Agregar al carrito' }))
    expect(await screen.findByRole('link', { name: 'Carrito · 18' })).toBeInTheDocument()
  })
})
