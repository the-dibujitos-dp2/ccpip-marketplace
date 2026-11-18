import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SIN_SESION } from '../../test/servidor.js'

// CCPIP-512: la interfaz en portugues (solo la interfaz: el contenido sigue en espanol), como el ingles de CCPIP-98
function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const sinSesion = { ...PUBLICAS, 'GET /api/auth/me': SIN_SESION }

/** El carrito de la sesion, con la respuesta que da el servidor al cambiar la cantidad del primer producto. */
const carritoQueResponde = (respuesta) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  'GET /api/carrito': () => [200, copia(DATOS.carrito)],
  ['PUT /api/carrito/items/' + DATOS.carrito.items[0].productoId]: respuesta,
})

describe('Interfaz en portugués (CCPIP-512)', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('pt')
  })

  it('el inicio usa los textos en portugués, también los del rediseño editorial', async () => {
    servidor(sinSesion)
    abrirEn('/')
    expect(await screen.findByRole('heading', { name: /Tudo tem\s*uma origem/ })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Explorar produtos' }).length).toBeGreaterThan(0)
    expect(screen.queryByText('Explorar productos')).not.toBeInTheDocument()
  })

  it('el contenido del catálogo se pide sin traducción: el servidor lo responde en español', async () => {
    const fetchMock = servidor(sinSesion)
    abrirEn('/')
    await screen.findByRole('heading', { name: /Tudo tem\s*uma origem/ })
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/inicio').map((l) => l[1].headers['Accept-Language'])).toEqual(['pt']))
  })

  it('el inicio de sesión valida y explica los errores en portugués', async () => {
    servidor({ ...sinSesion, 'POST /api/auth/login': [401, { code: 'CREDENCIALES_INVALIDAS', error: 'Correo o contrasena incorrectos' }] })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    expect(await screen.findByRole('heading', { name: 'Que bom encontrar você aqui' })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Entrar' }))
    expect(screen.getByText('Digite um e-mail válido, por exemplo nome@email.com')).toBeInTheDocument()

    await user.type(screen.getByLabelText('E-mail'), 'consumidor@ccpip.test')
    await user.type(screen.getByLabelText('Senha'), 'incorrecta')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))
    expect(await screen.findByText('O e-mail ou a senha não estão corretos.')).toBeInTheDocument()
  })

  it('un error del servidor se muestra por su código, no con el mensaje en español', async () => {
    servidor(carritoQueResponde([409, { code: 'VENTA_PAUSADA', error: 'La venta de este producto está pausada por ahora.' }]))
    const user = userEvent.setup()
    abrirEn('/carrito')
    expect(await screen.findByRole('heading', { name: 'Seu carrinho' })).toBeInTheDocument()
    expect(await screen.findByText('Resumo do pedido')).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Adicionar uma unidade' })[0])
    expect(await screen.findByText(/A venda deste produto está pausada no momento/, {}, { timeout: 2000 })).toBeInTheDocument()
    expect(screen.queryByText(/pausada por ahora/)).not.toBeInTheDocument()
  })

  it('un mensaje poco común del servidor, sin traducción, cae en un mensaje genérico en portugués', async () => {
    servidor(carritoQueResponde([409, { code: 'CODIGO_POCO_COMUN', error: 'Un mensaje que solo existe en español.' }]))
    const user = userEvent.setup()
    abrirEn('/carrito')
    expect(await screen.findByText('Resumo do pedido')).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Adicionar uma unidade' })[0])
    expect(await screen.findByText('Algo deu errado. Tente novamente.', {}, { timeout: 2000 })).toBeInTheDocument()
    expect(screen.queryByText(/solo existe en español/)).not.toBeInTheDocument()
  })
})
