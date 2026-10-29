import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { abrirPestana, copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const FAVORITOS = 'GET /api/cuenta/favoritos'
const VACIOS = { productos: [], productores: [], comunidades: [] }
const tienda = copia(DATOS.tienda)

// Como los arma FavoritosService del backend: el ultimo guardado primero; sin oferta si ya no esta publicado
const GUARDADOS = {
  productos: [
    { id: 1, nombre: DATOS.detalle.nombre, guardadoEn: '2026-10-29T15:00:00Z', oferta: { ...copia(DATOS.buscar.items[0]), id: 1, nombre: DATOS.detalle.nombre } },
    { id: 40, nombre: 'Miel de abeja nativa', guardadoEn: '2026-10-28T15:00:00Z', oferta: null },
  ],
  productores: [
    { id: 2, nombre: tienda.nombre, guardadoEn: '2026-10-29T15:00:00Z', tienda: copia(DATOS.tiendas[0]) },
  ],
  comunidades: [
    { tiendaId: 2, comunidad: 'Comunidad Awajun del Alto Maranon', region: 'Amazonas', guardadoEn: '2026-10-29T15:00:00Z',
      tiendas: [{ ...copia(DATOS.tiendas[0]), id: 2, nombre: 'C.N. Yarina' }] },
  ],
}

const comprador = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  'GET /api/carrito': [200, copia(DATOS.carrito)],
  [FAVORITOS]: [200, VACIOS],
  ...rutas,
})

describe('CCPIP-177 y CCPIP-396 Guardar y retirar un producto de favoritos', () => {
  it('el consumidor lo guarda desde la ficha y lo vuelve a quitar', async () => {
    let favoritos = copia(VACIOS)
    const fetchMock = servidor(comprador({
      'GET /api/productos/1': [200, copia(DATOS.detalle)],
      [FAVORITOS]: () => [200, copia(favoritos)],
      'PUT /api/cuenta/favoritos/productos/1': () => { favoritos.productos = [{ id: 1, nombre: 'x', oferta: null }]; return [204, null] },
      'DELETE /api/cuenta/favoritos/productos/1': () => { favoritos.productos = []; return [204, null] },
    }))
    const user = userEvent.setup()
    abrirEn('/productos/1')

    const guardar = await screen.findByRole('button', { name: 'Guardar en favoritos' })
    expect(guardar).toHaveAttribute('aria-pressed', 'false')
    await user.click(guardar)
    const guardado = await screen.findByRole('button', { name: 'En tus favoritos' })
    expect(guardado).toHaveAttribute('aria-pressed', 'true')
    expect(llamadas(fetchMock, 'PUT /api/cuenta/favoritos/productos/1')).toHaveLength(1)

    await user.click(guardado)
    expect(await screen.findByRole('button', { name: 'Guardar en favoritos' })).toHaveAttribute('aria-pressed', 'false')
    expect(llamadas(fetchMock, 'DELETE /api/cuenta/favoritos/productos/1')).toHaveLength(1)
  })

  it('sin sesion lo lleva a ingresar y lo regresa a la ficha', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'GET /api/productos/1': [200, copia(DATOS.detalle)] })
    const user = userEvent.setup()
    abrirEn('/productos/1')

    await user.click(await screen.findByRole('button', { name: 'Guardar en favoritos' }))
    await waitFor(() => expect(window.location.pathname).toBe('/ingresar'))
    expect(new URLSearchParams(window.location.search).get('volver')).toBe('/productos/1')
    expect(llamadas(fetchMock, FAVORITOS)).toHaveLength(0)
  })

  it('a otros roles no se les ofrece', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_PRODUCTOR], 'GET /api/productos/1': [200, copia(DATOS.detalle)] })
    abrirEn('/productos/1')

    expect(await screen.findByRole('heading', { level: 1, name: DATOS.detalle.nombre })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Guardar en favoritos' })).not.toBeInTheDocument()
  })

  it('si el servidor lo rechaza, lo dice junto al boton', async () => {
    servidor(comprador({
      'GET /api/productos/1': [200, copia(DATOS.detalle)],
      'PUT /api/cuenta/favoritos/productos/1': [404, { code: 'PRODUCTO_NO_ENCONTRADO', error: 'El producto no existe o no está publicado' }],
    }))
    const user = userEvent.setup()
    abrirEn('/productos/1')

    await user.click(await screen.findByRole('button', { name: 'Guardar en favoritos' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('El producto no existe o no está publicado')
  })
})

describe('CCPIP-368, CCPIP-369, CCPIP-397 y CCPIP-398 Guardar el productor y su comunidad desde la tienda', () => {
  it('guarda el productor y la comunidad por separado', async () => {
    let favoritos = copia(VACIOS)
    const fetchMock = servidor(comprador({
      'GET /api/tiendas/2': [200, copia(tienda)],
      [FAVORITOS]: () => [200, copia(favoritos)],
      'PUT /api/cuenta/favoritos/comunidades/2': () => {
        favoritos.comunidades = [{ tiendaId: 2, comunidad: tienda.comunidad, region: tienda.region, tiendas: [{ id: 2 }] }]
        return [204, null]
      },
    }))
    const user = userEvent.setup()
    abrirEn('/tiendas/2')

    await user.click(await screen.findByRole('button', { name: 'Guardar comunidad' }))
    expect(await screen.findByRole('button', { name: 'Comunidad guardada' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Guardar productor' })).toHaveAttribute('aria-pressed', 'false')
    expect(llamadas(fetchMock, 'PUT /api/cuenta/favoritos/comunidades/2')).toHaveLength(1)
    expect(llamadas(fetchMock, 'PUT /api/cuenta/favoritos/productores/2')).toHaveLength(0)
  })

  it('reconoce la comunidad guardada desde otra de sus tiendas', async () => {
    servidor(comprador({
      'GET /api/tiendas/2': [200, copia(tienda)],
      [FAVORITOS]: [200, { ...VACIOS, comunidades: [{ tiendaId: 9, comunidad: tienda.comunidad, tiendas: [{ id: 9 }, { id: 2 }] }] }],
    }))
    abrirEn('/tiendas/2')

    expect(await screen.findByRole('button', { name: 'Comunidad guardada' })).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('CCPIP-327, CCPIP-385 y CCPIP-386 Mi cuenta › Favoritos', () => {
  it('muestra sus productos, productores y comunidades en pestañas con su conteo', async () => {
    servidor(comprador({ [FAVORITOS]: [200, copia(GUARDADOS)] }))
    const user = userEvent.setup()
    abrirEn('/cuenta/favoritos')

    expect(await screen.findByRole('heading', { level: 2, name: 'Favoritos' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Favoritos' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText(/No reserva productos ni registra cantidades/)).toBeInTheDocument()

    const productos = await abrirPestana(user, 'Productos')
    const items = within(productos.getByRole('list')).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0]).getByRole('link', { name: new RegExp(DATOS.detalle.nombre) })).toHaveAttribute('href', '/productos/1')
    // Lo que ya no esta publicado se ve marcado, para que pueda quitarlo
    expect(within(items[1]).getByText('Miel de abeja nativa')).toBeInTheDocument()
    expect(within(items[1]).getByText('Ya no está publicado.')).toBeInTheDocument()

    const productores = await abrirPestana(user, 'Productores')
    expect(productores.getByRole('link', { name: /Visitar/ })).toHaveAttribute('href', `/tiendas/${DATOS.tiendas[0].id}`)

    const comunidades = await abrirPestana(user, 'Comunidades')
    expect(comunidades.getByText('Comunidad Awajun del Alto Maranon')).toBeInTheDocument()
    expect(within(comunidades.getByRole('list', { name: 'Tiendas de Comunidad Awajun del Alto Maranon' })).getByRole('link', { name: 'C.N. Yarina' }))
      .toHaveAttribute('href', '/tiendas/2')
  })

  it('quita un favorito y la lista se actualiza', async () => {
    let favoritos = copia(GUARDADOS)
    const fetchMock = servidor(comprador({
      [FAVORITOS]: () => [200, copia(favoritos)],
      'DELETE /api/cuenta/favoritos/productos/40': () => { favoritos.productos = favoritos.productos.filter((p) => p.id !== 40); return [204, null] },
    }))
    const user = userEvent.setup()
    abrirEn('/cuenta/favoritos')

    await user.click(await screen.findByRole('button', { name: 'Quitar Miel de abeja nativa de favoritos' }))
    expect(await screen.findByText('Quitaste «Miel de abeja nativa» de tus favoritos.')).toBeInTheDocument()
    expect(await screen.findByRole('tab', { name: 'Productos (1)' })).toBeInTheDocument()
    expect(llamadas(fetchMock, 'DELETE /api/cuenta/favoritos/productos/40')).toHaveLength(1)
  })

  it('si no puede quitarlo, lo avisa y lo mantiene en la lista', async () => {
    servidor(comprador({
      [FAVORITOS]: [200, copia(GUARDADOS)],
      'DELETE /api/cuenta/favoritos/productores/2': [500, { code: 'ERROR' }],
    }))
    const user = userEvent.setup()
    abrirEn('/cuenta/favoritos')

    const productores = await abrirPestana(user, 'Productores')
    await user.click(productores.getByRole('button', { name: `Quitar ${tienda.nombre} de favoritos` }))
    expect(await screen.findByText('No pudimos quitarlo de tus favoritos')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Productores (1)' })).toBeInTheDocument()
  })

  it('si no puede cargarlos, permite reintentar', async () => {
    servidor(comprador({ [FAVORITOS]: new TypeError('Failed to fetch') }))
    abrirEn('/cuenta/favoritos')
    expect(await screen.findByText('No pudimos cargar tus favoritos')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })

  it('sin favoritos invita a explorar', async () => {
    servidor(comprador())
    abrirEn('/cuenta/favoritos')
    expect(await screen.findByText('Aún no guardaste productos')).toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    try {
      servidor(comprador({ [FAVORITOS]: [200, copia(GUARDADOS)] }))
      abrirEn('/cuenta/favoritos')
      expect(await screen.findByRole('tab', { name: 'Products (2)' })).toBeInTheDocument()
      expect(screen.getByRole('tab', { name: 'Communities (1)' })).toBeInTheDocument()
      expect(screen.getByText('No longer published.')).toBeInTheDocument()
    } finally {
      await i18n.changeLanguage('es')
    }
  })
})
