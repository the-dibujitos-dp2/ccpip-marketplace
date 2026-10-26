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
