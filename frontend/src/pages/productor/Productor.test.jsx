import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR } from '../../test/servidor.js'
import { aCuerpo, validarDatos } from './PasoDatos.jsx'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

/** Backend minimo del productor; cada prueba agrega o reemplaza rutas. */
function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/panel': () => [200, copia(DATOS.panelProductor)],
    'GET /api/productor/intereses': () => [200, []],
    'GET /api/productor/productos': () => [200, copia(DATOS.productosProductor)],
    'GET /api/productor/productos/9': () => [200, copia(DATOS.edicion)],
    'GET /api/productor/productos/9/lotes': () => [200, copia(DATOS.lotes)],
    'GET /api/productor/lotes': () => [200, copia(DATOS.todosLosLotes)],
    'GET /api/productor/ubicaciones': () => [200, copia(DATOS.ubicaciones)],
    ...rutas,
  })
}

const cuerpo = (fetchMock, clave) => JSON.parse(llamadas(fetchMock, clave).at(-1)[1].body)
// CCPIP-49: borrador de descripcion que propone la IA y ruta para aceptarlo en el producto 9
const SUGERIDA = 'Aceite prensado en frío por mujeres shipibo.'
const ACEPTAR_9 = 'POST /api/productor/productos/9/ia/descripcion/aceptar'
const foto = (nombre = 'foto.jpg', bytes = 10) => new File([new Uint8Array(bytes)], nombre, { type: 'image/jpeg' })

describe('Acceso al panel del productor', () => {
  it('otro rol que entra a /productor vuelve a su inicio', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION], 'GET /api/panel/consumidor': [200, { titulo: 'Tu mercado' }] })
    abrirEn('/productor/productos')
    await waitFor(() => expect(window.location.pathname).toBe('/inicio'))
  })

  it('la barra lateral marca la seccion y la busqueda superior lleva a sus productos', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor')
    expect(await screen.findByText('Asoc. Nii Joi / Inicio')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Inicio' })).toHaveAttribute('aria-current', 'page')
    await user.type(screen.getByRole('searchbox', { name: 'Buscar en mis productos' }), 'camu{Enter}')
    expect(window.location.pathname).toBe('/productor/productos')
    await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => u.includes('q=camu'))).toBe(true))
  })

  it('cierra sesion desde la barra lateral', async () => {
    productor({ 'POST /api/auth/logout': [204] })
    const user = userEvent.setup()
    abrirEn('/productor')
    await user.click(await screen.findByRole('button', { name: 'Cerrar sesión' }))
    expect(await screen.findByText('Cerraste sesión')).toBeInTheDocument()
  })

  it('mientras cierra sesion, el boton de la barra lateral muestra el aro de carga (CCPIP-413)', async () => {
    let responder
    productor({ 'POST /api/auth/logout': () => new Promise((r) => { responder = r }) })
    const user = userEvent.setup()
    abrirEn('/productor')
    const salir = await screen.findByRole('button', { name: 'Cerrar sesión' })
    expect(salir.querySelector('.boton__giro')).toBeNull()
    await user.click(salir)

    expect(salir).toBeDisabled()
    expect(salir).toHaveAttribute('aria-busy', 'true')
    expect(salir.querySelector('.boton__giro')).not.toBeNull()
    expect(salir.querySelector('.icono')).toBeNull()
    responder([204])
    expect(await screen.findByText('Cerraste sesión')).toBeInTheDocument()
  })
})

describe('PW-01 Panel', () => {
  it('muestra el estado de la tienda y sus cifras', async () => {
    productor()
    abrirEn('/productor')
    expect(await screen.findByRole('heading', { name: 'Asoc. Nii Joi · Tienda publicada' })).toBeInTheDocument()
    expect(screen.getByText('400 und')).toBeInTheDocument()
    expect(screen.getByText(/Origen verificado desde el/)).toBeInTheDocument()
  })

  it('avisa lotes por vencer, productos sin stock y tienda sin publicar', async () => {
    const panel = {
      ...copia(DATOS.panelProductor),
      tiendaPublicada: false,
      // CCPIP-425: la anticipacion que el productor guardo en "Configurar alertas", no 15 fijos
      diasAviso: 7,
      lotesPorVencer: [{ producto: 'Camu camu', codigo: 'L-0009', ubicacion: 'Centro Nacional Lima', vence: '2026-10-10' }],
      publicadosSinStock: [{ id: 8, nombre: 'Aceite de aguaje 250 ml' }],
    }
    const user = userEvent.setup()
    productor({ 'GET /api/productor/panel': [200, panel] })
    abrirEn('/productor')
    expect(await screen.findByText('Publica tu tienda para empezar a vender')).toBeInTheDocument()
    expect(screen.getByText('1 lote vence pronto (según tu alerta de 7 días)')).toBeInTheDocument()
    // CCPIP-426: el indicador de lotes por vencer, en el amarillo de advertencia
    const porVencer = screen.getByRole('region', { name: 'Lotes por vencer' })
    expect(porVencer).toHaveClass('tarjeta-kpi--aviso')
    expect(within(porVencer).getByText('En los próximos 7 días')).toHaveClass('tarjeta-kpi__detalle--aviso')
    expect(screen.getByRole('link', { name: 'Registrar lote para reponer' })).toHaveAttribute('href', '/productor/lotes?producto=8')
    await user.click(screen.getAllByRole('button', { name: 'Cerrar aviso' })[0])
    expect(screen.queryByText(/vence pronto/)).not.toBeInTheDocument()
  })

  it('sin productos invita a publicar el primero; si falla lo dice', async () => {
    productor({ 'GET /api/productor/panel': [200, { ...copia(DATOS.panelProductor), publicados: 0, borradores: 0, retirados: 0 }] })
    abrirEn('/productor')
    expect(await screen.findByText('Aún no tienes productos')).toBeInTheDocument()
  })

  it('muestra cuántas personas y unidades esperan cada producto agotado, sin datos de los compradores (CCPIP-106)', async () => {
    productor({
      'GET /api/productor/intereses': [200, [
        { productoId: 8, nombre: 'Harina de aguaje 500 g', unidad: 'UNIDAD', interesados: 11, cantidad: 23, estado: 'AGOTADO', ultimoRegistro: '2026-10-16T20:00:00Z' },
        { productoId: 9, nombre: 'Bolso tejido de chambira', unidad: 'UNIDAD', interesados: 1, cantidad: 2, estado: 'VENTA_PAUSADA', ultimoRegistro: '2026-10-15T20:00:00Z' },
      ]],
    })
    abrirEn('/productor')
    const tarjeta = within(await screen.findByRole('region', { name: 'Interés en tus productos agotados' }))
    const filas = tarjeta.getAllByRole('listitem')
    expect(filas.map((f) => f.textContent)).toEqual([
      'Harina de aguaje 500 g11 personas · 23 und',
      'Bolso tejido de chambiraVenta pausada: reactiva tu cuenta bancaria para vender1 persona · 2 und',
    ])
    expect(tarjeta.getByRole('link', { name: 'Reponer stock' })).toHaveAttribute('href', '/productor/lotes')
    expect(tarjeta.getByText(/avisamos por correo a cada interesado/)).toBeInTheDocument()
  })

  it('sin interesados no muestra la tarjeta, y si no carga el panel sigue igual', async () => {
    productor({ 'GET /api/productor/intereses': [500, {}] })
    abrirEn('/productor')
    await screen.findByRole('heading', { name: 'Asoc. Nii Joi · Tienda publicada' })
    expect(screen.queryByRole('region', { name: 'Interés en tus productos agotados' })).not.toBeInTheDocument()
  })

  it('si el panel falla lo dice', async () => {
    productor({ 'GET /api/productor/panel': [500, {}] })
    abrirEn('/productor')
    expect(await screen.findByText('No pudimos cargar tu panel')).toBeInTheDocument()
  })
})

describe('PW-03 Mis productos (CCPIP-160)', () => {
  it('lista sus productos con precio, stock en venta y estado', async () => {
    productor()
    abrirEn('/productor/productos')
    const lista = await screen.findByRole('list', { name: 'Productos' })
    expect(within(lista).getAllByRole('listitem')).toHaveLength(4)
    expect(screen.getByRole('tab', { name: 'Publicados (4)' })).toHaveAttribute('aria-selected', 'true')
    expect(within(lista).getByText('S/ 39.00')).toBeInTheDocument()
  })

  it('despublicar pide confirmacion y no borra', async () => {
    const fetchMock = productor({ 'POST /api/productor/productos/9/retirar': [200, {}] })
    const user = userEvent.setup()
    abrirEn('/productor/productos')
    await user.click(await screen.findByRole('button', { name: 'Más acciones para Camu camu en polvo 150 g' }))
    await user.click(screen.getByRole('menuitem', { name: 'Despublicar' }))
    const dialogo = screen.getByRole('dialog')
    expect(within(dialogo).getByText(/No se borra/)).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('button', { name: 'Despublicar' }))
    expect(await screen.findByText('Producto despublicado')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/productos/9/retirar')).toHaveLength(1)
    expect(fetchMock.mock.calls.some(([, o]) => o?.method === 'DELETE')).toBe(false)
  })

  it('un despublicado se vuelve a publicar; si el servidor lo impide explica por que', async () => {
    const retirados = { items: [{ ...DATOS.productosProductor.items[0], estado: 'RETIRADO' }], conteo: { PUBLICADO: 3, BORRADOR: 0, RETIRADO: 1 } }
    productor({
      'GET /api/productor/productos': () => [200, copia(retirados)],
      'POST /api/productor/productos/9/publicar': [422, { code: 'NO_PUBLICABLE', error: 'Aún no se puede publicar', detalles: ['Registra un lote en un punto de acopio'] }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos?estado=RETIRADO')
    await user.click(await screen.findByRole('button', { name: /Más acciones para/ }))
    await user.click(screen.getByRole('menuitem', { name: 'Volver a publicar' }))
    expect(await screen.findByText('Aún no se puede publicar')).toBeInTheDocument()
    expect(screen.getByText('Registra un lote en un punto de acopio')).toBeInTheDocument()
  })

  it('cada pestana tiene su estado vacio y la busqueda sin resultados lo dice', async () => {
    productor({ 'GET /api/productor/productos': () => [200, { items: [], conteo: { PUBLICADO: 0, BORRADOR: 0, RETIRADO: 0 } }] })
    const user = userEvent.setup()
    abrirEn('/productor/productos')
    expect(await screen.findByText('Aún no tienes productos publicados')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Borradores (0)' }))
    expect(await screen.findByText('No tienes borradores')).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Despublicados (0)' }))
    expect(await screen.findByText('No tienes productos despublicados')).toBeInTheDocument()
    await user.type(screen.getByRole('searchbox', { name: 'Buscar producto' }), 'zzz')
    expect(await screen.findByText('No encontramos “zzz”')).toBeInTheDocument()
  })

  it('tras publicar desde el asistente muestra la confirmacion; si falla la red lo dice', async () => {
    productor({ 'GET /api/productor/productos': new TypeError('Failed to fetch') })
    abrirEn('/productor/productos?publicado=1')
    expect(await screen.findByText('Producto publicado')).toBeInTheDocument()
    expect(await screen.findByText('No pudimos cargar tus productos')).toBeInTheDocument()
  })
})

describe('PW-06 Registrar lote (CCPIP-41)', () => {
  it('desde el menu de un producto abre sus lotes con el que se vende primero', async () => {
    productor()
    const user = userEvent.setup()
    abrirEn('/productor/productos')
    await user.click(await screen.findByRole('button', { name: 'Más acciones para Camu camu en polvo 150 g' }))
    await user.click(screen.getByRole('menuitem', { name: 'Registrar lote' }))
    const lista = await screen.findByRole('list', { name: 'Lotes de este producto' })
    expect(within(lista).getByText('L-0009')).toBeInTheDocument()
    expect(within(lista).getByText('Vende primero')).toBeInTheDocument()
    expect(screen.getByText('Stock actual 100 und en 1 lote · 100 en venta')).toBeInTheDocument()
  })

  it('valida en pantalla: codigo, cantidad, fechas y ubicacion', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/lotes?producto=9')
    await user.click(await screen.findByRole('button', { name: 'Registrar lote' }))
    expect(screen.getByText('Escribe el código del lote')).toBeInTheDocument()
    expect(screen.getByText('La cantidad debe ser mayor a cero')).toBeInTheDocument()
    expect(screen.getByText('Este producto necesita fecha de vencimiento')).toBeInTheDocument()
    expect(screen.getByText('Elige dónde ingresa el lote')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Vencimiento'), '2020-01-01')
    await user.type(screen.getByLabelText('Producción'), '2099-01-01')
    await user.click(screen.getByRole('button', { name: 'Registrar lote' }))
    expect(screen.getByText('No puede ser anterior a hoy')).toBeInTheDocument()
    expect(screen.getByText('No puede ser posterior a hoy')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/productos/9/lotes')).toHaveLength(0)
  })

  it('registra el lote en un punto de acopio y actualiza la tabla', async () => {
    const nuevo = copia(DATOS.lotes)
    nuevo.items.push({ ...nuevo.items[0], id: 50, codigo: 'L-0050', cantidadActual: 20, estado: 'DISPONIBLE' })
    const fetchMock = productor({ 'POST /api/productor/productos/9/lotes': [201, nuevo] })
    const user = userEvent.setup()
    abrirEn('/productor/lotes?producto=9')
    await user.type(await screen.findByLabelText('Código de lote'), 'L-0050')
    await user.type(screen.getByLabelText('Cantidad'), '20')
    await user.type(screen.getByLabelText('Vencimiento'), '2099-12-31')
    await user.selectOptions(screen.getByLabelText('Ingresa a'), '1')
    expect(screen.getByText('En un punto de acopio el lote queda en venta de inmediato.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Registrar lote' }))
    expect(await screen.findByText('Lote registrado')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'POST /api/productor/productos/9/lotes')).toEqual({
      codigo: 'L-0050',
      cantidad: '20',
      fechaProduccion: null,
      fechaVencimiento: '2099-12-31',
      ubicacionId: 1,
    })
    expect(within(screen.getByRole('list', { name: 'Lotes de este producto' })).getByText('L-0050')).toBeInTheDocument()
  })

  it('muestra el rechazo del servidor (p. ej. codigo repetido)', async () => {
    productor({ 'POST /api/productor/productos/9/lotes': [409, { code: 'LOTE_DUPLICADO', error: 'Ya registraste ese código de lote', campos: { codigo: 'Ya existe' } }] })
    const user = userEvent.setup()
    abrirEn('/productor/lotes?producto=9')
    await user.type(await screen.findByLabelText('Código de lote'), 'L-0009')
    await user.type(screen.getByLabelText('Cantidad'), '5')
    await user.type(screen.getByLabelText('Vencimiento'), '2099-12-31')
    await user.selectOptions(screen.getByLabelText('Ingresa a'), '1')
    await user.click(screen.getByRole('button', { name: 'Registrar lote' }))
    expect(await screen.findByText('Ya registraste ese código de lote')).toBeInTheDocument()
  })

  it('sin producto elegido ofrece la lista; uno ajeno responde que no existe', async () => {
    productor({ 'GET /api/productor/productos/4/lotes': [404, { code: 'PRODUCTO_NO_ENCONTRADO' }] })
    const user = userEvent.setup()
    abrirEn('/productor/lotes?producto=4')
    expect(await screen.findByText('Ese producto no existe')).toBeInTheDocument()
    await user.selectOptions(await screen.findByLabelText('¿De qué producto es el lote?'), '9')
    expect(window.location.search).toBe('?producto=9')
    await user.click(await screen.findByRole('button', { name: 'Cambiar producto' }))
    expect(screen.getByLabelText('¿De qué producto es el lote?')).toBeInTheDocument()
  })

  it('un enlace armado no llega al API: el id debe ser un numero', async () => {
    const fetchMock = productor()
    abrirEn('/productor/lotes?producto=..%2F..%2Fauth%2Flogout')
    expect(await screen.findByText('Ese producto no existe')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([u]) => u.includes('auth/logout') || u.includes('..'))).toBe(false)
  })

  it('sin producto elegido lista todos sus lotes, primero el que vence antes (CCPIP-161)', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/lotes')
    const lista = await screen.findByRole('list', { name: 'Todos tus lotes' })
    const filas = within(lista).getAllByRole('listitem')
    expect(filas.map((f) => within(f).getByText(/^L-/).textContent)).toEqual(['L-0031', 'L-0032', 'L-0033', 'L-0009', 'L-0005'])
    // Producto, unidad, vencimiento, ubicacion y estado de cada lote
    expect(within(filas[0]).getByText('Aceite de aguaje 250 ml')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Botella')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Vencido')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Centro Nacional Lima')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Vende primero')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Mi almacén')).toBeInTheDocument()
    expect(within(filas[2]).getByText('En tu almacén')).toBeInTheDocument()
    expect(within(filas[4]).getByText('No vence')).toBeInTheDocument()
    expect(within(filas[4]).getByText('Unidad')).toBeInTheDocument()
    expect(within(filas[4]).getByText('Agotado')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/productor/lotes')).toHaveLength(1)

    // Desde un lote se abren los de su producto para registrar otro
    await user.click(within(filas[3]).getByRole('link', { name: 'Ver los lotes de Camu camu en polvo 150 g' }))
    expect(window.location.search).toBe('?producto=9')
    expect(await screen.findByRole('list', { name: 'Lotes de este producto' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Todos tus lotes' })).not.toBeInTheDocument()
  })

  it('sin lotes lo dice, y si falla la consulta lo avisa', async () => {
    productor({ 'GET /api/productor/lotes': [200, []] })
    const { unmount } = abrirEn('/productor/lotes')
    expect(await screen.findByText('Aún no registraste lotes. Elige un producto para registrar el primero.')).toBeInTheDocument()
    unmount()

    productor({ 'GET /api/productor/lotes': [500, { code: 'ERROR' }] })
    abrirEn('/productor/lotes')
    expect(await screen.findByText('No pudimos cargar tus lotes')).toBeInTheDocument()
  })

  it('sin productos invita a crear uno', async () => {
    productor({ 'GET /api/productor/productos': () => [200, { items: [], conteo: {} }] })
    abrirEn('/productor/lotes')
    expect(await screen.findByText('Primero crea un producto')).toBeInTheDocument()
  })
})

describe('PW-04 Datos del producto (CCPIP-40, 45, 132, 143, 44, 48)', () => {
  it('valida precios segun la modalidad y lo minimo para publicar', () => {
    const base = { nombre: 'X', lineas: [], unidad: '', modalidad: 'AMBAS', precio: '30', precioMayorista: '35', cantidadMinimaMayorista: '1' }
    expect(validarDatos(base)).toEqual({
      precioMayorista: 'Debe ser menor que el precio minorista',
      cantidadMinimaMayorista: 'Indica desde qué cantidad (2 o más)',
    })
    expect(validarDatos({ ...base, nombre: ' ', precio: '0', precioMayorista: '', modalidad: 'MINORISTA' })).toMatchObject({ nombre: expect.any(String), precio: expect.any(String) })
    expect(validarDatos({ ...base, modalidad: 'MAYORISTA', precio: '', precioMayorista: '' }, { paraPublicar: true })).toEqual({
      lineas: 'Elige al menos una línea',
      unidad: 'Elige la unidad de medida',
      precioMayorista: 'Escribe el precio mayorista',
    })
    expect(aCuerpo({ ...base, nombre: ' Cafe ', unidad: '', precio: '', precioMayorista: '27.5' })).toMatchObject({ nombre: 'Cafe', unidad: null, precio: null, precioMayorista: 27.5, cantidadMinimaMayorista: 1 })
  })

  it('crea el borrador con lineas, unidad sugerida y precios mayorista y minorista', async () => {
    const creado = { ...copia(DATOS.edicion), id: 30, estado: 'BORRADOR', nombre: 'Aceite de sacha inchi', imagenes: [], historia: null }
    const fetchMock = productor({
      'POST /api/productor/productos': [201, creado],
      'GET /api/productor/productos/30': () => [200, copia(creado)],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')
    await user.type(await screen.findByLabelText('Nombre del producto'), 'Aceite de sacha inchi')
    await user.click(await screen.findByRole('button', { name: 'Aceites' }))
    await user.click(screen.getByRole('button', { name: 'Superfrutos' }))
    expect(screen.getByRole('button', { name: 'Aceites' })).toHaveAttribute('aria-pressed', 'true')
    await user.click(screen.getByRole('radio', { name: 'Minorista y mayorista' }))
    await user.type(screen.getByLabelText('Precio minorista (S/)'), '32')
    await user.type(screen.getByLabelText('Precio mayorista (S/)'), '27')
    await user.type(screen.getByLabelText('Cantidad mínima mayorista'), '12')
    expect(screen.getByText('Recibirás S/ 28.80 por unidad')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Guardar borrador' }))

    await waitFor(() => expect(window.location.pathname).toBe('/productor/productos/30'))
    const enviado = cuerpo(fetchMock, 'POST /api/productor/productos')
    expect(enviado).toMatchObject({ nombre: 'Aceite de sacha inchi', lineas: ['ACEITES', 'SUPERFRUTOS'], modalidad: 'AMBAS', precio: 32, precioMayorista: 27, cantidadMinimaMayorista: 12 })
    expect(enviado.unidad).toBe(DATOS.catalogos.lineas.find((l) => l.codigo === 'ACEITES').unidadSugerida)
  })

  it('solo fija precios ordinarios: sin descuentos ni promociones (CCPIP-87)', async () => {
    const fetchMock = productor({
      'POST /api/productor/productos': [422, { code: 'CAMPO_NO_PERMITIDO', error: 'Solo fijas el precio ordinario: no existen descuentos ni precios promocionales.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')
    await user.type(await screen.findByLabelText('Nombre del producto'), 'Aceite de sacha inchi')
    // Solo minorista: no hay campo de descuento y se avisa que no existen promociones
    await user.type(screen.getByLabelText('Precio minorista (S/)'), '32')
    expect(screen.getByText('No se permiten descuentos ni precios promocionales.')).toBeInTheDocument()
    expect(screen.queryByLabelText(/descuento|promoci/i)).not.toBeInTheDocument()
    expect(screen.getByText(/Los descuentos solo los aplica la Cámara\./)).toBeInTheDocument()

    // La doble tarifa no es una promocion: el mayorista se aplica desde un minimo y es menor
    await user.click(screen.getByRole('radio', { name: 'Minorista y mayorista' }))
    await user.type(screen.getByLabelText('Precio mayorista (S/)'), '32')
    await user.type(screen.getByLabelText('Cantidad mínima mayorista'), '12')
    expect(screen.getByText(/se aplica solo desde 12 und\. Debe ser menor que el minorista\. No se permiten descuentos/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Guardar borrador' }))
    expect(screen.getAllByText('Debe ser menor que el precio minorista').length).toBeGreaterThan(0)
    expect(llamadas(fetchMock, 'POST /api/productor/productos')).toHaveLength(0)

    // Al servidor solo van los campos del contrato; si rechaza algo de promocion, se explica
    await user.clear(screen.getByLabelText('Precio mayorista (S/)'))
    await user.type(screen.getByLabelText('Precio mayorista (S/)'), '27')
    await user.click(screen.getByRole('button', { name: 'Guardar borrador' }))
    expect(await screen.findByText('Solo fijas el precio ordinario: no existen descuentos ni precios promocionales.')).toBeInTheDocument()
    expect(Object.keys(cuerpo(fetchMock, 'POST /api/productor/productos')).sort()).toEqual([
      'cantidadMinimaMayorista', 'contenido', 'descripcion', 'descripcionAsistida', 'esMateriaPrima', 'lineas',
      'modalidad', 'nombre', 'precio', 'precioMayorista', 'unidad',
    ])
  })

  it('no deja guardar con datos invalidos', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')
    await user.click(await screen.findByRole('button', { name: 'Siguiente: historia' }))
    expect(screen.getByText('Revisa los datos marcados')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/productos')).toHaveLength(0)
  })

  it('la descripcion asistida se revisa antes de aceptarse y queda marcada como IA', async () => {
    const fetchMock = productor({
      'POST /api/productor/ia/descripcion': [200, { texto: SUGERIDA }],
      [ACEPTAR_9]: () => [200, { ...copia(DATOS.edicion), descripcionSugerida: SUGERIDA }],
      'PUT /api/productor/productos/9': () => [200, copia(DATOS.edicion)],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))
    expect(await screen.findByText('Borrador asistido por IA · revisa antes de publicar')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'POST /api/productor/ia/descripcion')).toMatchObject({ productoId: 9 })
    expect(screen.getByLabelText('Descripción')).toHaveValue(DATOS.edicion.descripcion)
    // CCPIP-49: aceptarla queda registrado en el servidor antes de usarla
    await user.click(screen.getByRole('button', { name: 'Aceptar descripción' }))
    expect(await screen.findByLabelText('Descripción (borrador asistido por IA, editable)')).toHaveValue(SUGERIDA)
    expect(llamadas(fetchMock, ACEPTAR_9)).toHaveLength(1)
    // El productor la edita: lo que se guarda es su version final
    await user.type(screen.getByLabelText('Descripción (borrador asistido por IA, editable)'), ' Cosecha 2026.')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByText('Cambios guardados')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'PUT /api/productor/productos/9')).toMatchObject({ descripcion: `${SUGERIDA} Cosecha 2026.`, descripcionAsistida: true })
  })

  it('en un producto nuevo, pedir la descripcion guarda primero el borrador para que la sugerencia quede registrada (CCPIP-49)', async () => {
    const creado = { ...copia(DATOS.edicion), id: 32, estado: 'BORRADOR', nombre: 'Aceite de sacha inchi', descripcion: null, imagenes: [], historia: null }
    const fetchMock = productor({
      'POST /api/productor/productos': [201, creado],
      'GET /api/productor/productos/32': () => [200, copia(creado)],
      'POST /api/productor/ia/descripcion': [200, { texto: SUGERIDA }],
      'POST /api/productor/productos/32/ia/descripcion/aceptar': () => [200, { ...copia(creado), descripcionSugerida: SUGERIDA }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')
    await user.type(await screen.findByLabelText('Nombre del producto'), 'Aceite de sacha inchi')
    await user.click(screen.getByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))

    expect(await screen.findByText(SUGERIDA)).toBeInTheDocument()
    expect(window.location.pathname).toBe('/productor/productos/32')
    expect(cuerpo(fetchMock, 'POST /api/productor/productos')).toMatchObject({ nombre: 'Aceite de sacha inchi' })
    expect(cuerpo(fetchMock, 'POST /api/productor/ia/descripcion')).toMatchObject({ productoId: 32, nombre: 'Aceite de sacha inchi' })
    // Nacer como borrador no publica nada ni borra lo escrito
    expect(screen.getByLabelText('Nombre del producto')).toHaveValue('Aceite de sacha inchi')

    await user.click(screen.getByRole('button', { name: 'Aceptar descripción' }))
    expect(await screen.findByLabelText('Descripción (borrador asistido por IA, editable)')).toHaveValue(SUGERIDA)
    expect(llamadas(fetchMock, 'POST /api/productor/productos/32/ia/descripcion/aceptar')).toHaveLength(1)
  })

  it('si no se puede guardar el producto nuevo no pide la sugerencia y lo explica', async () => {
    const fetchMock = productor({
      'POST /api/productor/productos': [422, { code: 'CAMPO_NO_PERMITIDO', error: 'Solo fijas el precio ordinario: no existen descuentos ni precios promocionales.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')
    await user.type(await screen.findByLabelText('Nombre del producto'), 'Aceite de sacha inchi')
    await user.click(screen.getByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))
    expect(await screen.findByText('Solo fijas el precio ordinario: no existen descuentos ni precios promocionales.')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/ia/descripcion')).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Sugerir una descripción con IA (borrador)' })).toBeEnabled()
  })

  it('mientras el servidor registra la aceptacion no se puede decidir otra vez', async () => {
    let responder
    const fetchMock = productor({
      'POST /api/productor/ia/descripcion': [200, { texto: SUGERIDA }],
      [ACEPTAR_9]: () => new Promise((resolver) => { responder = resolver }),
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))
    await user.click(await screen.findByRole('button', { name: 'Aceptar descripción' }))
    for (const nombre of ['Aceptar descripción', 'Editar', 'Generar otra', 'Descartar']) {
      expect(screen.getByRole('button', { name: nombre })).toBeDisabled()
    }
    // Sin respuesta aun, la descripcion sigue siendo la del productor
    expect(screen.getByLabelText('Descripción')).toHaveValue(DATOS.edicion.descripcion)
    responder([200, copia(DATOS.edicion)])
    expect(await screen.findByLabelText('Descripción (borrador asistido por IA, editable)')).toHaveValue(SUGERIDA)
    expect(llamadas(fetchMock, ACEPTAR_9)).toHaveLength(1)
  })

  it('si el servidor no registra la aceptacion, la descripcion no cambia y el borrador sigue a la vista', async () => {
    productor({
      'POST /api/productor/ia/descripcion': [200, { texto: SUGERIDA }],
      [ACEPTAR_9]: [500, {}],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))
    await user.click(await screen.findByRole('button', { name: 'Aceptar descripción' }))
    expect(await screen.findByText('No pudimos aceptar el borrador')).toBeInTheDocument()
    expect(screen.getByLabelText('Descripción')).toHaveValue(DATOS.edicion.descripcion)
    expect(screen.getByText(SUGERIDA)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Aceptar descripción' })).toBeEnabled()
  })

  it('un borrador que ya no esta pendiente no se usa: se avisa y se puede pedir otro', async () => {
    productor({
      'POST /api/productor/ia/descripcion': [200, { texto: SUGERIDA }],
      [ACEPTAR_9]: [409, { code: 'SIN_BORRADOR', error: 'No hay un borrador de la IA para aceptar.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))
    await user.click(await screen.findByRole('button', { name: 'Aceptar descripción' }))
    expect(await screen.findByText('Este borrador ya no está pendiente. Pide otro si lo necesitas.')).toBeInTheDocument()
    expect(screen.queryByText(SUGERIDA)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Descripción')).toHaveValue(DATOS.edicion.descripcion)
    expect(screen.getByRole('button', { name: 'Sugerir una descripción con IA (borrador)' })).toBeInTheDocument()
  })

  it('descartar el borrador de la descripcion lo registra y no toca lo escrito (CCPIP-280)', async () => {
    const fetchMock = productor({
      'POST /api/productor/ia/descripcion': [200, { texto: 'Aceite prensado en frío por mujeres shipibo.' }],
      'POST /api/productor/productos/9/ia/rechazar?borrador=DESCRIPCION': [204],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))
    await user.click(await screen.findByRole('button', { name: 'Descartar' }))
    expect(await screen.findByText(/Descartaste el borrador/)).toBeInTheDocument()
    expect(screen.queryByText('Aceite prensado en frío por mujeres shipibo.')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Descripción')).toHaveValue(DATOS.edicion.descripcion)
    expect(llamadas(fetchMock, 'POST /api/productor/productos/9/ia/rechazar?borrador=DESCRIPCION')).toHaveLength(1)
    // Se puede pedir otro
    expect(screen.getByRole('button', { name: 'Sugerir una descripción con IA (borrador)' })).toBeInTheDocument()
  })

  it('si no se puede descartar el borrador lo avisa y lo mantiene', async () => {
    productor({
      'POST /api/productor/ia/descripcion': [200, { texto: 'Aceite prensado en frío por mujeres shipibo.' }],
      'POST /api/productor/productos/9/ia/rechazar?borrador=DESCRIPCION': [500, {}],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))
    await user.click(await screen.findByRole('button', { name: 'Descartar' }))
    expect(await screen.findByText('No pudimos descartar el borrador')).toBeInTheDocument()
    expect(screen.getByText('Aceite prensado en frío por mujeres shipibo.')).toBeInTheDocument()
  })

  it('sin IA configurada el productor escribe a mano', async () => {
    productor({ 'POST /api/productor/ia/descripcion': [503, { code: 'IA_NO_DISPONIBLE', error: 'La redacción asistida no está disponible' }] })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))
    expect(await screen.findByText('La redacción asistida no está disponible')).toBeInTheDocument()
    expect(screen.getByLabelText('Descripción')).not.toBeDisabled()
  })

  it('sube cada foto por separado, permite reintentar y elegir la principal', async () => {
    let intentos = 0
    const conFotos = {
      ...copia(DATOS.edicion),
      imagenes: [
        { id: '0000000a-0000-4000-8000-000000000000', urlMiniatura: '/api/imagenes/a?tam=min', principal: true },
        { id: '0000000b-0000-4000-8000-000000000000', urlMiniatura: '/api/imagenes/b?tam=min', principal: false },
      ],
    }
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, copia(conFotos)],
      'POST /api/productor/imagenes': () => {
        intentos += 1
        return intentos === 1 ? new TypeError('Failed to fetch') : [201, { id: '0000000c-0000-4000-8000-000000000000' }]
      },
      'PUT /api/productor/imagenes/0000000b-0000-4000-8000-000000000000/principal': [200, {}],
      'DELETE /api/productor/imagenes/0000000a-0000-4000-8000-000000000000': [204],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.upload(await screen.findByLabelText('Arrastra fotos aquí o selecciónalas'), foto())
    await user.click(await screen.findByRole('button', { name: 'Reintentar' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument())
    expect(intentos).toBe(2)

    await user.click(screen.getByRole('button', { name: 'Hacer principal' }))
    await user.click(screen.getAllByRole('button', { name: 'Quitar foto' })[0])
    await user.click(await screen.findByRole('button', { name: 'Retirar foto' }))
    await waitFor(() => expect(llamadas(fetchMock, 'DELETE /api/productor/imagenes/0000000a-0000-4000-8000-000000000000')).toHaveLength(1))
    expect(llamadas(fetchMock, 'PUT /api/productor/imagenes/0000000b-0000-4000-8000-000000000000/principal')).toHaveLength(1)
  })

  it('pide el nombre antes de la primera foto', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')
    await user.upload(await screen.findByLabelText('Arrastra fotos aquí o selecciónalas'), foto())
    expect(await screen.findAllByText('Escribe el nombre del producto antes de agregar fotos')).toHaveLength(2)
    expect(screen.getByLabelText('Nombre del producto')).toHaveAttribute('aria-invalid', 'true')
    expect(llamadas(fetchMock, 'POST /api/productor/imagenes')).toHaveLength(0)
  })

  it('la primera foto crea el borrador y se ve al terminar de subir, sin perder lo escrito', async () => {
    let conFoto = false
    const creado = { ...copia(DATOS.edicion), id: 31, estado: 'BORRADOR', nombre: 'Miel de abeja nativa', imagenes: [], historia: null }
    productor({
      'POST /api/productor/productos': [201, creado],
      'GET /api/productor/productos/31': () => [200, { ...copia(creado), imagenes: conFoto ? [{ id: '0000000f-0000-4000-8000-000000000000', urlMiniatura: '/api/imagenes/f?tam=min', principal: true }] : [] }],
      'POST /api/productor/imagenes': () => {
        conFoto = true
        return [201, { id: '0000000f-0000-4000-8000-000000000000' }]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')
    await user.type(await screen.findByLabelText('Nombre del producto'), 'Miel de abeja nativa')
    await user.upload(screen.getByLabelText('Arrastra fotos aquí o selecciónalas'), foto())
    expect(await screen.findByAltText('Foto del producto')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/productor/productos/31')
    expect(screen.getByLabelText('Nombre del producto')).toHaveValue('Miel de abeja nativa')
  })

  it('si no se puede quitar una foto lo avisa y la lista sigue', async () => {
    const conFoto = { ...copia(DATOS.edicion), imagenes: [{ id: '0000000a-0000-4000-8000-000000000000', urlMiniatura: '/api/imagenes/a?tam=min', principal: true }] }
    productor({
      'GET /api/productor/productos/9': () => [200, copia(conFoto)],
      'DELETE /api/productor/imagenes/0000000a-0000-4000-8000-000000000000': [500, { error: 'No pudimos quitar la foto' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Quitar foto' }))
    await user.click(await screen.findByRole('button', { name: 'Retirar foto' }))
    expect(await screen.findByText('No pudimos quitar la foto')).toBeInTheDocument()
    expect(screen.getByAltText('Foto del producto')).toBeInTheDocument()
  })

  it('retirar una foto pide confirmacion y explica que se conserva (CCPIP-389)', async () => {
    const conFoto = { ...copia(DATOS.edicion), imagenes: [{ id: '0000000a-0000-4000-8000-000000000000', urlMiniatura: '/api/imagenes/a?tam=min', principal: true }] }
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, copia(conFoto)],
      'DELETE /api/productor/imagenes/0000000a-0000-4000-8000-000000000000': [204],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Quitar foto' }))
    expect(screen.getByRole('heading', { name: '¿Retirar esta foto?' })).toBeInTheDocument()
    expect(screen.getByText(/Es tu foto principal: la siguiente ocupará su lugar/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(llamadas(fetchMock, 'DELETE /api/productor/imagenes/0000000a-0000-4000-8000-000000000000')).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Quitar foto' }))
    await user.click(await screen.findByRole('button', { name: 'Retirar foto' }))
    await waitFor(() => expect(llamadas(fetchMock, 'DELETE /api/productor/imagenes/0000000a-0000-4000-8000-000000000000')).toHaveLength(1))
  })

  it('reemplaza una foto en su lugar y avisa si el archivo no sirve (CCPIP-388)', async () => {
    let intentos = 0
    let reemplazada = false
    const fotoA = { id: '0000000a-0000-4000-8000-000000000000', urlMiniatura: '/api/imagenes/a?tam=min', principal: true }
    const fotoD = { id: '0000000d-0000-4000-8000-000000000000', urlMiniatura: '/api/imagenes/d?tam=min', principal: true }
    productor({
      'GET /api/productor/productos/9': () => [200, { ...copia(DATOS.edicion), imagenes: [reemplazada ? fotoD : fotoA] }],
      'POST /api/productor/imagenes/0000000a-0000-4000-8000-000000000000/reemplazo': () => {
        intentos += 1
        if (intentos === 1) {
          return [422, { code: 'FORMATO_NO_PERMITIDO', error: 'Solo se aceptan fotos JPG, PNG o WebP.' }]
        }
        reemplazada = true
        return [201, fotoD]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.upload(await screen.findByLabelText('Reemplazar foto'), foto('mala.jpg'))
    expect(await screen.findByText('Solo se aceptan fotos JPG, PNG o WebP.')).toBeInTheDocument()
    expect(screen.getByAltText('Foto del producto')).toHaveAttribute('src', '/api/imagenes/a?tam=min')

    await user.upload(screen.getByLabelText('Reemplazar foto'), foto('nueva.jpg'))
    await waitFor(() => expect(screen.getByAltText('Foto del producto')).toHaveAttribute('src', '/api/imagenes/d?tam=min'))
    expect(screen.getByText('Principal')).toBeInTheDocument()
  })

  it('no reemplaza con una foto de mas de 10 MB', async () => {
    const conFoto = { ...copia(DATOS.edicion), imagenes: [{ id: '0000000a-0000-4000-8000-000000000000', urlMiniatura: '/api/imagenes/a?tam=min', principal: true }] }
    const fetchMock = productor({ 'GET /api/productor/productos/9': () => [200, copia(conFoto)] })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    const grande = foto('grande.jpg', 1)
    Object.defineProperty(grande, 'size', { value: 11 * 1024 * 1024 })
    await user.upload(await screen.findByLabelText('Reemplazar foto'), grande)
    expect(await screen.findByText('Pesa más de 10 MB')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/imagenes/0000000a-0000-4000-8000-000000000000/reemplazo')).toHaveLength(0)
  })

  it('una foto que pesa mas de 10 MB no se envia', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    const grande = foto('grande.jpg', 1)
    Object.defineProperty(grande, 'size', { value: 11 * 1024 * 1024 })
    await user.upload(await screen.findByLabelText('Arrastra fotos aquí o selecciónalas'), grande)
    expect(await screen.findByText('Pesa más de 10 MB')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/imagenes')).toHaveLength(0)
  })

  it('un producto ajeno responde que no existe', async () => {
    productor({ 'GET /api/productor/productos/4': [404, { code: 'PRODUCTO_NO_ENCONTRADO' }] })
    abrirEn('/productor/productos/4')
    expect(await screen.findByText('Este producto no existe')).toBeInTheDocument()
  })
})

describe('PW-05 Historia asistida (CCPIP-49)', () => {
  const sinHistoria = { ...copia(DATOS.edicion), estado: 'BORRADOR', historia: null }

  it('arma el borrador con IA desde las respuestas, permite editarlo y lo aprueba como asistido', async () => {
    const texto = 'Somos mujeres shipibo que recolectamos camu camu en las cochas sin talar el bosque.'
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, copia(sinHistoria)],
      'POST /api/productor/productos/9/historia/propuesta': [200, { titulo: 'Del agua a tu mesa', texto }],
      'PUT /api/productor/productos/9/historia': [200, {}],
      'POST /api/productor/productos/9/historia/aprobar': [200, {}],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9?paso=historia')

    await user.click(await screen.findByRole('button', { name: 'Generar borrador con IA' }))
    expect(screen.getByText('Responde al menos una pregunta')).toBeInTheDocument()

    // La primera pregunta sigue abierta: nada le quito el foco
    await user.type(screen.getByRole('textbox', { name: '¿Quiénes lo elaboran?' }), '34 mujeres shipibo')
    // Al salir de ella se abre la siguiente sin responder, sin robar el foco
    await user.tab()
    expect(screen.getByRole('textbox', { name: '¿Cómo lo hacen?' })).not.toHaveFocus()
    await user.click(screen.getByRole('button', { name: 'Responder: ¿Quiénes lo elaboran?' }))
    expect(screen.getByRole('textbox', { name: '¿Quiénes lo elaboran?' })).toHaveFocus()
    await user.click(screen.getByRole('button', { name: 'Generar borrador con IA' }))
    expect(await screen.findByRole('heading', { name: 'Del agua a tu mesa' })).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'POST /api/productor/productos/9/historia/propuesta')).toMatchObject({ respuestas: { quienes: '34 mujeres shipibo' }, variante: 'NORMAL' })

    await user.click(screen.getByRole('button', { name: 'Más corto' }))
    expect(cuerpo(fetchMock, 'POST /api/productor/productos/9/historia/propuesta').variante).toBe('CORTA')
    await user.click(await screen.findByRole('button', { name: 'Editar texto' }))
    await user.clear(screen.getByLabelText('Título'))
    await user.type(screen.getByLabelText('Título'), 'Camu camu de las cochas')
    await user.click(screen.getByRole('button', { name: 'Listo' }))
    await user.click(screen.getByRole('button', { name: 'Aprobar historia' }))

    await waitFor(() => expect(window.location.search).toBe('?paso=lote'))
    expect(cuerpo(fetchMock, 'POST /api/productor/productos/9/historia/aprobar')).toEqual({ titulo: 'Camu camu de las cochas', textoFinal: texto, asistida: true })
  })

  it('descartar el borrador de la historia conserva las respuestas (CCPIP-280)', async () => {
    const conPropuesta = { ...copia(sinHistoria), historia: { quienes: '34 mujeres shipibo', como: '', significado: '', cuidado: '', titulo: 'Del agua a tu mesa', textoPropuesto: 'Somos mujeres shipibo que recolectamos camu camu.', aprobada: false } }
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, copia(conPropuesta)],
      'POST /api/productor/productos/9/ia/rechazar?borrador=HISTORIA': [204],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9?paso=historia')
    expect(await screen.findByRole('heading', { name: 'Del agua a tu mesa' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Descartar borrador' }))
    expect(await screen.findByText('Descartaste el borrador de la IA')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Del agua a tu mesa' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Generar borrador con IA' })).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/productos/9/ia/rechazar?borrador=HISTORIA')).toHaveLength(1)
    expect(llamadas(fetchMock, 'POST /api/productor/productos/9/historia/aprobar')).toHaveLength(0)
  })

  it('sin IA se escribe a mano y debe tener un minimo de caracteres', async () => {
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, copia(sinHistoria)],
      'POST /api/productor/productos/9/historia/propuesta': [503, { code: 'IA_NO_DISPONIBLE', error: 'No disponible' }],
      'PUT /api/productor/productos/9/historia': [200, {}],
      'POST /api/productor/productos/9/historia/aprobar': [200, {}],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9?paso=historia')
    await user.type(await screen.findByRole('textbox', { name: '¿Quiénes lo elaboran?' }), 'Familias kukama')
    await user.click(screen.getByRole('button', { name: 'Generar borrador con IA' }))
    expect(await screen.findByText('La redacción asistida no está disponible')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Historia'), 'Muy corta')
    await user.click(screen.getByRole('button', { name: 'Aprobar historia' }))
    expect(screen.getByText('La historia debe tener al menos 40 caracteres')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Historia'), ' pero ahora sí cuenta quiénes somos y cómo lo hacemos.')
    await user.click(screen.getByRole('button', { name: 'Aprobar historia' }))
    await waitFor(() => expect(llamadas(fetchMock, 'POST /api/productor/productos/9/historia/aprobar')).toHaveLength(1))
    expect(cuerpo(fetchMock, 'POST /api/productor/productos/9/historia/aprobar').asistida).toBe(false)
  })

  it('puede guardar las respuestas y seguir despues', async () => {
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, copia(sinHistoria)],
      'PUT /api/productor/productos/9/historia': [200, {}],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9?paso=historia')
    await user.click(await screen.findByRole('button', { name: 'Guardar respuestas y seguir después' }))
    await waitFor(() => expect(window.location.search).toBe('?paso=lote'))
    expect(llamadas(fetchMock, 'PUT /api/productor/productos/9/historia')).toHaveLength(1)
  })

  it('una historia aprobada se muestra y se puede editar', async () => {
    productor()
    const user = userEvent.setup()
    abrirEn('/productor/productos/9?paso=historia')
    expect(await screen.findByText('Historia aprobada')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Editar historia' }))
    expect(screen.getByLabelText('Historia')).toHaveValue(DATOS.edicion.historia.textoFinal)
  })
})

describe('Paso 3 · primer lote y publicacion (CCPIP-40)', () => {
  it('un borrador listo se publica y vuelve a Mis productos', async () => {
    const borrador = { ...copia(DATOS.edicion), estado: 'BORRADOR' }
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, copia(borrador)],
      'POST /api/productor/productos/9/publicar': [200, {}],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9?paso=lote')
    await user.click(await screen.findByRole('button', { name: 'Publicar producto' }))
    await waitFor(() => expect(window.location.pathname).toBe('/productor/productos'))
    expect(llamadas(fetchMock, 'POST /api/productor/productos/9/publicar')).toHaveLength(1)
  })

  it('si falta algo lo lista; si la tienda no esta publicada lleva a Mi tienda', async () => {
    const borrador = { ...copia(DATOS.edicion), estado: 'BORRADOR', publicable: { listo: false, faltan: ['Registra un lote en un punto de acopio', 'Publica tu tienda'] } }
    productor({ 'GET /api/productor/productos/9': () => [200, copia(borrador)] })
    abrirEn('/productor/productos/9?paso=lote')
    expect(await screen.findByText('Registra un lote en un punto de acopio')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ir a Mi tienda' })).toHaveAttribute('href', '/productor/tienda')
  })

  it('el servidor puede rechazar la publicacion', async () => {
    const borrador = { ...copia(DATOS.edicion), estado: 'BORRADOR' }
    productor({
      'GET /api/productor/productos/9': () => [200, copia(borrador)],
      'POST /api/productor/productos/9/publicar': [422, { code: 'NO_PUBLICABLE', error: 'Aún no se puede publicar', detalles: ['El lote venció'] }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9?paso=lote')
    await user.click(await screen.findByRole('button', { name: 'Publicar producto' }))
    expect(await screen.findByText('El lote venció')).toBeInTheDocument()
  })

  it('los pasos ya hechos se pueden revisar', async () => {
    productor()
    const user = userEvent.setup()
    abrirEn('/productor/productos/9?paso=lote')
    expect(await screen.findByText('Producto publicado')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Datos/ }))
    expect(window.location.search).toBe('?paso=datos')
    expect(await screen.findByLabelText('Nombre del producto')).toHaveValue(DATOS.edicion.nombre)
  })
})

describe('PW-08 Editar mi tienda (CCPIP-30, 153, 154)', () => {
  const nueva = {
    ...copia(DATOS.tiendaProductor),
    nombreTienda: null,
    descripcionCorta: null,
    historia: null,
    cita: null,
    pueblo: null,
    localidad: null,
    socios: null,
    publicada: false,
    productosPublicados: [],
  }

  it('una tienda nueva se completa y se publica', async () => {
    let tienda = copia(nueva)
    const fetchMock = productor({
      'GET /api/productor/tienda': () => [200, copia(tienda)],
      'PUT /api/productor/tienda': (o) => {
        tienda = { ...tienda, ...JSON.parse(o.body) }
        return [200, copia(tienda)]
      },
      'POST /api/productor/tienda/publicar': () => {
        tienda = { ...tienda, publicada: true }
        return [200, copia(tienda)]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    await user.click(await screen.findByRole('button', { name: 'Publicar tienda' }))
    expect(screen.getByText('Escribe el nombre de tu tienda')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Nombre de la tienda'), 'Comunidad Kukama')
    await user.type(screen.getByLabelText('Historia de mi comunidad'), 'Pescamos y sembramos en la ribera del Marañón.')
    await user.type(screen.getByLabelText('Pueblo de pertenencia'), 'Kukama')
    await user.type(screen.getByLabelText('Socias y socios'), '-3')
    await user.click(screen.getByRole('button', { name: 'Publicar tienda' }))
    expect(screen.getByText('Debe ser un número positivo')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Socias y socios'))
    await user.type(screen.getByLabelText('Socias y socios'), '18')
    await user.click(screen.getByRole('button', { name: 'Publicar tienda' }))
    expect(await screen.findByText('¡Tu tienda está publicada!')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'PUT /api/productor/tienda')).toMatchObject({ nombreTienda: 'Comunidad Kukama', pueblo: 'Kukama', socios: 18 })
    expect(screen.getByRole('link', { name: 'Ver tienda publicada' })).toHaveAttribute('href', `/tiendas/${DATOS.tiendaProductor.id}`)
  })

  it('si falta algo para publicar el servidor lo explica', async () => {
    productor({
      'GET /api/productor/tienda': () => [200, { ...copia(nueva), nombreTienda: 'X' }],
      'PUT /api/productor/tienda': () => [200, { ...copia(nueva), nombreTienda: 'X' }],
      'POST /api/productor/tienda/publicar': [422, { code: 'TIENDA_INCOMPLETA', error: 'Completa tu tienda', detalles: ['Cuenta la historia de tu comunidad'] }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    await user.click(await screen.findByRole('button', { name: 'Publicar tienda' }))
    expect(await screen.findByText('Cuenta la historia de tu comunidad')).toBeInTheDocument()
  })

  it('elige hasta 4 destacados y guarda los cambios', async () => {
    const conCinco = copia(DATOS.tiendaProductor)
    conCinco.productosPublicados.push({ id: 99, nombre: 'Quinto producto' })
    const fetchMock = productor({
      'GET /api/productor/tienda': () => [200, copia(conCinco)],
      'PUT /api/productor/tienda': (o) => [200, { ...copia(conCinco), ...JSON.parse(o.body) }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    await user.click(await screen.findByRole('button', { name: 'Elegir productos' }))
    for (const p of conCinco.productosPublicados.slice(0, 4)) await user.click(screen.getByRole('checkbox', { name: p.nombre }))
    expect(screen.getByRole('checkbox', { name: 'Quinto producto' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Listo' }))
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByText('Cambios publicados')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'PUT /api/productor/tienda').destacados).toEqual([8, 7, 9, 5])
  })

  it('sube portada y fotos de galeria con su categoria, y las quita', async () => {
    let tienda = copia(DATOS.tiendaProductor)
    const subidas = []
    productor({
      'GET /api/productor/tienda': () => [200, copia(tienda)],
      'POST /api/productor/imagenes': (o) => {
        subidas.push(Object.fromEntries([...o.body.entries()].filter(([k]) => k !== 'archivo')))
        tienda = { ...tienda, galeria: [{ id: '000000a1-0000-4000-8000-000000000000', categoria: 'CULTIVO', urlMiniatura: '/api/imagenes/g1?tam=min' }] }
        return [201, {}]
      },
      'DELETE /api/productor/imagenes/000000a1-0000-4000-8000-000000000000': () => {
        tienda = { ...tienda, galeria: [] }
        return [204]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    await user.upload(await screen.findByLabelText('Subir portada'), foto('portada.jpg'))
    await user.selectOptions(screen.getByLabelText('Categoría de las fotos nuevas'), 'CULTIVO')
    await user.upload(screen.getByLabelText('Agregar foto'), foto('chacra.jpg'))
    expect(await screen.findByRole('heading', { name: 'Galería (1)' })).toBeInTheDocument()
    expect(subidas).toEqual([{ tipo: 'PORTADA' }, { tipo: 'GALERIA', categoria: 'CULTIVO' }])

    await user.click(screen.getByRole('button', { name: 'Comunidad' }))
    expect(screen.queryByAltText('Foto de Cultivo')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Todo' }))
    await user.click(screen.getByRole('button', { name: 'Quitar foto' }))
    expect(await screen.findByRole('heading', { name: 'Galería (0)' })).toBeInTheDocument()
  })

  it('una foto que falla avisa sin perder las demas', async () => {
    productor({ 'POST /api/productor/imagenes': [413, { code: 'IMAGEN_MUY_GRANDE', error: 'La foto pesa más de 10 MB' }] })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    await user.upload(await screen.findByLabelText('Agregar foto'), foto('pesada.jpg'))
    expect(await screen.findByText('No se subió “pesada.jpg”')).toBeInTheDocument()
  })

  it('si tras subir una foto no se puede recargar la tienda, lo dice', async () => {
    let lecturas = 0
    productor({
      'GET /api/productor/tienda': () => (++lecturas > 2 ? new TypeError('Failed to fetch') : [200, copia(DATOS.tiendaProductor)]),
      'POST /api/productor/imagenes': [201, {}],
    })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    await user.upload(await screen.findByLabelText('Agregar foto'), foto('chacra.jpg'))
    expect(await screen.findByText('No pudimos actualizar tus fotos')).toBeInTheDocument()
  })

  it('si no carga la tienda lo dice', async () => {
    productor({ 'GET /api/productor/tienda': [500, {}] })
    abrirEn('/productor/tienda')
    expect(await screen.findByText('No pudimos cargar tu tienda')).toBeInTheDocument()
  })
})
