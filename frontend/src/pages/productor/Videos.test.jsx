import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const productor = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/tienda/traducciones/en': [200, { idioma: 'en', automaticaDisponible: true, campos: [] }],
    'GET /api/productor/videos': [200, []],
    ...rutas,
  })

// Armado segun VideosService.Video del backend
const VIDEO = {
  id: 4, titulo: 'Cosecha de cacao', formato: 'MP4', bytes: 1048576, duracionSegundos: 95.5, estado: 'EN_REVISION',
  motivo: null, subidoEn: '2026-10-26T15:00:00Z', decididoEn: null, reemplazaA: null, url: '/api/videos/4',
}

const archivo = (nombre = 'cosecha.mp4', tipo = 'video/mp4', bytes = 1024) => new File([new Uint8Array(bytes)], nombre, { type: tipo })
const videos = async () => within(await screen.findByRole('region', { name: 'Videos' }))

describe('CCPIP-118 Videos de la tienda', () => {
  it('sube un video propio, que queda en revisión', async () => {
    let subido = false
    const fetchMock = productor({
      'GET /api/productor/videos': () => [200, subido ? [copia(VIDEO)] : []],
      'POST /api/productor/videos': () => {
        subido = true
        return [201, copia(VIDEO)]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    const v = await videos()
    expect(v.getByText(/Hasta 5 videos · MP4 o WebM · 50 MB y 3 min/)).toBeInTheDocument()
    expect(await v.findByText('Aún no subiste videos.')).toBeInTheDocument()

    // Sin titulo ni archivo no se envia
    await user.click(v.getByRole('button', { name: 'Subir video' }))
    expect(v.getByText('Escribe un título para el video')).toBeInTheDocument()
    expect(v.getByText('Elige un video')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/videos')).toHaveLength(0)

    await user.type(v.getByLabelText('Título del video'), '  Cosecha de cacao ')
    await user.upload(v.getByLabelText('Elegir video'), archivo())
    expect(v.getByText('cosecha.mp4 · 0 MB')).toBeInTheDocument()
    await user.click(v.getByRole('button', { name: 'Subir video' }))

    expect(await v.findByText('Video enviado a revisión')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, 'POST /api/productor/videos')
    expect(opciones.body.get('titulo')).toBe('Cosecha de cacao')
    expect(opciones.body.get('archivo').name).toBe('cosecha.mp4')
    expect(v.getByLabelText('Título del video')).toHaveValue('')
    // CCPIP-390: aparece en su lista, en revision
    const item = within(await v.findByRole('list', { name: 'Videos de tu tienda' })).getByRole('listitem')
    expect(within(item).getByText('En revisión')).toBeInTheDocument()
    expect(within(item).getByText('1:36 · MP4 · 1 MB')).toBeInTheDocument()
  })

  it('CCPIP-390: ve sus videos con su revisión y el motivo si se rechazaron', async () => {
    productor({
      'GET /api/productor/videos': () => [200, [
        { ...copia(VIDEO), id: 1, titulo: 'Cosecha de cacao', estado: 'PUBLICADO', motivo: 'Muestra su trabajo', decididoEn: '2026-10-26T16:00:00Z' },
        { ...copia(VIDEO), id: 2, titulo: 'Telar', formato: 'WEBM', estado: 'RECHAZADO', motivo: 'Se ve el logo de otra marca.', decididoEn: '2026-10-26T16:00:00Z' },
      ]],
    })
    abrirEn('/productor/tienda')
    const items = within(await (await videos()).findByRole('list', { name: 'Videos de tu tienda' })).getAllByRole('listitem')
    expect(within(items[0]).getByText('Se ve en tu tienda')).toBeInTheDocument()
    expect(within(items[0]).queryByText(/Motivo de la Cámara/)).not.toBeInTheDocument()
    expect(within(items[0]).getByLabelText('Cosecha de cacao')).toHaveAttribute('src', '/api/videos/4')
    expect(within(items[1]).getByText('No publicado')).toBeInTheDocument()
    expect(within(items[1]).getByText('Motivo de la Cámara: «Se ve el logo de otra marca.»')).toBeInTheDocument()
  })

  it('CCPIP-390: con cinco videos no ofrece subir otro', async () => {
    productor({ 'GET /api/productor/videos': () => [200, [1, 2, 3, 4, 5].map((id) => ({ ...copia(VIDEO), id, estado: 'PUBLICADO' }))] })
    abrirEn('/productor/tienda')
    const v = await videos()
    expect(await v.findByText('Tu tienda ya tiene 5 videos. Retira uno para subir otro.')).toBeInTheDocument()
    expect(v.queryByRole('button', { name: 'Subir video' })).not.toBeInTheDocument()
  })

  it('CCPIP-390: si no puede cargar sus videos, lo dice', async () => {
    productor({ 'GET /api/productor/videos': new TypeError('Failed to fetch') })
    abrirEn('/productor/tienda')
    expect(await (await videos()).findByText('No pudimos cargar tus videos')).toBeInTheDocument()
  })

  it('no envía lo que no es MP4 o WebM ni pesa más de 50 MB', async () => {
    const fetchMock = productor()
    const user = userEvent.setup({ applyAccept: false })
    abrirEn('/productor/tienda')
    const v = await videos()
    await user.type(v.getByLabelText('Título del video'), 'Telar')
    await user.upload(v.getByLabelText('Elegir video'), archivo('telar.mov', 'video/quicktime'))
    await user.click(v.getByRole('button', { name: 'Subir video' }))
    expect(v.getByText('Elige un video MP4 o WebM')).toBeInTheDocument()

    const pesado = archivo('telar.mp4')
    Object.defineProperty(pesado, 'size', { value: 51 * 1024 * 1024 })
    await user.upload(v.getByLabelText('Cambiar video'), pesado)
    await user.click(v.getByRole('button', { name: 'Subir video' }))
    expect(v.getByText('El video pesa más de 50 MB')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/videos')).toHaveLength(0)
  })

  it('si el servidor lo rechaza o se corta, lo dice y conserva el video elegido', async () => {
    let intento = 0
    productor({
      'POST /api/productor/videos': () => {
        intento += 1
        if (intento === 1) return [422, { code: 'VIDEO_MUY_LARGO', error: 'El video dura más de 3 minutos. Recórtalo e inténtalo otra vez.' }]
        return new TypeError('Failed to fetch')
      },
    })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    const v = await videos()
    await user.type(v.getByLabelText('Título del video'), 'Cosecha')
    await user.upload(v.getByLabelText('Elegir video'), archivo())
    await user.click(v.getByRole('button', { name: 'Subir video' }))
    expect(await v.findByText('El video dura más de 3 minutos. Recórtalo e inténtalo otra vez.')).toBeInTheDocument()

    await user.click(v.getByRole('button', { name: 'Subir video' }))
    expect(await v.findByText('Se cortó la conexión. Tu video sigue elegido: inténtalo otra vez.')).toBeInTheDocument()
    expect(v.getByText('cosecha.mp4 · 0 MB')).toBeInTheDocument()
    expect(v.getByLabelText('Título del video')).toHaveValue('Cosecha')
  })

  it('CCPIP-391: reemplaza un video publicado; el actual sigue hasta que se apruebe el nuevo', async () => {
    const publicado = { ...copia(VIDEO), id: 1, titulo: 'Cosecha de cacao', estado: 'PUBLICADO', motivo: 'Bien', decididoEn: '2026-10-26T16:00:00Z' }
    const reemplazo = { ...copia(VIDEO), id: 7, titulo: 'Cosecha de cacao', reemplazaA: 1 }
    let lista = [publicado]
    const fetchMock = productor({
      'GET /api/productor/videos': () => [200, copia(lista)],
      'POST /api/productor/videos/1/reemplazo': () => {
        lista = [publicado, reemplazo]
        return [201, copia(reemplazo)]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    const v = await videos()
    await user.upload(await v.findByLabelText('Reemplazar el video Cosecha de cacao'), archivo('nuevo.webm', 'video/webm'))

    expect(await v.findByText('Reemplazo enviado a revisión')).toBeInTheDocument()
    expect(v.getByText('Tu tienda sigue mostrando el video actual hasta que la Cámara apruebe el nuevo.')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, 'POST /api/productor/videos/1/reemplazo')
    expect(opciones.body.get('archivo').name).toBe('nuevo.webm')
    const items = within(v.getByRole('list', { name: 'Videos de tu tienda' })).getAllByRole('listitem')
    expect(within(items[0]).getByText('Su reemplazo está en revisión')).toBeInTheDocument()
    expect(within(items[0]).queryByLabelText(/Reemplazar el video/)).not.toBeInTheDocument()
    expect(within(items[1]).getByText('Reemplaza a «Cosecha de cacao»')).toBeInTheDocument()
  })

  it('CCPIP-391: no envía un reemplazo que no es video y muestra el error del servidor', async () => {
    const fetchMock = productor({
      'GET /api/productor/videos': () => [200, [{ ...copia(VIDEO), id: 1, estado: 'RECHAZADO', motivo: 'Logo ajeno' }]],
      'POST /api/productor/videos/1/reemplazo': [422, { code: 'VIDEO_MUY_LARGO', error: 'El video dura más de 3 minutos. Recórtalo e inténtalo otra vez.' }],
    })
    const user = userEvent.setup({ applyAccept: false })
    abrirEn('/productor/tienda')
    const v = await videos()
    await user.upload(await v.findByLabelText('Reemplazar el video Cosecha de cacao'), archivo('foto.png', 'image/png'))
    expect(await v.findByText('Elige un video MP4 o WebM')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/videos/1/reemplazo')).toHaveLength(0)

    await user.upload(v.getByLabelText('Reemplazar el video Cosecha de cacao'), archivo())
    expect(await v.findByText('El video dura más de 3 minutos. Recórtalo e inténtalo otra vez.')).toBeInTheDocument()
    expect(v.getByText('No pudimos reemplazar el video')).toBeInTheDocument()
  })

  it('la tienda pública muestra los videos aprobados', async () => {
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': SIN_SESION,
      'GET /api/tiendas/2': [200, { ...copia(DATOS.tienda), videos: [{ id: 4, titulo: 'Cosecha de cacao', duracionSegundos: 95.5, url: '/api/videos/4' }] }],
    })
    abrirEn('/tiendas/2')
    const seccion = within(await screen.findByRole('region', { name: 'Videos' }))
    const video = seccion.getByLabelText('Cosecha de cacao')
    expect(video.tagName).toBe('VIDEO')
    expect(video).toHaveAttribute('src', '/api/videos/4')
    expect(video).toHaveAttribute('preload', 'metadata')
  })

  it('una tienda sin videos no muestra la sección', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'GET /api/tiendas/2': [200, { ...copia(DATOS.tienda), videos: [] }] })
    abrirEn('/tiendas/2')
    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('region', { name: 'Videos' })).not.toBeInTheDocument()
  })
})
