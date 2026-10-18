import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

const LINEAS = '/api/admin/catalogo/lineas'
const PRODUCTOS = '/api/admin/catalogo/productos'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const comoSuper = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUPER],
  'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
  'GET /api/admin/autorizaciones': () => [200, copia(DATOS.autorizacionesAdmin)],
  [`GET ${LINEAS}`]: () => [200, copia(DATOS.lineasMaestras)],
  [`GET ${PRODUCTOS}`]: () => [200, copia(DATOS.productosMaestros)],
  ...rutas,
})

const cuerpo = (llamada) => JSON.parse(llamada[1].body)
const fila = (nombre) => screen.getByText(nombre, { selector: '.label-m' }).closest('[role="row"]')
const dialogo = (nombre) => within(screen.getByRole('dialog', { name: nombre }))

function lineaCon(cambios) {
  return { ...copia(DATOS.lineasMaestras.lineas[1]), ...cambios }
}

// Formularios con mucho tipeo: margen extra cuando la suite corre en paralelo
const LENTA = { timeout: 15000 }

describe('Catálogo maestro: líneas (CCPIP-96, 309, 310, 311)', LENTA, () => {
  it('el superadministrador consulta las líneas con sus productos, ofertas y estado', async () => {
    servidor(comoSuper())
    abrirEn('/admin/catalogo')

    expect(await screen.findByRole('heading', { level: 1, name: 'Catálogo maestro' })).toBeInTheDocument()
    expect(screen.getByText(/No son la oferta comercial/)).toBeInTheDocument()
    const nav = within(screen.getByRole('navigation', { name: 'Super administrador' }))
    expect(nav.getByRole('link', { name: 'Catálogo maestro' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('tab', { name: 'Líneas' })).toHaveAttribute('aria-selected', 'true')

    const tabla = within(await screen.findByRole('table', { name: 'Líneas' }))
    expect(tabla.getAllByRole('row')).toHaveLength(8)
    const cafe = within(fila('Café'))
    expect(cafe.getByText('CAFE · Coffee')).toBeInTheDocument()
    expect(cafe.getByText('Vence')).toBeInTheDocument()
    expect(cafe.getByText('Bolsa')).toBeInTheDocument()
    expect(cafe.getByText('Activo')).toBeInTheDocument()
    expect(within(fila('Artesanías')).getByText('No vence')).toBeInTheDocument()
    expect(screen.getByText('7 activos · 0 inactivos')).toBeInTheDocument()
  })

  it('filtra por estado y nombre en el servidor', async () => {
    const fetchMock = servidor(comoSuper({
      [`GET ${LINEAS}?estado=INACTIVOS`]: [200, { contadores: { activos: 6, inactivos: 1 }, lineas: [] }],
    }))
    abrirEn('/admin/catalogo')
    await screen.findByRole('table', { name: 'Líneas' })

    await userEvent.selectOptions(screen.getByLabelText('Estado'), 'INACTIVOS')
    expect(await screen.findByText('No hay líneas con estos filtros')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Buscar por nombre'), 'cafe')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))
    await waitFor(() => expect(llamadas(fetchMock, `GET ${LINEAS}?estado=INACTIVOS&q=cafe`)).toHaveLength(1))
  })

  it('crea una línea: valida en la pantalla y envía los datos al servidor', async () => {
    const nueva = lineaCon({ id: 8, codigo: 'MIEL', nombre: 'Miel', nombreEn: 'Honey', orden: 8, productos: 0, ofertas: 0 })
    const fetchMock = servidor(comoSuper({ [`POST ${LINEAS}`]: [201, nueva] }))
    abrirEn('/admin/catalogo')
    await userEvent.click(await screen.findByRole('button', { name: 'Nueva línea' }))

    const d = dialogo('Nueva línea')
    await userEvent.type(d.getByLabelText('Código'), 'con espacio')
    await userEvent.click(d.getByRole('button', { name: 'Crear' }))
    expect(d.getByText('Usa de 2 a 40 letras, números o guion bajo, sin espacios.')).toBeInTheDocument()
    expect(d.getByText('Escribe el nombre.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${LINEAS}`)).toHaveLength(0)

    await userEvent.clear(d.getByLabelText('Código'))
    await userEvent.type(d.getByLabelText('Código'), 'MIEL')
    await userEvent.type(d.getByLabelText('Nombre'), 'Miel')
    await userEvent.type(d.getByLabelText('Nombre en inglés (opcional)'), 'Honey')
    await userEvent.selectOptions(d.getByLabelText('Unidad sugerida'), 'FRASCO')
    await userEvent.click(d.getByLabelText('Sus lotes tienen fecha de vencimiento'))
    await userEvent.click(d.getByRole('button', { name: 'Crear' }))

    expect(await screen.findByText('Línea Miel creada.')).toBeInTheDocument()
    expect(cuerpo(llamadas(fetchMock, `POST ${LINEAS}`)[0])).toEqual({
      codigo: 'MIEL', nombre: 'Miel', nombreEn: 'Honey', requiereVencimiento: false, unidadSugerida: 'FRASCO', orden: null,
    })
    expect(llamadas(fetchMock, `GET ${LINEAS}?estado=TODOS`).length).toBeGreaterThanOrEqual(2)
  })

  it('muestra en su campo el código repetido que rechaza el servidor', async () => {
    servidor(comoSuper({
      [`POST ${LINEAS}`]: [409, { code: 'CODIGO_DUPLICADO', error: 'Ya existe una línea con el código CAFE.' }],
    }))
    abrirEn('/admin/catalogo')
    await userEvent.click(await screen.findByRole('button', { name: 'Nueva línea' }))
    const d = dialogo('Nueva línea')
    await userEvent.type(d.getByLabelText('Código'), 'CAFE')
    await userEvent.type(d.getByLabelText('Nombre'), 'Café de altura')
    await userEvent.click(d.getByRole('button', { name: 'Crear' }))
    expect(await d.findByText('Ya existe una línea con el código CAFE.')).toBeInTheDocument()
    expect(d.getByLabelText('Código')).toHaveAttribute('aria-invalid', 'true')
  })

  it('modifica una línea sin cambiar su código', async () => {
    const fetchMock = servidor(comoSuper({
      [`PUT ${LINEAS}/2`]: [200, lineaCon({ nombre: 'Cafés especiales', nombreEn: null })],
    }))
    abrirEn('/admin/catalogo')
    await screen.findByRole('table', { name: 'Líneas' })
    await userEvent.click(screen.getByRole('button', { name: 'Editar Café' }))

    const d = dialogo('Editar línea')
    expect(d.getByLabelText('Código')).toBeDisabled()
    expect(d.getByLabelText('Código')).toHaveValue('CAFE')
    expect(d.getByLabelText('Orden en el catálogo')).toHaveValue('2')
    await userEvent.clear(d.getByLabelText('Nombre'))
    await userEvent.type(d.getByLabelText('Nombre'), 'Cafés especiales')
    await userEvent.clear(d.getByLabelText('Nombre en inglés (opcional)'))
    await userEvent.click(d.getByRole('button', { name: 'Guardar cambios' }))

    expect(await screen.findByText('Cambios guardados en Cafés especiales.')).toBeInTheDocument()
    expect(cuerpo(llamadas(fetchMock, `PUT ${LINEAS}/2`)[0])).toEqual({
      codigo: 'CAFE', nombre: 'Cafés especiales', nombreEn: null, requiereVencimiento: true, unidadSugerida: 'BOLSA', orden: 2,
    })
  })

  it('desactiva una línea tras confirmar que se conservan sus ofertas', async () => {
    const fetchMock = servidor(comoSuper({
      [`POST ${LINEAS}/2/desactivar`]: [200, lineaCon({ activa: false, desactivadaEn: '2026-10-17T06:00:00Z' })],
    }))
    abrirEn('/admin/catalogo')
    await screen.findByRole('table', { name: 'Líneas' })
    await userEvent.click(screen.getByRole('button', { name: 'Desactivar Café' }))

    const d = dialogo('¿Desactivar la línea Café?')
    expect(d.getByText(/Se conservan sus 2 productos maestros y 2 ofertas, con sus pedidos e historial/)).toBeInTheDocument()
    await userEvent.click(d.getByRole('button', { name: 'Desactivar' }))

    expect(await screen.findByText('Línea Café desactivada. Se conservan sus 2 ofertas.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${LINEAS}/2/desactivar`)).toHaveLength(1)
  })

  it('reactiva una línea inactiva', async () => {
    const lineas = copia(DATOS.lineasMaestras)
    lineas.lineas[1].activa = false
    const fetchMock = servidor(comoSuper({
      [`GET ${LINEAS}`]: [200, lineas],
      [`POST ${LINEAS}/2/reactivar`]: [200, lineaCon({})],
    }))
    abrirEn('/admin/catalogo')
    expect(await within(await screen.findByRole('table', { name: 'Líneas' })).findByText('Inactivo')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Reactivar Café' }))
    await userEvent.click(dialogo('¿Reactivar la línea Café?').getByRole('button', { name: 'Reactivar' }))
    expect(await screen.findByText('Línea Café reactivada.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${LINEAS}/2/reactivar`)).toHaveLength(1)
  })

  it('avisa si no se pudo cambiar el estado o cargar el catálogo', async () => {
    servidor(comoSuper({
      [`POST ${LINEAS}/2/desactivar`]: [409, { code: 'LINEA_YA_INACTIVA', error: 'La línea ya está desactivada.' }],
    }))
    abrirEn('/admin/catalogo')
    await screen.findByRole('table', { name: 'Líneas' })
    await userEvent.click(screen.getByRole('button', { name: 'Desactivar Café' }))
    await userEvent.click(dialogo('¿Desactivar la línea Café?').getByRole('button', { name: 'Desactivar' }))
    expect(await screen.findByText('No pudimos cambiar el estado')).toBeInTheDocument()
    expect(screen.getByText('La línea ya está desactivada.')).toBeInTheDocument()
  })

  it('el sub administrador no ve ni abre el catálogo maestro', async () => {
    const fetchMock = servidor(comoSuper({ 'GET /api/auth/me': [200, SESION_SUB] }))
    abrirEn('/admin/catalogo')
    expect(await screen.findByRole('heading', { level: 1, name: 'Autorizaciones de productos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/admin/autorizaciones')
    expect(screen.queryByRole('link', { name: 'Catálogo maestro' })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${LINEAS}?estado=TODOS`)).toHaveLength(0)
  })
})

describe('Catálogo maestro: productos (CCPIP-351, 312, 313, 314)', LENTA, () => {
  it('consulta los productos de referencia, sin precio, con sus ofertas', async () => {
    servidor(comoSuper())
    abrirEn('/admin/catalogo?vista=productos')

    expect(await screen.findByRole('tab', { name: 'Productos' })).toHaveAttribute('aria-selected', 'true')
    const tabla = within(await screen.findByRole('table', { name: 'Productos' }))
    expect(tabla.getAllByRole('row')).toHaveLength(11)
    const cafe = within(fila('Café molido'))
    expect(cafe.getByText('CAFE-MOLIDO · Café de altura tostado y molido.')).toBeInTheDocument()
    expect(cafe.getByText('Café')).toBeInTheDocument()
    expect(cafe.getByText('1 · 1 publicadas')).toBeInTheDocument()
    // Dos comunidades ofrecen el mismo producto maestro (CCPIP-42)
    expect(within(fila('Cacao en grano fermentado')).getByText('2 · 2 publicadas')).toBeInTheDocument()
    expect(screen.queryByText(/S\//)).not.toBeInTheDocument()
  })

  it('cambia de pestaña y filtra por línea en el servidor', async () => {
    const fetchMock = servidor(comoSuper())
    abrirEn('/admin/catalogo')
    await screen.findByRole('table', { name: 'Líneas' })
    await userEvent.click(screen.getByRole('tab', { name: 'Productos' }))
    expect(window.location.search).toBe('?vista=productos')
    await screen.findByRole('table', { name: 'Productos' })

    await userEvent.selectOptions(await screen.findByLabelText('Línea'), 'CAFE')
    await waitFor(() => expect(llamadas(fetchMock, `GET ${PRODUCTOS}?estado=TODOS&linea=CAFE`)).toHaveLength(1))
  })

  it('crea un producto en una línea activa', async () => {
    const lineas = copia(DATOS.lineasMaestras)
    lineas.lineas[6].activa = false
    const creado = { ...copia(DATOS.productosMaestros.productos[0]), id: 11, nombre: 'Chocolate al 70 %', ofertas: 0, ofertasPublicadas: 0 }
    const fetchMock = servidor(comoSuper({ [`GET ${LINEAS}`]: [200, lineas], [`POST ${PRODUCTOS}`]: [201, creado] }))
    abrirEn('/admin/catalogo?vista=productos')
    await userEvent.click(await screen.findByRole('button', { name: 'Nuevo producto' }))

    const d = dialogo('Nuevo producto')
    await userEvent.click(d.getByRole('button', { name: 'Crear' }))
    expect(d.getByText('Usa de 2 a 40 letras, números, guion o guion bajo, sin espacios.')).toBeInTheDocument()
    expect(d.getByText('Escribe el nombre.')).toBeInTheDocument()
    expect(d.getByText('Elige la línea.')).toBeInTheDocument()
    // Una línea inactiva no se ofrece para productos nuevos
    expect(within(d.getByLabelText('Línea')).queryByRole('option', { name: /Textiles/ })).not.toBeInTheDocument()

    await userEvent.type(d.getByLabelText('Código'), 'CHOCOLATE-70')
    await userEvent.type(d.getByLabelText('Nombre genérico'), 'Chocolate al 70 %')
    await userEvent.selectOptions(d.getByLabelText('Línea'), 'CACAO')
    await userEvent.click(d.getByRole('button', { name: 'Crear' }))

    expect(await screen.findByText('Producto Chocolate al 70 % creado.')).toBeInTheDocument()
    expect(cuerpo(llamadas(fetchMock, `POST ${PRODUCTOS}`)[0])).toEqual({
      codigo: 'CHOCOLATE-70', nombre: 'Chocolate al 70 %', descripcion: null, linea: 'CACAO',
    })
  })

  it('modifica un producto avisando que sus ofertas y pedidos no cambian', async () => {
    const productos = copia(DATOS.productosMaestros)
    productos.productos[2].linea.activa = false
    const lineas = copia(DATOS.lineasMaestras)
    lineas.lineas[1].activa = false
    const fetchMock = servidor(comoSuper({
      [`GET ${LINEAS}`]: [200, lineas],
      [`GET ${PRODUCTOS}`]: [200, productos],
      [`PUT ${PRODUCTOS}/9`]: [409, { code: 'PRODUCTO_DUPLICADO', error: 'Ya existe un producto con ese nombre en la línea.' }],
    }))
    abrirEn('/admin/catalogo?vista=productos')
    await screen.findByRole('table', { name: 'Productos' })
    expect(within(fila('Café molido')).getByText('Línea inactiva')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Editar Café molido' }))

    const d = dialogo('Editar producto')
    expect(d.getByText('Los cambios no modifican sus 1 ofertas ni los pedidos que ya existen.')).toBeInTheDocument()
    // Conserva su línea aunque esté inactiva
    expect(d.getByLabelText('Línea')).toHaveValue('CAFE')
    expect(within(d.getByLabelText('Línea')).getByRole('option', { name: 'Café (inactiva)' })).toBeInTheDocument()
    expect(d.getByLabelText('Código')).toBeDisabled()
    await userEvent.clear(d.getByLabelText('Nombre genérico'))
    await userEvent.type(d.getByLabelText('Nombre genérico'), 'Café tostado en grano')
    await userEvent.click(d.getByRole('button', { name: 'Guardar cambios' }))

    expect(await d.findByText('Ya existe un producto con ese nombre en la línea.')).toBeInTheDocument()
    expect(cuerpo(llamadas(fetchMock, `PUT ${PRODUCTOS}/9`)[0])).toEqual({
      codigo: 'CAFE-MOLIDO', nombre: 'Café tostado en grano', descripcion: 'Café de altura tostado y molido.', linea: 'CAFE',
    })
  })

  it('desactiva un producto conservando sus ofertas', async () => {
    const desactivado = { ...copia(DATOS.productosMaestros.productos[5]), activo: false }
    const fetchMock = servidor(comoSuper({ [`POST ${PRODUCTOS}/2/desactivar`]: [200, desactivado] }))
    abrirEn('/admin/catalogo?vista=productos')
    await screen.findByRole('table', { name: 'Productos' })
    await userEvent.click(screen.getByRole('button', { name: 'Desactivar Aceite de aguaje' }))

    const d = dialogo('¿Desactivar el producto Aceite de aguaje?')
    expect(d.getByText(/Sus 1 ofertas \(1 publicadas\) siguen igual, con sus pedidos e historial/)).toBeInTheDocument()
    await userEvent.click(d.getByRole('button', { name: 'Desactivar' }))
    expect(await screen.findByText('Producto Aceite de aguaje desactivado. Se conservan sus 1 ofertas.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${PRODUCTOS}/2/desactivar`)).toHaveLength(1)
  })

  it('en inglés traduce la pantalla y los errores del servidor por su código', async () => {
    servidor(comoSuper({
      [`POST ${PRODUCTOS}/2/desactivar`]: [409, { code: 'PRODUCTO_YA_INACTIVO', error: 'El producto ya está desactivado.' }],
    }))
    await i18n.changeLanguage('en')
    abrirEn('/admin/catalogo?vista=productos')
    expect(await screen.findByRole('heading', { level: 1, name: 'Master catalog' })).toBeInTheDocument()
    await screen.findByRole('table', { name: 'Products' })
    await userEvent.click(screen.getByRole('button', { name: 'Deactivate Aceite de aguaje' }))
    await userEvent.click(dialogo('Deactivate the product Aceite de aguaje?').getByRole('button', { name: 'Deactivate' }))
    expect(await screen.findByText('The product is already deactivated.')).toBeInTheDocument()
  })
})
