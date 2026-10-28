import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

// CCPIP-144, 168, 364 (validar la foto contra su linea) y CCPIP-176, 272, 281, 282 (mejora opcional) en PW-04

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const A = '0000000a-0000-4000-8000-000000000000'
const B = '0000000b-0000-4000-8000-000000000000'
const C = '0000000c-0000-4000-8000-000000000000'
const D = '0000000d-0000-4000-8000-000000000000'
const E = '0000000e-0000-4000-8000-000000000000'
const fotoDe = (id, principal = false) => ({ id, url: `/api/imagenes/${id}`, urlMiniatura: `/api/imagenes/${id}?tam=min`, principal })
const linea = (codigo, nombre) => ({ codigo, nombre })
const validacion = (imagenId, estado, detectada = 'SUPERFRUTOS', proveedor = 'simulado') => ({
  id: 1, imagenId, estado, lineaEsperada: linea('SUPERFRUTOS', 'Superfrutos'),
  linea: estado === 'SOSPECHOSA' ? linea(detectada, 'Artesanías') : linea('SUPERFRUTOS', 'Superfrutos'), proveedor,
})
const conFotos = (...fotos) => ({ ...copia(DATOS.edicion), imagenes: fotos })
const archivo = (nombre = 'foto.jpg') => new File([new Uint8Array(10)], nombre, { type: 'image/jpeg' })

function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/productos/9/lotes': () => [200, copia(DATOS.lotes)],
    'GET /api/productor/ubicaciones': () => [200, copia(DATOS.ubicaciones)],
    'GET /api/productor/imagenes/validaciones': [200, []],
    ...rutas,
  })
}

const comparacion = (imagenId, proveedor = 'simulado') => ({
  mejoraId: 'm1', imagenId, estado: 'PENDIENTE',
  original: { url: `/api/imagenes/${imagenId}`, urlMiniatura: `/api/imagenes/${imagenId}?tam=min`, ancho: 400, alto: 300 },
  mejorada: { url: '/api/productor/mejoras/m1', urlMiniatura: '/api/productor/mejoras/m1?tam=min', ancho: 400, alto: 300 },
  ajustes: ['AUTOCONTRASTE', 'NITIDEZ'], proveedor,
})

describe('PW-04 Validacion de fotos con IA (CCPIP-144, 168, 364)', () => {
  it('muestra la recomendacion de cada foto, el chip Revisar y reemplaza desde el aviso', async () => {
    let reemplazada = false
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true), fotoDe(reemplazada ? D : B))],
      'GET /api/productor/imagenes/validaciones': () => [200, [validacion(A, 'VALIDA'), validacion(B, 'SOSPECHOSA', 'ARTESANIA')]],
      [`POST /api/productor/imagenes/${B}/reemplazo`]: () => {
        reemplazada = true
        return [201, fotoDe(D)]
      },
      [`POST /api/productor/imagenes/${D}/validacion`]: [200, validacion(D, 'VALIDA')],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')

    expect(await screen.findByText('Foto 1 · Imagen válida')).toBeInTheDocument()
    expect(screen.getByText('Corresponde a la categoría Superfrutos.')).toBeInTheDocument()
    expect(screen.getByText('Foto 2 · Posible imagen incorrecta')).toBeInTheDocument()
    expect(screen.getByText('Parece de la categoría Artesanías, no de Superfrutos. Revísala antes de publicar.')).toBeInTheDocument()
    // RF-137: solo recomienda, y el simulador se presenta como tal
    expect(screen.getByText('Es solo una recomendación: puedes publicar con esta foto.')).toBeInTheDocument()
    expect(screen.getAllByText('Validación de prueba')).toHaveLength(2)

    // El chip sobre la miniatura lleva al aviso
    await user.click(screen.getByRole('button', { name: 'Revisar' }))
    expect(document.getElementById(`aviso-foto-${B}`)).toHaveFocus()

    // CCPIP-364: "Reemplazar imagen" usa el mismo reemplazo y la nueva se valida
    await user.upload(screen.getByLabelText('Reemplazar imagen'), archivo('aceite.jpg'))
    expect(await screen.findByText('Foto 2 · Imagen válida')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST /api/productor/imagenes/${B}/reemplazo`)).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Revisar' })).not.toBeInTheDocument()
    expect(screen.queryByText('Foto 2 · Posible imagen incorrecta')).not.toBeInTheDocument()
  })

  it('una foto nueva se valida al subirla y se ve mientras se analiza', async () => {
    let subida = false
    let terminar
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, conFotos(...(subida ? [fotoDe(C, true)] : []))],
      'POST /api/productor/imagenes': () => {
        subida = true
        return [201, fotoDe(C, true)]
      },
      [`POST /api/productor/imagenes/${C}/validacion`]: () => new Promise((resolver) => { terminar = () => resolver([200, validacion(C, 'VALIDA')]) }),
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.upload(await screen.findByLabelText('Arrastra fotos aquí o selecciónalas'), archivo())

    expect(await screen.findByText('Foto 1 · Analizando')).toBeInTheDocument()
    expect(screen.getByText('Verificamos que la foto corresponda a la categoría Superfrutos.')).toBeInTheDocument()
    terminar()
    expect(await screen.findByText('Foto 1 · Imagen válida')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST /api/productor/imagenes/${C}/validacion`)).toHaveLength(1)
  })

  it('una foto subida antes de elegir la linea no se manda a validar y pide elegirla', async () => {
    let subida = false
    const sinLineas = () => ({ ...conFotos(...(subida ? [fotoDe(C, true)] : [])), lineas: [] })
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, sinLineas()],
      'POST /api/productor/imagenes': () => {
        subida = true
        return [201, fotoDe(C, true)]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.upload(await screen.findByLabelText('Arrastra fotos aquí o selecciónalas'), archivo())

    expect(await screen.findByText('Elige la línea del producto y guarda para validar sus fotos.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST /api/productor/imagenes/${C}/validacion`)).toHaveLength(0)
  })

  it('sin IA disponible lo dice, no bloquea y conserva la foto original', async () => {
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true))],
      [`POST /api/productor/imagenes/${A}/validacion`]: [503, { code: 'IA_NO_DISPONIBLE', error: 'No pudimos analizar la imagen en este momento.' }],
      [`POST /api/productor/imagenes/${A}/mejora`]: [503, { code: 'IA_NO_DISPONIBLE', error: 'No pudimos analizar la imagen en este momento.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')

    expect(await screen.findByText('Aún no verificamos si corresponde a la categoría del producto.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Validar foto' }))
    expect(await screen.findByText('Foto 1 · Validación no disponible')).toBeInTheDocument()
    expect(screen.getByText('No pudimos analizarla en este momento. Puedes continuar con tu foto original.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Validar otra vez' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Mejorar con IA' }))
    expect(await screen.findByText('No pudimos mejorar la foto ahora')).toBeInTheDocument()
    expect(screen.getByText('Tu foto original se conserva y puedes publicar igual.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Compara la foto mejorada' })).not.toBeInTheDocument()
    expect(screen.getByAltText('Foto del producto')).toHaveAttribute('src', `/api/imagenes/${A}?tam=min`)
    expect(fetchMock.mock.calls.some(([u]) => u.includes('/reemplazo') || u.includes('/aceptar'))).toBe(false)
  })

  it('un error guardado de la red invita a reintentar', async () => {
    productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true))],
      'GET /api/productor/imagenes/validaciones': [200, [{ ...validacion(A, 'ERROR'), linea: null }]],
      [`POST /api/productor/imagenes/${A}/validacion`]: [200, validacion(A, 'VALIDA')],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    expect(await screen.findByText('Foto 1 · No pudimos analizar la imagen')).toBeInTheDocument()
    expect(screen.getByText('Revisa tu conexión e inténtalo otra vez. Puedes continuar con tu foto.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Validar otra vez' }))
    expect(await screen.findByText('Foto 1 · Imagen válida')).toBeInTheDocument()
  })

  it('sin linea elegida pide elegirla para validar', async () => {
    productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true))],
      [`POST /api/productor/imagenes/${A}/validacion`]: [422, { code: 'LINEA_REQUERIDA', error: 'Elige la línea del producto para validar sus fotos.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Validar foto' }))
    expect(await screen.findByText('Elige la línea del producto y guarda para validar sus fotos.')).toBeInTheDocument()
  })

  it('en ingles', async () => {
    await i18n.changeLanguage('en')
    productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true), fotoDe(B))],
      'GET /api/productor/imagenes/validaciones': () => [200, [validacion(A, 'VALIDA', 'SUPERFRUTOS', 'otro'), validacion(B, 'SOSPECHOSA', 'ARTESANIA')]],
    })
    abrirEn('/productor/productos/9')
    expect(await screen.findByText('Photo 1 · Valid image')).toBeInTheDocument()
    expect(screen.getByText('Photo 2 · Possibly the wrong image')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review' })).toBeInTheDocument()
    expect(screen.getByText('Replace image')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Enhance with AI' })).toHaveLength(2)
    // Solo la validacion simulada lleva el aviso de prueba
    expect(screen.getAllByText('Test check')).toHaveLength(1)
  })
})

describe('PW-04 Mejora opcional con IA (CCPIP-176, 272, 281, 282)', () => {
  it('compara la original con la mejorada y usar la mejorada la pone en su lugar', async () => {
    let aceptada = false
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(aceptada ? E : A, true))],
      [`POST /api/productor/imagenes/${A}/mejora`]: [201, comparacion(A)],
      [`POST /api/productor/imagenes/${A}/mejora/aceptar`]: () => {
        aceptada = true
        return [200, fotoDe(E, true)]
      },
      [`POST /api/productor/imagenes/${E}/validacion`]: [200, validacion(E, 'VALIDA')],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')

    // Nada se pide sin una eleccion expresa
    await screen.findByText('Foto 1')
    expect(llamadas(fetchMock, `POST /api/productor/imagenes/${A}/mejora`)).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Mejorar con IA' }))
    const dialogo = within(await screen.findByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Compara la foto mejorada' })).toBeInTheDocument()
    expect(dialogo.getByAltText('Foto original')).toHaveAttribute('src', `/api/imagenes/${A}`)
    expect(dialogo.getByAltText('Foto mejorada')).toHaveAttribute('src', '/api/productor/mejoras/m1')
    expect(dialogo.getByText(/no se agrega nada a tu producto/)).toBeInTheDocument()
    expect(dialogo.getByText(/Mejora de prueba/)).toBeInTheDocument()
    // Hasta elegir, la ficha sigue con la original
    expect(screen.getByAltText('Foto del producto')).toHaveAttribute('src', `/api/imagenes/${A}?tam=min`)

    await user.click(dialogo.getByRole('button', { name: 'Usar la mejorada' }))
    expect(await screen.findByText('Usamos la foto mejorada')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByAltText('Foto del producto')).toHaveAttribute('src', `/api/imagenes/${E}?tam=min`))
    expect(await screen.findByText('Foto 1 · Imagen válida')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST /api/productor/imagenes/${A}/mejora/aceptar`)).toHaveLength(1)
    expect(llamadas(fetchMock, `POST /api/productor/imagenes/${A}/mejora/rechazar`)).toHaveLength(0)
  })

  it('mientras prepara la mejora, el enlace muestra el aro de carga en lugar del icono (CCPIP-413)', async () => {
    let responder
    productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true))],
      [`POST /api/productor/imagenes/${A}/mejora`]: () => new Promise((r) => { responder = r }),
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    const mejorar = await screen.findByRole('button', { name: 'Mejorar con IA' })
    expect(mejorar.querySelector('.boton__giro')).toBeNull()
    await user.click(mejorar)

    const preparando = screen.getByRole('button', { name: 'Preparando la mejora…' })
    expect(preparando).toBeDisabled()
    expect(preparando).toHaveAttribute('aria-busy', 'true')
    expect(preparando.querySelector('.boton__giro')).not.toBeNull()
    expect(preparando.querySelector('.icono')).toBeNull()
    responder([201, comparacion(A)])
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  it('cerrar no decide y conservar la original la rechaza', async () => {
    const fetchMock = productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true))],
      [`POST /api/productor/imagenes/${A}/mejora`]: [201, comparacion(A, 'otro')],
      [`POST /api/productor/imagenes/${A}/mejora/rechazar`]: [204],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')

    await user.click(await screen.findByRole('button', { name: 'Mejorar con IA' }))
    expect(within(await screen.findByRole('dialog')).queryByText(/Mejora de prueba/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Decidir después' }))
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Compara la foto mejorada' })).not.toBeInTheDocument())
    expect(fetchMock.mock.calls.some(([u]) => u.includes('/mejora/'))).toBe(false)

    // Pedirla otra vez trae la misma propuesta pendiente
    await user.click(screen.getByRole('button', { name: 'Mejorar con IA' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Conservar la original' }))
    expect(await screen.findByText('Conservaste tu foto original')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST /api/productor/imagenes/${A}/mejora/rechazar`)).toHaveLength(1)
    expect(llamadas(fetchMock, `POST /api/productor/imagenes/${A}/mejora/aceptar`)).toHaveLength(0)
    expect(screen.getByAltText('Foto del producto')).toHaveAttribute('src', `/api/imagenes/${A}?tam=min`)
  })

  it('si no se puede guardar la eleccion lo avisa y la original sigue', async () => {
    productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true))],
      [`POST /api/productor/imagenes/${A}/mejora`]: [201, comparacion(A)],
      [`POST /api/productor/imagenes/${A}/mejora/aceptar`]: [404, { code: 'SIN_MEJORA_PENDIENTE', error: 'No hay una versión mejorada pendiente para esta foto.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Mejorar con IA' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Usar la mejorada' }))
    expect(await screen.findByText('No hay una versión mejorada pendiente para esta foto.')).toBeInTheDocument()
    expect(screen.getByAltText('Foto del producto')).toHaveAttribute('src', `/api/imagenes/${A}?tam=min`)
  })
})

describe('PW-04 Fotos y borrador mientras esperan respuesta (CCPIP-413)', () => {
  it('reemplazar desde el aviso muestra el aro en ese enlace y en el lápiz de la miniatura', async () => {
    let responder
    productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true), fotoDe(B))],
      'GET /api/productor/imagenes/validaciones': () => [200, [validacion(A, 'VALIDA'), validacion(B, 'SOSPECHOSA', 'ARTESANIA')]],
      [`POST /api/productor/imagenes/${B}/reemplazo`]: () => new Promise((r) => { responder = r }),
      [`POST /api/productor/imagenes/${D}/validacion`]: [200, validacion(D, 'VALIDA')],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.upload(await screen.findByLabelText('Reemplazar imagen'), archivo('aceite.jpg'))

    const enlace = screen.getByLabelText('Reemplazar imagen').closest('label')
    expect(enlace).toHaveAttribute('aria-busy', 'true')
    expect(enlace.querySelector('.boton__giro')).not.toBeNull()
    const lapices = screen.getAllByTitle('Reemplazar foto')
    expect(lapices[1]).toHaveAttribute('aria-busy', 'true')
    expect(lapices[1].querySelector('.boton__giro')).not.toBeNull()
    expect(lapices[0].querySelector('.boton__giro')).toBeNull()
    responder([201, fotoDe(D)])
    await waitFor(() => expect(document.querySelector('.boton__giro')).toBeNull())
  })

  it('conservar la original pone el aro en "Conservar" y no en "Usar la mejorada"', async () => {
    let responder
    productor({
      'GET /api/productor/productos/9': () => [200, conFotos(fotoDe(A, true))],
      [`POST /api/productor/imagenes/${A}/mejora`]: [201, comparacion(A)],
      [`POST /api/productor/imagenes/${A}/mejora/rechazar`]: () => new Promise((r) => { responder = r }),
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Mejorar con IA' }))
    const dialogo = await screen.findByRole('dialog')
    const conservar = within(dialogo).getByRole('button', { name: 'Conservar la original' })
    const usar = within(dialogo).getByRole('button', { name: 'Usar la mejorada' })
    await user.click(conservar)

    expect(conservar).toHaveAttribute('aria-busy', 'true')
    expect(conservar.querySelector('.boton__giro')).not.toBeNull()
    expect(usar).toBeDisabled()
    expect(usar.querySelector('.boton__giro')).toBeNull()
    responder([204])
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Compara la foto mejorada' })).not.toBeInTheDocument())
  })

  it('mientras la IA prepara el borrador de la descripción se ve el indicador de carga', async () => {
    let responder
    productor({
      'GET /api/productor/productos/9': () => [200, conFotos()],
      'POST /api/productor/ia/descripcion': () => new Promise((r) => { responder = r }),
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    await user.click(await screen.findByRole('button', { name: 'Sugerir una descripción con IA (borrador)' }))

    const estado = (await screen.findByText('Preparando un borrador…')).closest('[role="status"]')
    expect(estado.querySelector('.cargando__giro')).not.toBeNull()
    responder([200, { texto: 'Aceite prensado en frío.' }])
    expect(await screen.findByText('Borrador asistido por IA · revisa antes de publicar')).toBeInTheDocument()
  })
})
