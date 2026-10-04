import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.jsx'
import { llamadas, pagina, producto, PRODUCTOR, servidor, SESION, SIN_SESION } from '../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const ACEITE = producto(1, 'Aceite de ungurahui 250 ml')
const CAFE = producto(2, 'Cafe tostado en grano 500 g', {
  linea: { codigo: 'CAFE', nombre: 'Cafe' },
  productor: { ...PRODUCTOR, id: 3, comunidad: 'Comunidad Asháninka', region: 'Junin', origenVerificado: false },
})

describe('Catalogo y busqueda (CCPIP-52)', () => {
  it('es publico: sin sesion muestra el catalogo y la opcion de ingresar', async () => {
    servidor({ 'GET /api/auth/me': SIN_SESION, 'GET /api/productos?pagina=0&tamano=12': pagina([ACEITE, CAFE]) })
    abrirEn('/')

    expect(await screen.findByRole('heading', { name: 'Explora productos con historia' })).toBeInTheDocument()
    const productos = within(await screen.findByRole('list', { name: 'Productos' }))
    expect(productos.getAllByRole('link')).toHaveLength(2)
    expect(productos.getByText('Aceite de ungurahui 250 ml')).toBeInTheDocument()
    expect(productos.getByText('Comunidad Shipibo Konibo · Ucayali')).toBeInTheDocument()
    expect(productos.getAllByText(/S\/\s?48\.00/)).toHaveLength(2)
    // Solo el productor verificado lleva el sello
    expect(productos.getAllByAltText('Origen verificado')).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Ingresar' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/')
  })

  it('busca desde la cabecera y deja el termino en la URL', async () => {
    const fetchMock = servidor({
      'GET /api/auth/me': SIN_SESION,
      'GET /api/productos?pagina=0&tamano=12': pagina([ACEITE, CAFE]),
      'GET /api/productos?pagina=0&tamano=12&q=caf%C3%A9': pagina([CAFE]),
    })
    const user = userEvent.setup()
    abrirEn('/')
    await user.type(await screen.findByRole('searchbox', { name: 'Buscar productos' }), 'café{Enter}')

    expect(await screen.findByRole('heading', { name: 'Resultados para “café”' })).toBeInTheDocument()
    expect(window.location.search).toBe('?q=caf%C3%A9')
    // Nunca se mezclan resultados de la busqueda anterior con el titulo nuevo
    await waitFor(() => expect(screen.queryByText('Aceite de ungurahui 250 ml')).not.toBeInTheDocument())
    expect(await screen.findByText('Cafe tostado en grano 500 g')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/productos?pagina=0&tamano=12&q=caf%C3%A9')).toHaveLength(1)
  })

  it('una busqueda sin resultados muestra un mensaje claro y una salida', async () => {
    servidor({
      'GET /api/auth/me': SIN_SESION,
      'GET /api/productos?pagina=0&tamano=12&q=televisor': pagina([]),
      'GET /api/productos?pagina=0&tamano=12': pagina([ACEITE]),
    })
    const user = userEvent.setup()
    abrirEn('/?q=televisor')

    expect(await screen.findByText('No encontramos productos para “televisor”')).toBeInTheDocument()
    expect(screen.getByText(/Prueba con otra palabra/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Ver todo el catálogo' }))
    expect(await screen.findByText('Aceite de ungurahui 250 ml')).toBeInTheDocument()
  })

  it('pagina los resultados', async () => {
    const primeros = Array.from({ length: 12 }, (_, i) => producto(i + 1, `Producto ${i + 1}`))
    servidor({
      'GET /api/auth/me': SIN_SESION,
      'GET /api/productos?pagina=0&tamano=12': pagina(primeros, { total: 14 }),
      'GET /api/productos?pagina=1&tamano=12': pagina([producto(13, 'Producto 13'), producto(14, 'Producto 14')], { pagina: 1, total: 14 }),
    })
    const user = userEvent.setup()
    abrirEn('/')

    expect(await screen.findByText('Mostrando 1–12 de 14')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))

    expect(await screen.findByText('Mostrando 13–14 de 14')).toBeInTheDocument()
    expect(window.location.search).toBe('?pagina=1')
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled()
  })

  it('avisa si el catalogo no carga', async () => {
    servidor({ 'GET /api/auth/me': SIN_SESION, 'GET /api/productos?pagina=0&tamano=12': new TypeError('Failed to fetch') })
    abrirEn('/')
    expect(await screen.findByText('No pudimos cargar el catálogo')).toBeInTheDocument()
  })

  it('una ruta desconocida vuelve al catalogo', async () => {
    servidor({ 'GET /api/auth/me': SIN_SESION, 'GET /api/productos?pagina=0&tamano=12': pagina([]) })
    abrirEn('/no-existe')
    expect(await screen.findByText('Aún no hay productos disponibles')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/')
  })
})

describe('Ficha del producto (CCPIP-53) e historia de origen (CCPIP-152)', () => {
  const FICHA = {
    ...ACEITE,
    descripcion: 'Aceite prensado en frio, sin refinar.',
    historiaOrigen: 'Cosechado a mano en la cuenca del Ucayali.',
    disponible: true,
  }

  it('es publica y muestra lo necesario para decidir la compra', async () => {
    servidor({ 'GET /api/auth/me': SIN_SESION, 'GET /api/productos/1': [200, FICHA] })
    abrirEn('/productos/1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Aceite de ungurahui 250 ml' })).toBeInTheDocument()
    expect(screen.getByText('Aceite prensado en frio, sin refinar.')).toBeInTheDocument()
    expect(screen.getByText(/S\/\s?48\.00/)).toBeInTheDocument()
    expect(screen.getByText('Mililitro')).toBeInTheDocument()
    expect(screen.getAllByText('Aceites').length).toBeGreaterThan(0)
    expect(screen.getByText('Disponible')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Comunidad Shipibo Konibo' })).toHaveAttribute('href', '/productores/1')
    expect(screen.getByRole('button', { name: 'Agregar al carrito' })).toBeEnabled()
  })

  it('muestra la historia de origen con su comunidad, region y sello', async () => {
    servidor({ 'GET /api/auth/me': SIN_SESION, 'GET /api/productos/1': [200, FICHA] })
    abrirEn('/productos/1')

    const historia = await screen.findByRole('region', { name: 'La historia detrás de este producto' })
    expect(within(historia).getByText('Cosechado a mano en la cuenca del Ucayali.')).toBeInTheDocument()
    expect(within(historia).getByText('Comunidad Shipibo Konibo · Ucayali')).toBeInTheDocument()
    expect(within(historia).getByText('Origen verificado por la CCPIP')).toBeInTheDocument()
  })

  it('sin historia cargada no deja un espacio vacio', async () => {
    const { historiaOrigen: _omitida, ...sinHistoria } = FICHA
    servidor({ 'GET /api/auth/me': SIN_SESION, 'GET /api/productos/1': [200, sinHistoria] })
    abrirEn('/productos/1')

    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByText('La historia detrás de este producto')).not.toBeInTheDocument()
    // La comunidad y la region siguen visibles en la ficha
    expect(screen.getByText('· Ucayali')).toBeInTheDocument()
  })

  it('sin stock se puede ver pero no agregar al carrito', async () => {
    servidor({ 'GET /api/auth/me': SIN_SESION, 'GET /api/productos/1': [200, { ...FICHA, disponible: false }] })
    abrirEn('/productos/1')

    expect(await screen.findByText('Agotado')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Agregar al carrito' })).toBeDisabled()
    expect(screen.getByText(/Sin stock por ahora/)).toBeInTheDocument()
  })

  it('agregar al carrito sin sesion lleva a ingresar y luego vuelve a la ficha', async () => {
    let conSesion = false
    servidor({
      'GET /api/auth/me': () => (conSesion ? [200, SESION] : SIN_SESION),
      'GET /api/productos/1': [200, FICHA],
      'POST /api/auth/login': () => {
        conSesion = true
        return [200, SESION]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    await user.click(await screen.findByRole('button', { name: 'Agregar al carrito' }))

    expect(window.location.pathname).toBe('/ingresar')
    await user.type(await screen.findByLabelText('Correo'), 'consumidor@ccpip.test')
    await user.type(screen.getByLabelText('Contraseña'), 'Demo1234!')
    await user.click(screen.getByRole('button', { name: 'Ingresar' }))

    await waitFor(() => expect(window.location.pathname).toBe('/productos/1'))
    await user.click(await screen.findByRole('button', { name: 'Agregar al carrito' }))
    expect(await screen.findByText('El carrito estará disponible muy pronto')).toBeInTheDocument()
  })

  it('volver= solo acepta rutas internas', async () => {
    servidor({ 'GET /api/auth/me': [200, SESION], 'GET /api/panel/consumidor': [200, { titulo: 'Tu mercado', descripcion: '' }] })
    abrirEn('/ingresar?volver=//sitio-malicioso.com')
    await waitFor(() => expect(window.location.pathname).toBe('/inicio'))
  })

  it.each([
    [404, 'Este producto no está disponible'],
    [500, 'No pudimos cargar el producto'],
  ])('si la ficha responde %i muestra un mensaje y la salida al catalogo', async (status, titulo) => {
    servidor({
      'GET /api/auth/me': SIN_SESION,
      'GET /api/productos/99': [status, { code: 'PRODUCTO_NO_ENCONTRADO' }],
      'GET /api/productos?pagina=0&tamano=12': pagina([ACEITE]),
    })
    const user = userEvent.setup()
    abrirEn('/productos/99')
    expect(await screen.findByText(titulo)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Volver al catálogo' }))
    expect(await screen.findByText('Aceite de ungurahui 250 ml')).toBeInTheDocument()
  })

  it('desde una tarjeta se abre la ficha y el enlace al productor lleva a su pagina', async () => {
    servidor({
      'GET /api/auth/me': SIN_SESION,
      'GET /api/productos?pagina=0&tamano=12': pagina([ACEITE]),
      'GET /api/productos/1': [200, FICHA],
    })
    const user = userEvent.setup()
    abrirEn('/')
    await user.click(await screen.findByRole('link', { name: /Aceite de ungurahui/ }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Aceite de ungurahui 250 ml' })).toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Comunidad Shipibo Konibo' }))
    expect(await screen.findByText('Muy pronto podrás conocer a esta comunidad')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/productores/1')
  })
})
