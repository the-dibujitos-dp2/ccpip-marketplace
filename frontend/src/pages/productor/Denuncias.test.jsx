import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const comoProductor = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_PRODUCTOR],
  'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
  'GET /api/productor/denuncias': () => [200, copia(DATOS.denunciasProductor)],
  ...rutas,
})

describe('Denuncias recibidas por el productor (CCPIP-116, CCPIP-323)', () => {
  afterEach(() => i18n.changeLanguage('es'))

  it('lista las denuncias anonimizadas tal como llegan del servidor, sin datos del denunciante', async () => {
    servidor(comoProductor())
    abrirEn('/productor/denuncias')
    expect(await screen.findByRole('heading', { level: 1, name: 'Denuncias recibidas' })).toBeInTheDocument()
    expect(screen.getByText('Denuncias anónimas')).toBeInTheDocument()
    expect(await screen.findByText('2 en total · 1 abiertas · 1 sin responder')).toBeInTheDocument()
    const abierta = within(screen.getByRole('article', { name: 'Denuncia sobre Aceite de ungurahui 250 ml del 15 oct 2026' }))
    expect(abierta.getByText('Recibida el 15 oct 2026 · Mala calidad o en mal estado')).toBeInTheDocument()
    expect(abierta.getByText('Llegó rancio. Escríbeme a [correo oculto].')).toBeInTheDocument()
    expect(abierta.getByText('Recibida', { selector: '.badge-estado' })).toBeInTheDocument()
    const cerrada = within(screen.getByRole('article', { name: 'Denuncia sobre Sobre tu comunidad del 2 oct 2026' }))
    expect(cerrada.getByText('Ya lo conversamos con la Cámara.')).toBeInTheDocument()
    expect(cerrada.queryByRole('button', { name: 'Enviar respuesta' })).not.toBeInTheDocument()
    // El menu del productor lleva a sus denuncias
    expect(screen.getByRole('link', { name: 'Denuncias' })).toHaveAttribute('aria-current', 'page')
  })

  it('responde una denuncia abierta una vez', async () => {
    const respondida = {
      ...copia(DATOS.denunciasProductor.denuncias[0]),
      respuesta: 'Reemplazaremos el frasco.', respondidaEn: '2026-10-16T15:00:00Z', puedeResponder: false,
    }
    const fetchMock = servidor(comoProductor({ 'POST /api/productor/denuncias/12/respuesta': [200, respondida] }))
    const user = userEvent.setup()
    abrirEn('/productor/denuncias')
    const abierta = within(await screen.findByRole('article', { name: /Aceite de ungurahui/ }))
    await user.type(abierta.getByLabelText('Tu respuesta'), 'corta')
    await user.click(abierta.getByRole('button', { name: 'Enviar respuesta' }))
    expect(abierta.getByText('Escribe tu respuesta (al menos 10 caracteres).')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/productor/denuncias/12/respuesta')).toHaveLength(0)

    await user.clear(abierta.getByLabelText('Tu respuesta'))
    await user.type(abierta.getByLabelText('Tu respuesta'), 'Reemplazaremos el frasco.')
    await user.click(abierta.getByRole('button', { name: 'Enviar respuesta' }))
    expect(await abierta.findByText('Reemplazaremos el frasco.')).toBeInTheDocument()
    expect(abierta.queryByRole('button', { name: 'Enviar respuesta' })).not.toBeInTheDocument()
    expect(screen.getByText('2 en total · 1 abiertas · 0 sin responder')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, 'POST /api/productor/denuncias/12/respuesta')[0][1].body))
      .toEqual({ texto: 'Reemplazaremos el frasco.' })
  })

  it('sin denuncias lo dice, y un error del servidor se explica', async () => {
    servidor(comoProductor({ 'GET /api/productor/denuncias': [200, { resumen: { total: 0, abiertas: 0, sinResponder: 0 }, denuncias: [] }] }))
    const { unmount } = abrirEn('/productor/denuncias')
    expect(await screen.findByText('No tienes denuncias')).toBeInTheDocument()
    unmount()
    servidor(comoProductor({
      'GET /api/productor/denuncias': [200, copia(DATOS.denunciasProductor)],
      'POST /api/productor/denuncias/12/respuesta': [409, { code: 'DENUNCIA_CERRADA', error: 'La denuncia ya fue cerrada por la Cámara.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/productor/denuncias')
    const abierta = within(await screen.findByRole('article', { name: /Aceite de ungurahui/ }))
    await user.type(abierta.getByLabelText('Tu respuesta'), 'Respuesta suficientemente larga.')
    await user.click(abierta.getByRole('button', { name: 'Enviar respuesta' }))
    expect(await abierta.findByText('La denuncia ya fue cerrada por la Cámara.')).toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    servidor(comoProductor())
    abrirEn('/productor/denuncias')
    expect(await screen.findByRole('heading', { level: 1, name: 'Reports received' })).toBeInTheDocument()
    expect(await screen.findByText('Received on 15 Oct 2026 · Poor quality or damaged')).toBeInTheDocument()
  })
})
