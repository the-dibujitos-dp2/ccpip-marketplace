import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR, SIN_SESION } from '../../test/servidor.js'
import { escribirFiltros, leerFiltros } from './BuscarPage.jsx'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const sinSesion = { ...PUBLICAS, 'GET /api/auth/me': SIN_SESION }

/** URL de la ultima busqueda pedida al servidor. */
function ultimaBusqueda(fetchMock) {
  const urls = fetchMock.mock.calls.map(([url]) => url).filter((u) => u.startsWith('/api/productos?'))
  return new URLSearchParams(urls.at(-1).split('?')[1])
}

describe('CW-01 Inicio de la tienda', () => {
  it('muestra comunidades, lineas con su total y productos para descubrir', async () => {
    servidor(sinSesion)
    abrirEn('/')
    expect(await screen.findByRole('heading', { name: /Productos con historia/ })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Conoce a quienes lo hacen' })).toBeInTheDocument()
    for (const t of DATOS.inicio.tiendas) expect(screen.getAllByText(t.nombre).length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: 'Explora por línea' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Productos para descubrir' })).toBeInTheDocument()
  })

  it('las pestanas cambian entre destacados y novedades', async () => {
    servidor(sinSesion)
    const user = userEvent.setup()
    abrirEn('/')
    const novedades = await screen.findByRole('tab', { name: 'Novedades' })
    await user.click(novedades)
    expect(novedades).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText(DATOS.inicio.novedades[0].nombre)).toBeInTheDocument()
  })

  it('si el servidor falla lo dice, sin pantalla en blanco', async () => {
    servidor({ ...sinSesion, 'GET /api/inicio': [500, {}] })
    abrirEn('/')
    expect(await screen.findByText(/No pudimos cargar/)).toBeInTheDocument()
  })

  it('la busqueda de la cabecera lleva a CW-02 con el termino', async () => {
    servidor(sinSesion)
    const user = userEvent.setup()
    abrirEn('/')
    await user.type(await screen.findByRole('searchbox', { name: 'Buscar productos' }), 'cacao{Enter}')
    expect(window.location.pathname).toBe('/buscar')
    expect(window.location.search).toBe('?q=cacao')
    expect(await screen.findByRole('heading', { name: 'Resultados para “cacao”' })).toBeInTheDocument()
  })

  it('una ruta desconocida o un enlace antiguo de productor se redirigen', async () => {
    servidor({ ...sinSesion, 'GET /api/tiendas/2': [200, copia(DATOS.tienda)] })
    abrirEn('/productores/2')
    await waitFor(() => expect(window.location.pathname).toBe('/tiendas/2'))
    act(() => {
      window.history.pushState(null, '', '/no-existe')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await waitFor(() => expect(window.location.pathname).toBe('/'))
  })
})

describe('CW-02 Catalogo y busqueda (CCPIP-52, 54, 165)', () => {
  it('lee y escribe los filtros en la URL', () => {
    const f = leerFiltros('?q=cafe&linea=CAFE&linea=CACAO&region=Junin&modalidad=MAYORISTA&tienda=3&precioMin=10&precioMax=40&disponibles=false&orden=precio_asc&pagina=2')
    expect(f).toMatchObject({ q: 'cafe', lineas: ['CAFE', 'CACAO'], regiones: ['Junin'], disponibles: false, orden: 'PRECIO_ASC', pagina: 2 })
    expect(leerFiltros(escribirFiltros(f).split('?')[1] ? `?${escribirFiltros(f).split('?')[1]}` : '')).toEqual(f)
    expect(escribirFiltros(leerFiltros(''))).toBe('/buscar')
    expect(leerFiltros('?pagina=-3').pagina).toBe(0)
  })

  it('muestra resultados, cuantas comunidades y las facetas con su total', async () => {
    servidor(sinSesion)
    abrirEn('/buscar?q=cacao')
    expect(await screen.findByText('2 productos de 1 comunidad')).toBeInTheDocument()
    const productos = screen.getByRole('list', { name: 'Productos' })
    expect(within(productos).getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByRole('checkbox', { name: /Cacao y chocolates/ })).not.toBeChecked()
    expect(screen.getByRole('switch', { name: 'Mostrar solo disponibles' })).toBeChecked()
  })

  it('cada filtro se aplica, aparece como chip y se puede quitar', async () => {
    const fetchMock = servidor(sinSesion)
    const user = userEvent.setup()
    abrirEn('/buscar?q=cacao')
    await user.click(await screen.findByRole('checkbox', { name: /Cacao y chocolates/ }))
    await waitFor(() => expect(ultimaBusqueda(fetchMock).getAll('linea')).toEqual(['CACAO']))
    await user.click(await screen.findByRole('checkbox', { name: /Amazonas/ }))
    await user.click(await screen.findByRole('checkbox', { name: /Minorista/ }))
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Comunidad o pueblo' }), '2')
    await waitFor(() => expect(ultimaBusqueda(fetchMock).get('tienda')).toBe('2'))
    const busqueda = ultimaBusqueda(fetchMock)
    expect(busqueda.getAll('region')).toEqual(['Amazonas'])
    expect(busqueda.get('modalidad')).toBe('MINORISTA')
    expect(busqueda.get('q')).toBe('cacao')

    const activos = await screen.findByRole('list', { name: 'Filtros activos' })
    expect(within(activos).getAllByRole('button')).toHaveLength(4)
    await user.click(screen.getByRole('button', { name: 'Quitar filtro C.N. Yarina' }))
    await waitFor(() => expect(ultimaBusqueda(fetchMock).get('tienda')).toBeNull())

    await user.click(screen.getByRole('button', { name: 'Limpiar' }))
    await waitFor(() => expect(window.location.search).toBe('?q=cacao'))
  })

  it('el rango de precio se aplica al dejar de escribir y el orden al elegirlo', async () => {
    const fetchMock = servidor(sinSesion)
    const user = userEvent.setup()
    abrirEn('/buscar')
    await user.type(await screen.findByLabelText('Precio mínimo'), '10')
    await user.type(screen.getByLabelText('Precio máximo'), '40')
    await waitFor(() => expect(ultimaBusqueda(fetchMock).get('precioMax')).toBe('40'), { timeout: 2000 })
    expect(ultimaBusqueda(fetchMock).get('precioMin')).toBe('10')
    expect(await screen.findByRole('button', { name: 'Quitar filtro S/ 10 – 40' })).toBeInTheDocument()

    await user.selectOptions(screen.getByRole('combobox', { name: 'Ordenar' }), 'PRECIO_ASC')
    await waitFor(() => expect(ultimaBusqueda(fetchMock).get('orden')).toBe('PRECIO_ASC'))
    expect(window.location.search).toContain('orden=precio_asc')

    await user.click(screen.getByRole('switch', { name: 'Mostrar solo disponibles' }))
    await waitFor(() => expect(ultimaBusqueda(fetchMock).get('disponibles')).toBe('false'))
  })

  it('pagina los resultados', async () => {
    const muchos = { ...copia(DATOS.buscar), total: 30, totalPaginas: 3 }
    const fetchMock = servidor({ ...sinSesion, 'GET /api/productos': () => [200, copia(muchos)] })
    const user = userEvent.setup()
    window.scrollTo = vi.fn()
    abrirEn('/buscar')
    await user.click(await screen.findByRole('button', { name: 'Página 2' }))
    await waitFor(() => expect(ultimaBusqueda(fetchMock).get('pagina')).toBe('1'))
    expect(window.location.search).toBe('?pagina=1')
  })

  it('sin resultados sugiere otra busqueda y si falla la red lo dice', async () => {
    const vacio = { ...copia(DATOS.buscar), items: [], total: 0, totalPaginas: 0, totalComunidades: 0 }
    servidor({ ...sinSesion, 'GET /api/productos': [200, vacio] })
    abrirEn('/buscar?q=zzz')
    expect(await screen.findByText('No encontramos productos para “zzz”')).toBeInTheDocument()
  })

  it('si falla la red muestra el error', async () => {
    servidor({ ...sinSesion, 'GET /api/productos': new TypeError('Failed to fetch') })
    abrirEn('/buscar')
    expect(await screen.findByText('No pudimos cargar el catálogo')).toBeInTheDocument()
  })
})

describe('CW-03 Ficha del producto (CCPIP-53, 152)', () => {
  const conTodo = {
    ...copia(DATOS.detalle),
    modalidad: 'AMBAS',
    precioMayorista: 35,
    cantidadMinimaMayorista: 12,
    contenido: '1 kg',
    imagenes: [
      { id: 'a', url: '/api/imagenes/a', miniatura: '/api/imagenes/a?tam=min' },
      { id: 'b', url: '/api/imagenes/b', miniatura: '/api/imagenes/b?tam=min' },
    ],
    historia: {
      titulo: 'Del bosque a tu mesa',
      texto: 'Lo cosechamos a mano.',
      asistida: true,
      partes: { comunidad: 'somos 20 familias', cultivo: 'bajo sombra', proceso: 'fermentado seis dias' },
    },
  }

  it('muestra precios por modalidad, la historia con sus partes y la cosecha sin codigo de lote', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1': [200, conTodo] })
    const user = userEvent.setup()
    abrirEn('/productos/1')
    expect(await screen.findByRole('heading', { level: 1, name: conTodo.nombre })).toBeInTheDocument()
    const mayorista = screen.getByRole('radio', { name: /Precio mayorista/ })
    await user.click(mayorista)
    expect(mayorista).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('desde 12 und')).toBeInTheDocument()

    expect(screen.getByRole('heading', { name: 'Del bosque a tu mesa' })).toBeInTheDocument()
    expect(screen.getByText('Somos 20 familias')).toBeInTheDocument()
    expect(screen.getByText(/redactado con apoyo de inteligencia artificial/)).toBeInTheDocument()
    expect(screen.getByText('Consumir antes de')).toBeInTheDocument()
    expect(screen.queryByText(/L-000/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Ver foto 2' }))
    expect(screen.getByText('2 / 2')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Para compras por volumen' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'También de cacao y chocolates' })).toBeInTheDocument()
  })

  it('un producto agotado lo indica y uno retirado responde que no esta disponible', async () => {
    servidor({
      ...sinSesion,
      'GET /api/productos/1': [200, { ...copia(DATOS.detalle), disponible: false, historia: null, cosecha: null }],
      'GET /api/productos/99': [404, { code: 'PRODUCTO_NO_ENCONTRADO' }],
    })
    abrirEn('/productos/1')
    expect(await screen.findByText('Agotado por ahora')).toBeInTheDocument()
    act(() => {
      window.history.pushState(null, '', '/productos/99')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(await screen.findByText('Este producto no está disponible')).toBeInTheDocument()
  })

  it('un error de red ofrece volver al catalogo', async () => {
    servidor({ ...sinSesion, 'GET /api/productos/1': new TypeError('Failed to fetch') })
    abrirEn('/productos/1')
    expect(await screen.findByText('No pudimos cargar el producto')).toBeInTheDocument()
  })
})

describe('CW-04 Tienda de la comunidad (CCPIP-56, 154)', () => {
  it('muestra la historia, cifras y filtra sus productos por linea', async () => {
    servidor({ ...sinSesion, 'GET /api/tiendas/2': [200, copia(DATOS.tienda)] })
    const user = userEvent.setup()
    abrirEn('/tiendas/2')
    expect(await screen.findByRole('heading', { level: 1, name: DATOS.tienda.nombre })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Nuestra historia' })).toBeInTheDocument()
    const lista = screen.getByRole('list', { name: `Productos de ${DATOS.tienda.nombre}` })
    expect(within(lista).getAllByRole('listitem')).toHaveLength(DATOS.tienda.productos.length)

    const linea = DATOS.tienda.lineas[0]
    await user.click(screen.getByRole('tab', { name: `${linea.nombre} (${linea.total})` }))
    expect(within(lista).getAllByRole('listitem')).toHaveLength(linea.total)
  })

  it('compartir copia el enlace cuando no hay menu nativo', async () => {
    servidor({ ...sinSesion, 'GET /api/tiendas/2': [200, copia(DATOS.tienda)] })
    const user = userEvent.setup()
    const writeText = vi.fn().mockResolvedValue()
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    abrirEn('/tiendas/2')
    await user.click(await screen.findByRole('button', { name: 'Compartir' }))
    expect(await screen.findByRole('button', { name: 'Enlace copiado' })).toBeInTheDocument()
    expect(writeText).toHaveBeenCalledWith(window.location.href)
  })

  it('una tienda con galeria y destacados los muestra; una sin productos lo dice', async () => {
    const conGaleria = {
      ...copia(DATOS.tienda),
      galeria: [{ id: 'g', url: '/api/imagenes/g', miniatura: '/api/imagenes/g?tam=min' }],
      productos: DATOS.tienda.productos.map((p, i) => ({ ...p, destacado: i === 0 })),
    }
    servidor({
      ...sinSesion,
      'GET /api/tiendas/2': [200, conGaleria],
      'GET /api/tiendas/3': [200, { ...copia(DATOS.tienda), id: 3, productos: [], lineas: [] }],
    })
    abrirEn('/tiendas/2')
    expect(await screen.findByRole('heading', { name: 'Galería' })).toBeInTheDocument()
    act(() => {
      window.history.pushState(null, '', '/tiendas/3')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    expect(await screen.findByText('Aún no hay productos publicados')).toBeInTheDocument()
  })

  it('una tienda que no existe o no esta publicada responde 404', async () => {
    servidor({ ...sinSesion, 'GET /api/tiendas/9': [404, { code: 'TIENDA_NO_ENCONTRADA' }] })
    abrirEn('/tiendas/9')
    expect(await screen.findByText(/no está disponible/)).toBeInTheDocument()
  })
})

describe('Comunidades (CCPIP-153)', () => {
  it('lista las tiendas publicadas', async () => {
    servidor(sinSesion)
    abrirEn('/comunidades')
    for (const t of DATOS.tiendas) expect(await screen.findByText(t.nombre)).toBeInTheDocument()
  })

  it('si falla lo dice', async () => {
    servidor({ ...sinSesion, 'GET /api/tiendas': [500, {}] })
    abrirEn('/comunidades')
    expect(await screen.findByText(/No pudimos cargar/)).toBeInTheDocument()
  })
})

describe('Cabecera con sesion', () => {
  it('el menu de cuenta lleva al panel del productor y se cierra con Escape', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_PRODUCTOR] })
    const user = userEvent.setup()
    abrirEn('/')
    await user.click(await screen.findByRole('button', { name: 'Mi cuenta' }))
    expect(screen.getByRole('menuitem', { name: 'Panel del productor' })).toHaveAttribute('href', '/productor')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('las lineas de la cabecera llevan al catalogo filtrado', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION] })
    const user = userEvent.setup()
    abrirEn('/')
    const lineas = await screen.findByRole('navigation', { name: 'Líneas de producto' })
    await user.click(within(lineas).getByRole('link', { name: 'Café' }))
    await waitFor(() => expect(ultimaBusqueda(fetchMock).getAll('linea')).toEqual(['CAFE']))
    expect(llamadas(fetchMock, 'GET /api/catalogos').length).toBeGreaterThan(0)
  })
})
