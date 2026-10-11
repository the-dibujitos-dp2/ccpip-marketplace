import { render, screen, within } from '@testing-library/react'
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
    expect(within(filas[0]).getByText('mililitro · de Asoc. Nii Joi')).toBeInTheDocument()
    expect(within(filas[0]).getByText('S/ 52.00 c/u')).toBeInTheDocument()
    expect(within(filas[0]).getByText('14 und')).toBeInTheDocument()
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

  it('lo que ya no se puede comprar se marca, avisa y no cuenta en el resumen', async () => {
    const carrito = copia(DATOS.carrito)
    carrito.items[0] = { ...carrito.items[0], estado: 'STOCK_INSUFICIENTE', disponibles: 3, subtotal: null }
    carrito.items.push(
      { ...carrito.items[1], productoId: 5, nombre: 'Café agotado', estado: 'AGOTADO', subtotal: null },
      { ...carrito.items[1], productoId: 6, nombre: 'Aceite retirado', estado: 'NO_DISPONIBLE', subtotal: null, precio: 30 },
      { ...carrito.items[1], productoId: 7, nombre: 'Cacao por mayor', estado: 'SOLO_MAYORISTA', subtotal: null, precio: null },
    )
    carrito.total = 84
    carrito.comunidades = 1
    servidor(conSesion(carrito))
    abrirEn('/carrito')

    expect(await screen.findByText('Algunos productos no se pueden comprar como están')).toBeInTheDocument()
    expect(screen.getByText('Solo quedan 3: ajusta la cantidad')).toBeInTheDocument()
    expect(screen.getByText('Agotado por ahora')).toBeInTheDocument()
    expect(screen.getByText('Ya no está disponible')).toBeInTheDocument()
    expect(screen.getByText('Solo se vende por mayor')).toBeInTheDocument()
    const resumen = screen.getByRole('complementary', { name: 'Resumen del pedido' })
    expect(within(resumen).getByText('Productos (2)')).toBeInTheDocument()
    expect(within(resumen).getAllByText('S/ 84.00')).toHaveLength(2)
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
