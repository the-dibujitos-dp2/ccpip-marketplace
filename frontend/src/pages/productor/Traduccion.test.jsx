import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

// CCPIP-147 (RF-168): traducir desde PW-04 y PW-08 con el proveedor que exista, revisar y publicar.
// Sin proveedor, aviso de que no esta disponible. Respuestas segun TraduccionesContenido del backend.
function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/productos/9': () => [200, copia(DATOS.edicion)],
    'GET /api/productor/ubicaciones': () => [200, copia(DATOS.ubicaciones)],
    ...rutas,
  })
}

const campo = (entidad, nombre, original, extra = {}) => ({
  entidad, campo: nombre, original, version: 1, texto: null, estado: null, origen: null, proveedor: null, vigente: false, ...extra,
})

const SIN_TRADUCIR = {
  idioma: 'en',
  automaticaDisponible: true,
  campos: [
    campo('PRODUCTO', 'nombre', 'Camu camu en polvo 150 g'),
    campo('PRODUCTO', 'descripcion', 'Deshidratado a baja temperatura.', {
      version: 2, texto: 'Low-temperature dried.', estado: 'DESACTUALIZADA', origen: 'COMUNIDAD',
    }),
  ],
}

const PROPUESTA = {
  ...SIN_TRADUCIR,
  campos: [
    campo('PRODUCTO', 'nombre', 'Camu camu en polvo 150 g', {
      texto: '[simulado en] Camu camu en polvo 150 g', estado: 'PENDIENTE', origen: 'AUTOMATICA', proveedor: 'simulado', vigente: true,
    }),
    campo('PRODUCTO', 'descripcion', 'Deshidratado a baja temperatura.', {
      version: 2, texto: '[simulado en] Deshidratado a baja temperatura.', estado: 'PENDIENTE', origen: 'AUTOMATICA', proveedor: 'simulado', vigente: true,
    }),
  ],
}

const RUTA = '/api/productor/productos/9/traducciones/en'
// El editor carga varias cosas antes del panel; bajo carga tarda mas que el segundo por defecto
const ESPERA = { timeout: 4000 }

describe('PW-04 Traducir el producto (CCPIP-147)', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('traduce al inglés, deja revisar y publica solo lo corregido', async () => {
    const publicado = {
      ...PROPUESTA,
      campos: PROPUESTA.campos.map((c) => ({ ...c, estado: 'APROBADA' })),
    }
    const fetchMock = productor({
      [`GET ${RUTA}`]: [200, SIN_TRADUCIR],
      [`POST ${RUTA}`]: [200, PROPUESTA],
      [`POST ${RUTA}/aprobar`]: [200, publicado],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')

    const panel = await screen.findByRole('region', { name: 'Tu contenido en inglés' }, ESPERA)
    expect(within(panel).getByText('Sin traducir')).toBeInTheDocument()
    expect(within(panel).getByText('Desactualizada')).toBeInTheDocument()

    await user.click(within(panel).getByRole('button', { name: 'Traducir al inglés' }))
    expect(await within(panel).findAllByText('Por revisar', {}, ESPERA)).toHaveLength(2)
    const descripcion = within(panel).getByLabelText('Revisa la traducción: Descripción')
    expect(descripcion).toHaveValue('[simulado en] Deshidratado a baja temperatura.')
    await user.clear(descripcion)
    await user.type(descripcion, 'Dried at low temperature.')

    await user.click(within(panel).getByRole('button', { name: 'Publicar traducción' }))
    expect(await within(panel).findByText('Publicaste la traducción', {}, ESPERA)).toBeInTheDocument()
    const [, opciones] = llamadas(fetchMock, `POST ${RUTA}/aprobar`).at(-1)
    expect(JSON.parse(opciones.body)).toEqual({
      textos: [{ entidad: 'PRODUCTO', campo: 'descripcion', texto: 'Dried at low temperature.' }],
    })
    expect(within(panel).getAllByText('Publicada')).toHaveLength(2)
  })

  it('sin proveedor avisa que no está disponible y no deja traducir', async () => {
    const fetchMock = productor({ [`GET ${RUTA}`]: [200, { ...SIN_TRADUCIR, automaticaDisponible: false }] })
    abrirEn('/productor/productos/9')
    const panel = await screen.findByRole('region', { name: 'Tu contenido en inglés' }, ESPERA)
    expect(within(panel).getByText('La traducción automática aún no está disponible')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Traducir al inglés' })).toBeDisabled()
    expect(llamadas(fetchMock, `POST ${RUTA}`)).toHaveLength(0)
  })

  it('si el servicio responde 503 el contenido queda como estaba y se avisa', async () => {
    productor({
      [`GET ${RUTA}`]: [200, SIN_TRADUCIR],
      [`POST ${RUTA}`]: [503, { code: 'TRADUCCION_NO_DISPONIBLE', error: 'La traducción automática no está disponible en este momento.' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    const panel = await screen.findByRole('region', { name: 'Tu contenido en inglés' }, ESPERA)
    await user.click(within(panel).getByRole('button', { name: 'Traducir al inglés' }))
    expect(await within(panel).findByRole('alert')).toHaveTextContent('La traducción automática aún no está disponible')
    expect(within(panel).getByText('Sin traducir')).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Publicar traducción' })).not.toBeInTheDocument()
  })

  it('una traducción corregida no puede quedar vacía', async () => {
    const fetchMock = productor({ [`GET ${RUTA}`]: [200, PROPUESTA] })
    const user = userEvent.setup()
    abrirEn('/productor/productos/9')
    const panel = await screen.findByRole('region', { name: 'Tu contenido en inglés' }, ESPERA)
    await user.clear(within(panel).getByLabelText('Revisa la traducción: Nombre del producto'))
    await user.click(within(panel).getByRole('button', { name: 'Publicar traducción' }))
    expect(await within(panel).findByText('Una traducción no puede quedar vacía')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${RUTA}/aprobar`)).toHaveLength(0)
  })

  it('si no se puede consultar, el editor sigue igual sin el panel', async () => {
    productor({ [`GET ${RUTA}`]: [500, { code: 'ERROR' }] })
    abrirEn('/productor/productos/9')
    expect(await screen.findByLabelText(/Nombre del producto/, {}, ESPERA)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Tu contenido en inglés' })).not.toBeInTheDocument())
  })
})

describe('PW-08 Traducir la tienda (CCPIP-147)', () => {
  it('traduce la tienda en inglés con los textos de la pantalla en inglés', async () => {
    await i18n.changeLanguage('en')
    const ruta = '/api/productor/tienda/traducciones/en'
    const tienda = {
      idioma: 'en',
      automaticaDisponible: true,
      campos: [campo('COMUNIDAD', 'historia', 'Trabajamos el aceite de ungurahui hace cuatro generaciones.', {
        texto: 'We have worked ungurahui oil for four generations.', estado: 'APROBADA', origen: 'COMUNIDAD', vigente: true,
      })],
    }
    const fetchMock = productor({
      [`GET ${ruta}`]: [200, tienda],
      [`POST ${ruta}`]: [200, tienda],
    })
    const user = userEvent.setup()
    abrirEn('/productor/tienda')
    const panel = await screen.findByRole('region', { name: 'Your content in English' }, ESPERA)
    expect(within(panel).getByText('Community story')).toBeInTheDocument()
    expect(within(panel).getByText('Published')).toBeInTheDocument()
    expect(within(panel).getByText('Translated by the community')).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Translate into English' }))
    await waitFor(() => expect(llamadas(fetchMock, `POST ${ruta}`)).toHaveLength(1))
  })

  it('una tienda sin textos todavía no se puede traducir', async () => {
    await i18n.changeLanguage('es')
    productor({ 'GET /api/productor/tienda/traducciones/en': [200, { idioma: 'en', automaticaDisponible: true, campos: [] }] })
    abrirEn('/productor/tienda')
    const panel = await screen.findByRole('region', { name: 'Tu contenido en inglés' }, ESPERA)
    expect(within(panel).getByText('Guarda primero los textos de tu contenido para poder traducirlos.')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Traducir al inglés' })).toBeDisabled()
  })
})
