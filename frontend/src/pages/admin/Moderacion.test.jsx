import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const BANDEJA = '/api/admin/moderacion'
const EXPEDIENTE = `${BANDEJA}/7`

// Armados segun ModeracionModelos (Bandeja, Expediente) del backend
const SOLICITUDES = {
  contadores: { pendientes: 2, aprobadasMes: 4, rechazadasMes: 1 },
  solicitudes: [
    {
      id: 7, estado: 'PENDIENTE', productoId: 21, producto: 'Miel de abeja melipona 250 g', linea: 'Mieles', productorId: 3,
      organizacion: 'Asoc. Nii Joi', pueblo: 'Asháninka', region: 'Junín', republicacion: false, autorizacion: 'APROBADA',
      autorizada: true, solicitadaEn: '2026-10-25T15:00:00Z', decididaEn: null,
    },
    {
      id: 8, estado: 'PENDIENTE', productoId: 22, producto: 'Chocolate 70 % cacao chuncho', linea: 'Cacao y chocolates', productorId: 5,
      organizacion: 'C.N. Puerto Alegre', pueblo: null, region: 'Cusco', republicacion: true, autorizacion: 'SOLICITADA',
      autorizada: false, solicitadaEn: '2026-10-26T12:00:00Z', decididaEn: null,
    },
  ],
}

const EVIDENCIA = {
  id: 'b1f2', productoId: 21, tipo: 'CERTIFICADO', titulo: 'Certificado orgánico', emisor: 'Control Union', vigenteDesde: null,
  vigenteHasta: '2027-06-30', vencida: false, nombreArchivo: 'organico.pdf', formato: 'PDF', bytes: 1200,
  subidaEn: '2026-10-01T12:00:00Z', url: '/api/admin/autorizaciones/31/evidencias/b1f2/archivo',
}

const EXP = {
  id: 7, estado: 'PENDIENTE', solicitadaEn: '2026-10-25T15:00:00Z', solicitadaPor: 'María Shirampari', republicacion: false,
  oferta: {
    id: 21, nombre: 'Miel de abeja melipona 250 g', descripcion: 'Miel de abejas sin aguijón del bosque de Pichanaki.',
    lineas: ['Mieles', 'Superfrutos'], unidad: 'Frasco', contenido: '250 g', modalidad: 'AMBAS', precio: 38, precioMayorista: 32,
    cantidadMinimaMayorista: 12, enVenta: 40, historiaTitulo: 'La miel del monte', historia: 'Doce familias cuidan las colmenas.',
    imagenes: [{ id: 'i1', url: '/img/1.webp', urlMiniatura: '/img/1-m.webp' }],
  },
  productor: { id: 3, organizacion: 'Asoc. Nii Joi', comunidad: 'C.N. Nii Joi', pueblo: 'Asháninka', region: 'Junín' },
  autorizacion: { id: 31, estado: 'APROBADA', vigenteHasta: '2027-06-30', vigente: true, evidencias: [EVIDENCIA] },
  historial: [{ id: 7, estado: 'PENDIENTE', solicitadaEn: '2026-10-25T15:00:00Z', solicitadaPor: 'María Shirampari', motivo: null, decididaPor: null, decididaEn: null }],
  faltan: [],
  puedeDecidir: true,
  puedeAprobar: true,
}

const comoSub = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUB],
    [`GET ${BANDEJA}`]: () => [200, copia(SOLICITUDES)],
    [`GET ${EXPEDIENTE}`]: () => [200, copia(EXP)],
    ...rutas,
  })

const decision = async () => within(await screen.findByRole('complementary', { name: 'Decisión' }))

afterEach(async () => {
  await i18n.changeLanguage('es')
})

describe('CCPIP-46 Moderación de publicaciones', () => {
  it('lista las ofertas por revisar con su autorización de venta', async () => {
    comoSub()
    const user = userEvent.setup()
    abrirEn('/admin/autorizaciones')
    await user.click(await screen.findByRole('link', { name: 'Moderación' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Moderación de publicaciones' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Por revisar' })).getByText('2')).toBeInTheDocument()

    const filas = within(await screen.findByRole('table', { name: 'Pendientes' })).getAllByRole('row')
    expect(filas).toHaveLength(3)
    expect(within(filas[1]).getByText('Miel de abeja melipona 250 g')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Autorizada')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Nueva')).toBeInTheDocument()
    // Sin autorizacion vigente se ve: aprobar la publicacion lo exige (CCPIP-331)
    expect(within(filas[2]).getByText('En revisión')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Republicación')).toBeInTheDocument()
    expect(within(filas[1]).getByRole('link', { name: 'Revisar la publicación de Miel de abeja melipona 250 g de Asoc. Nii Joi' }))
      .toHaveAttribute('href', '/admin/moderacion/7')
  })

  it('cambia a las decididas', async () => {
    const fetchMock = comoSub({ [`GET ${BANDEJA}?estado=RECHAZADA`]: () => [200, { ...copia(SOLICITUDES), solicitudes: [] }] })
    const user = userEvent.setup()
    abrirEn('/admin/moderacion')
    await screen.findByRole('table', { name: 'Pendientes' })
    await user.click(screen.getByRole('tab', { name: 'Rechazadas' }))
    expect(await screen.findByText('No hay publicaciones rechazadas')).toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${BANDEJA}?estado=RECHAZADA`)).toHaveLength(1)
  })

  it('el expediente muestra la oferta como se publicaría, su autorización y sus evidencias', async () => {
    comoSub()
    abrirEn('/admin/moderacion/7')
    expect(await screen.findByRole('heading', { level: 1, name: 'Asoc. Nii Joi · Miel de abeja melipona 250 g' })).toBeInTheDocument()
    expect(screen.getByText(/Enviada por María Shirampari/)).toBeInTheDocument()

    const oferta = within(screen.getByRole('region', { name: 'La oferta' }))
    expect(oferta.getByRole('img', { name: 'Foto 1 de Miel de abeja melipona 250 g' })).toHaveAttribute('src', '/img/1-m.webp')
    expect(oferta.getByText('Mieles, Superfrutos')).toBeInTheDocument()
    expect(oferta.getByText('Frasco · 250 g')).toBeInTheDocument()
    expect(oferta.getByText('Minorista y mayorista')).toBeInTheDocument()
    expect(oferta.getByText(/S\/\s38\.00/)).toBeInTheDocument()
    expect(oferta.getByText(/S\/\s32\.00 desde 12 und/)).toBeInTheDocument()
    expect(oferta.getByText('40 und')).toBeInTheDocument()
    expect(oferta.getByRole('heading', { name: 'La miel del monte' })).toBeInTheDocument()

    const autorizacion = within(screen.getByRole('region', { name: 'Autorización de venta' }))
    expect(autorizacion.getByText('Autorizada')).toBeInTheDocument()
    expect(autorizacion.getByRole('link', { name: 'Ver la autorización' })).toHaveAttribute('href', '/admin/autorizaciones/31')
    expect(autorizacion.getByRole('link', { name: /Certificado orgánico/ })).toHaveAttribute('href', EVIDENCIA.url)

    expect((await decision()).getByText('Cumple lo necesario para publicarse')).toBeInTheDocument()
  })

  it('dice qué falta si la oferta no se podría aprobar', async () => {
    comoSub({
      [`GET ${EXPEDIENTE}`]: () => [200, {
        ...copia(EXP), puedeAprobar: false,
        autorizacion: { id: null, estado: 'SIN_SOLICITUD', vigenteHasta: null, vigente: false, evidencias: [] },
        faltan: ['Una autorización de venta vigente para este producto', 'Un lote vigente con stock en un punto de acopio'],
      }],
    })
    abrirEn('/admin/moderacion/7')
    const d = await decision()
    expect(d.getByText('Una autorización de venta vigente para este producto')).toBeInTheDocument()
    expect(d.getByText('Un lote vigente con stock en un punto de acopio')).toBeInTheDocument()
    const autorizacion = within(screen.getByRole('region', { name: 'Autorización de venta' }))
    expect(autorizacion.getByText('Sin solicitud')).toBeInTheDocument()
    expect(autorizacion.queryByRole('link', { name: 'Ver la autorización' })).not.toBeInTheDocument()
  })

  it('CCPIP-331: aprueba con motivo y la oferta pasa al catálogo', async () => {
    const aprobada = {
      ...copia(EXP), estado: 'APROBADA', puedeDecidir: false, puedeAprobar: false,
      historial: [{ ...EXP.historial[0], estado: 'APROBADA', motivo: 'Fotos y precios conformes', decididaPor: 'Lucía Paredes', decididaEn: '2026-10-26T15:00:00Z' }],
    }
    const fetchMock = comoSub({ [`POST ${EXPEDIENTE}/aprobar`]: () => [200, aprobada] })
    const user = userEvent.setup()
    abrirEn('/admin/moderacion/7')
    const d = await decision()

    // Sin motivo no se abre el dialogo
    await user.click(d.getByRole('button', { name: 'Aprobar publicación' }))
    expect(d.getByText('Escribe el motivo de la decisión')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await user.type(d.getByLabelText('Motivo de la decisión'), 'Fotos y precios conformes')
    await user.click(d.getByRole('button', { name: 'Aprobar publicación' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Aprobar la publicación de Miel de abeja melipona 250 g' })).toBeInTheDocument()
    expect(dialogo.getByText('Motivo: «Fotos y precios conformes»')).toBeInTheDocument()
    expect(dialogo.getByText(/Asoc\. Nii Joi recibe tu motivo/)).toBeInTheDocument()
    await user.click(dialogo.getByRole('button', { name: 'Aprobar' }))

    expect(await screen.findByText('Aprobaste la publicación. La oferta ya está en el catálogo.')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${EXPEDIENTE}/aprobar`)
    expect(JSON.parse(opciones.body)).toEqual({ motivo: 'Fotos y precios conformes' })
    expect((await decision()).getByText('Esta publicación ya se aprobó.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Aprobar publicación' })).not.toBeInTheDocument()
  })

  it('CCPIP-331: no se aprueba lo que no cumple, y si cambió al decidir se vuelve a cargar', async () => {
    let cambio = false
    const fetchMock = comoSub({
      [`GET ${EXPEDIENTE}`]: () => [200, cambio
        ? { ...copia(EXP), puedeAprobar: false, faltan: ['Una autorización de venta vigente para este producto'] }
        : copia(EXP)],
      [`POST ${EXPEDIENTE}/aprobar`]: () => {
        cambio = true
        return [409, { code: 'OFERTA_NO_APROBABLE', error: 'La oferta todavía no se puede publicar.', detalles: ['Una autorización de venta vigente para este producto'] }]
      },
    })
    const user = userEvent.setup()
    abrirEn('/admin/moderacion/7')
    const d = await decision()
    await user.type(d.getByLabelText('Motivo de la decisión'), 'Conforme')
    await user.click(d.getByRole('button', { name: 'Aprobar publicación' }))
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Aprobar' }))

    expect(await screen.findByText('La oferta todavía no se puede publicar.')).toBeInTheDocument()
    expect(await d.findByText('Una autorización de venta vigente para este producto')).toBeInTheDocument()
    expect(d.getByText('Para aprobar, la oferta debe cumplir todo lo de la lista.')).toBeInTheDocument()
    expect(d.getByRole('button', { name: 'Aprobar publicación' })).toBeDisabled()
    expect(llamadas(fetchMock, `GET ${EXPEDIENTE}`)).toHaveLength(2)
  })

  it('CCPIP-332: rechaza con motivo aunque la oferta no se pueda aprobar', async () => {
    const rechazada = {
      ...copia(EXP), estado: 'RECHAZADA', puedeDecidir: false, puedeAprobar: false,
      historial: [{ ...EXP.historial[0], estado: 'RECHAZADA', motivo: 'Las fotos no muestran el producto', decididaPor: 'Lucía Paredes', decididaEn: '2026-10-26T15:00:00Z' }],
    }
    const fetchMock = comoSub({
      [`GET ${EXPEDIENTE}`]: () => [200, { ...copia(EXP), puedeAprobar: false, faltan: ['Un lote vigente con stock en un punto de acopio'] }],
      [`POST ${EXPEDIENTE}/rechazar`]: () => [200, rechazada],
    })
    const user = userEvent.setup()
    abrirEn('/admin/moderacion/7')
    const d = await decision()
    expect(d.getByRole('button', { name: 'Aprobar publicación' })).toBeDisabled()
    await user.type(d.getByLabelText('Motivo de la decisión'), 'Las fotos no muestran el producto')
    await user.click(d.getByRole('button', { name: 'Rechazar publicación' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Rechazar la publicación de Miel de abeja melipona 250 g' })).toBeInTheDocument()
    expect(dialogo.getByText(/La oferta vuelve a Asoc\. Nii Joi, que verá este motivo/)).toBeInTheDocument()
    await user.click(dialogo.getByRole('button', { name: 'Rechazar publicación' }))

    expect(await screen.findByText('Rechazaste la publicación. El productor verá tu motivo.')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${EXPEDIENTE}/rechazar`)
    expect(JSON.parse(opciones.body)).toEqual({ motivo: 'Las fotos no muestran el producto' })
    expect((await decision()).getByText('Esta publicación se rechazó.')).toBeInTheDocument()
  })

  it('CCPIP-118: revisa los videos de las tiendas y aprueba con motivo', async () => {
    const VIDEO = {
      id: 4, estado: 'PENDIENTE', titulo: 'Cosecha de cacao', formato: 'MP4', bytes: 3145728, duracionSegundos: 95.5,
      url: '/api/videos/4', subidoEn: '2026-10-26T15:00:00Z', subidoPor: 'María Shirampari',
      productor: { id: 3, organizacion: 'Asoc. Nii Joi', comunidad: 'C.N. Nii Joi', pueblo: 'Asháninka', region: 'Junín' },
      reemplaza: null, motivo: null, decididoPor: null, decididoEn: null, puedeDecidir: true,
    }
    const fetchMock = comoSub({
      [`GET ${BANDEJA}/videos`]: () => [200, {
        contadores: { pendientes: 1, aprobadasMes: 0, rechazadasMes: 0 },
        solicitudes: [{ id: 4, estado: 'PENDIENTE', titulo: 'Cosecha de cacao', duracionSegundos: 95.5, productorId: 3, organizacion: 'Asoc. Nii Joi',
          pueblo: 'Asháninka', region: 'Junín', reemplazo: false, subidoEn: '2026-10-26T15:00:00Z', decididoEn: null }],
      }],
      [`GET ${BANDEJA}/videos/4`]: () => [200, copia(VIDEO)],
      [`POST ${BANDEJA}/videos/4/aprobar`]: () => [200, { ...copia(VIDEO), estado: 'APROBADA', puedeDecidir: false, motivo: 'Muestra su trabajo', decididoPor: 'Lucía Paredes', decididoEn: '2026-10-26T16:00:00Z' }],
    })
    const user = userEvent.setup()
    abrirEn('/admin/moderacion')
    await user.click(await screen.findByRole('link', { name: 'Videos de tiendas' }))
    expect(screen.getByRole('link', { name: 'Videos de tiendas' })).toHaveAttribute('aria-current', 'page')
    const filas = within(await screen.findByRole('table', { name: 'Pendientes' })).getAllByRole('row')
    expect(within(filas[1]).getByText('Cosecha de cacao')).toBeInTheDocument()
    expect(within(filas[1]).getByText('1:36')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Nuevo')).toBeInTheDocument()
    await user.click(within(filas[1]).getByRole('link', { name: /Revisar la publicación de Cosecha de cacao/ }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Asoc. Nii Joi · Cosecha de cacao' })).toBeInTheDocument()
    expect(screen.getByLabelText('Cosecha de cacao')).toHaveAttribute('src', '/api/videos/4')
    expect(screen.getByText('1:36 · MP4 · 3.0 MB')).toBeInTheDocument()
    const d = await decision()
    await user.type(d.getByLabelText('Motivo de la decisión'), 'Muestra su trabajo')
    await user.click(d.getByRole('button', { name: 'Aprobar video' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Aprobar el video Cosecha de cacao' })).toBeInTheDocument()
    await user.click(dialogo.getByRole('button', { name: 'Aprobar' }))
    expect(await screen.findByText('Aprobaste el video. Ya se ve en la tienda.')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, `POST ${BANDEJA}/videos/4/aprobar`)[0][1].body)).toEqual({ motivo: 'Muestra su trabajo' })
    expect((await decision()).getByText('Este video ya se aprobó.')).toBeInTheDocument()
  })

  it('el superadministrador no modera', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_SUPER], 'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)] })
    abrirEn('/admin/moderacion')
    await screen.findByRole('navigation', { name: 'Super administrador' })
    expect(screen.queryByRole('link', { name: 'Moderación' })).not.toBeInTheDocument()
    expect(window.location.pathname).toBe('/admin')
  })

  it('sale en inglés', async () => {
    await i18n.changeLanguage('en')
    comoSub()
    abrirEn('/admin/moderacion')
    expect(await screen.findByRole('heading', { level: 1, name: 'Publication moderation' })).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: 'Pending' })).toBeInTheDocument()
  })
})
