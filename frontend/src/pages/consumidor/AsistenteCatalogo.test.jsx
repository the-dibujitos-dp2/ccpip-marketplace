import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { DATOS, llamadas, PUBLICAS, servidor, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const sinSesion = { ...PUBLICAS, 'GET /api/auth/me': SIN_SESION }

async function preguntar(user, texto) {
  await user.type(await screen.findByLabelText('¿Qué estás buscando?'), texto)
  await user.click(screen.getByRole('button', { name: 'Preguntar' }))
}

describe('Asistente de catálogo (CCPIP-51, CCPIP-175)', () => {
  it('responde con productos reales que llevan a su ficha y avisa que es IA', async () => {
    const citado = DATOS.buscar.items[0]
    const fetchMock = servidor({
      ...sinSesion,
      'POST /api/asistente/catalogo': [200, {
        texto: `Te sugiero ${citado.nombre}.`,
        productos: [citado],
        busqueda: { q: 'cacao', lineas: ['CACAO'], regiones: [] },
        sinResultados: false,
        fueraDeAlcance: false,
        proveedor: 'gemini',
      }],
    })
    const user = userEvent.setup()
    abrirEn('/buscar')
    await preguntar(user, 'cacao para regalar')

    expect(await screen.findByText(`Te sugiero ${citado.nombre}.`)).toBeInTheDocument()
    const sugeridos = screen.getByRole('list', { name: 'Productos que sugiere el asistente' })
    expect(within(sugeridos).getByRole('link')).toHaveAttribute('href', `/productos/${citado.id}`)
    expect(screen.getByText(/Respuesta generada con IA a partir del catálogo real/)).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, 'POST /api/asistente/catalogo')[0][1].body)).toEqual({ pregunta: 'cacao para regalar' })

    await user.click(screen.getByRole('button', { name: 'Ver todos los resultados en la búsqueda' }))
    await waitFor(() => expect(window.location.search).toBe('?q=cacao&linea=CACAO'))
  })

  it('sin coincidencias muestra el texto del servidor y ningun producto', async () => {
    servidor({
      ...sinSesion,
      'POST /api/asistente/catalogo': [200, { texto: 'No encontré productos del catálogo que coincidan con tu pregunta.', productos: [], sinResultados: true, fueraDeAlcance: false }],
    })
    const user = userEvent.setup()
    abrirEn('/buscar')
    await preguntar(user, 'un televisor')
    expect(await screen.findByText(/No encontré productos del catálogo/)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Productos que sugiere el asistente' })).not.toBeInTheDocument()
  })

  it('si la IA no esta disponible, la busqueda normal sigue funcionando', async () => {
    servidor({
      ...sinSesion,
      'POST /api/asistente/catalogo': [503, { code: 'IA_NO_DISPONIBLE', error: 'El asistente no está disponible.' }],
    })
    const user = userEvent.setup()
    abrirEn('/buscar?q=cacao')
    await preguntar(user, 'cacao')
    expect(await screen.findByText('El asistente no está disponible en este momento.')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Productos' })).toBeInTheDocument()
  })

  it('una pregunta muy corta no se envia', async () => {
    const fetchMock = servidor(sinSesion)
    const user = userEvent.setup()
    abrirEn('/buscar')
    await preguntar(user, 'ok')
    expect(await screen.findByText('Escribe tu pregunta con un poco más de detalle.')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/asistente/catalogo')).toHaveLength(0)
  })
})
