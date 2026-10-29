import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from './App.jsx'
import { copia, DATOS, EXPIRADA, llamadas, PUBLICAS, servidor, SESION, SESION_ACOPIO, SIN_SESION } from './test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

async function ingresar(user, correo = 'consumidor@ccpip.test', clave = 'Demo1234!') {
  await user.type(await screen.findByLabelText('Correo electrónico'), correo)
  await user.type(screen.getByLabelText('Contraseña'), clave)
  await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
}

async function cerrarSesion(user) {
  const tienda = screen.queryByRole('button', { name: 'Mi cuenta' })
  if (tienda) {
    await user.click(tienda)
    await user.click(screen.getByRole('menuitem', { name: 'Cerrar sesión' }))
  } else {
    await user.click(await screen.findByRole('button', { name: 'Cerrar sesión' }))
  }
}

describe('Inicio de sesion (CCPIP-27)', () => {
  it('sin sesion, una pantalla protegida lleva al inicio de sesion', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/inicio')
    expect(await screen.findByRole('heading', { name: 'Ingresa a tu cuenta' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/ingresar')
  })

  it('el ojo muestra y oculta la contraseña escrita', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    const clave = await screen.findByLabelText('Contraseña')
    await user.type(clave, 'Demo1234!')
    expect(clave).toHaveAttribute('type', 'password')

    await user.click(screen.getByRole('button', { name: 'Mostrar contraseña' }))
    expect(clave).toHaveAttribute('type', 'text')
    expect(clave).toHaveValue('Demo1234!')
    expect(screen.getByRole('button', { name: 'Ocultar contraseña' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: 'Ocultar contraseña' }))
    expect(clave).toHaveAttribute('type', 'password')
  })

  it('valida los campos antes de llamar al servidor', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await user.type(await screen.findByLabelText('Correo electrónico'), 'no-es-correo')
    await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))

    expect(screen.getByText(/Escribe un correo válido/)).toBeInTheDocument()
    expect(screen.getByText('Escribe tu contraseña')).toBeInTheDocument()
    expect(screen.getByLabelText('Correo electrónico')).toHaveAttribute('aria-invalid', 'true')
    expect(llamadas(fetchMock, 'POST /api/auth/login')).toHaveLength(0)
  })

  it.each([
    [[401, { code: 'CREDENCIALES_INVALIDAS' }], 'No pudimos iniciar sesión'],
    [[403, { code: 'CORREO_NO_CONFIRMADO' }], 'Confirma tu correo'],
    [[403, { code: 'CUENTA_INACTIVA' }], 'Cuenta desactivada'],
    [[423, { code: 'CUENTA_BLOQUEADA', lockedUntil: '2026-10-03T23:45:00Z' }], 'Cuenta bloqueada temporalmente'],
  ])('muestra el motivo cuando el servidor rechaza el ingreso (%j)', async (respuesta, titulo) => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/login': respuesta })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await ingresar(user)

    expect(await screen.findByText(titulo)).toBeInTheDocument()
    expect(screen.getByLabelText('Contraseña')).toHaveValue('')
    expect(window.location.pathname).toBe('/ingresar')
  })

  it('avisa si no hay conexion con el servidor', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/login': new TypeError('Failed to fetch') })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await ingresar(user)
    expect(await screen.findByText('No pudimos conectar')).toBeInTheDocument()
  })

  it('el consumidor entra a la tienda y los demas roles a su inicio', async () => {
    let conSesion = false
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': () => (conSesion ? [200, SESION] : SIN_SESION),
      'POST /api/auth/login': () => {
        conSesion = true
        return [200, SESION]
      },
    })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await ingresar(user)

    expect(await screen.findByRole('heading', { name: /Productos con historia/ })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/')
    expect(screen.getByRole('button', { name: 'Mi cuenta' })).toBeInTheDocument()
  })

  it('el administrador de acopio entra directo a Pedidos desde login', async () => {
    let conSesion = false
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': () => (conSesion ? [200, SESION_ACOPIO] : SIN_SESION), 'POST /api/auth/login': () => { conSesion = true; return [200, SESION_ACOPIO] }, 'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)] })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await ingresar(user, 'admin_acopio@ccpip.test')
    expect(await screen.findByRole('heading', { name: 'Pedidos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/acopio/pedidos')
    expect(llamadas(fetchMock, 'GET /api/acopio/pedidos')).toHaveLength(1)
  })

  it('una sesion de acopio abierta en /inicio va a pedidos sin cargar catalogo de comprador', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_ACOPIO], 'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)] })
    abrirEn('/inicio')
    expect(await screen.findByRole('heading', { name: 'Pedidos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/acopio/pedidos')
    expect(llamadas(fetchMock, 'GET /api/inicio')).toHaveLength(0)
  })

  it('el distribuidor conserva el inicio y el aviso de inactividad', async () => {
    let conSesion = false
    const sesionDistribuidor = { user: { id: 6, email: 'distribuidor@ccpip.test', fullName: 'Distribuidor', role: 'DISTRIBUIDOR', roleName: 'Distribuidor' }, expiresInSeconds: 120 }
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': () => (conSesion ? [200, sesionDistribuidor] : SIN_SESION),
      'POST /api/auth/login': () => {
        conSesion = true
        return [200, sesionDistribuidor]
      },
      'GET /api/distribuidor/pedidos': [200, { items: [] }],
    })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await ingresar(user, 'distribuidor@ccpip.test')

    expect(await screen.findByText(/se cierra tras 2 minutos sin actividad/)).toBeInTheDocument()
    expect(window.location.pathname).toBe('/inicio')
  })

  it('vuelve a la pagina que pidio antes de ingresar, solo si es interna', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION] })
    abrirEn('/ingresar?volver=//sitio-malicioso.com')
    await waitFor(() => expect(window.location.pathname).toBe('/'))
  })

  it('el acopio vuelve a una ruta interna valida y rechaza fallback externo', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_ACOPIO], 'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)] })
    abrirEn('/ingresar?volver=%2Facopio%2Fpedidos')
    expect(await screen.findByRole('heading', { name: 'Pedidos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/acopio/pedidos')
  })

  it('fallback externo para acopio nunca lo redirige a un origen externo', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_ACOPIO], 'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)] })
    abrirEn('/ingresar?volver=%2F%2Fevil.example')
    expect(await screen.findByRole('heading', { name: 'Pedidos' })).toBeInTheDocument()
    expect(window.location.origin).toMatch(/^http:\/\/localhost(?::\d+)?$/)
    expect(window.location.pathname).toBe('/acopio/pedidos')
  })

  it('si ya habia sesion entra directo y muestra el error traducido si pedidos falla', async () => {
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_ACOPIO],
      'GET /api/acopio/pedidos': [403, { code: 'SIN_PERMISO', error: 'No tienes permiso para esta accion' }],
    })
    abrirEn('/ingresar')
    expect(await screen.findByText('No pudimos cargar los pedidos')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/acopio/pedidos')
  })

  it('la alerta se puede cerrar', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/login': [401, {}] })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await ingresar(user)
    await user.click(await screen.findByRole('button', { name: 'Cerrar aviso' }))
    expect(screen.queryByText('No pudimos iniciar sesión')).not.toBeInTheDocument()
  })
})

describe('Cierre de sesion manual (CCPIP-27)', () => {
  it('cierra la sesion en el servidor y vuelve al inicio de sesion', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_ACOPIO],
      'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)],
      'POST /api/auth/logout': [204],
    })
    const user = userEvent.setup()
    abrirEn('/inicio')
    await cerrarSesion(user)

    expect(await screen.findByText('Cerraste sesión')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/auth/logout')).toHaveLength(1)
    expect(window.location.pathname).toBe('/ingresar')
  })

  it('mientras cierra, el menu lo dice con el aro de carga (CCPIP-413)', async () => {
    let responder
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_ACOPIO],
      'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)],
      'POST /api/auth/logout': () => new Promise((r) => { responder = r }),
    })
    const user = userEvent.setup()
    abrirEn('/inicio')
    await cerrarSesion(user)

    const cerrando = await screen.findByRole('button', { name: 'Cerrar sesión' })
    expect(cerrando).toBeDisabled()
    expect(cerrando).toHaveAttribute('aria-busy', 'true')
    expect(cerrando.querySelector('.boton__giro')).not.toBeNull()
    responder([204])
    expect(await screen.findByText('Cerraste sesión')).toBeInTheDocument()
  })

  it('aunque falle la red, la pantalla se cierra', async () => {
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_ACOPIO],
      'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)],
      'POST /api/auth/logout': new TypeError('Failed to fetch'),
    })
    const user = userEvent.setup()
    abrirEn('/inicio')
    await cerrarSesion(user)
    expect(await screen.findByText('Cerraste sesión')).toBeInTheDocument()
  })

  it('volver atras a una pantalla del rol consulta al servidor y no da acceso', async () => {
    let conSesion = true
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': () => (conSesion ? [200, SESION_ACOPIO] : SIN_SESION),
      'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)],
      'POST /api/auth/logout': () => {
        conSesion = false
        return [204]
      },
    })
    const user = userEvent.setup()
    abrirEn('/inicio')
    await cerrarSesion(user)
    await screen.findByText('Cerraste sesión')

    // Simula el boton "atras" del navegador hacia /inicio
    act(() => {
      window.history.pushState(null, '', '/inicio')
      window.dispatchEvent(new PopStateEvent('popstate'))
    })
    await waitFor(() => expect(window.location.pathname).toBe('/ingresar'))
    expect(screen.queryByRole('button', { name: 'Mi cuenta' })).not.toBeInTheDocument()
  })

  it('al restaurar la pagina desde la cache del navegador vuelve a verificar la sesion', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/ingresar')
    await screen.findByLabelText('Correo electrónico')
    const antes = llamadas(fetchMock, 'GET /api/auth/me').length

    const evento = new Event('pageshow')
    evento.persisted = true
    act(() => window.dispatchEvent(evento))
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/auth/me').length).toBe(antes + 1))
  })
})

describe('Expiracion automatica por inactividad (CCPIP-27)', () => {
  it('tras el plazo sin actividad vuelve al inicio de sesion indicando el motivo', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let expirada = false
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': () => (expirada ? EXPIRADA : [200, SESION_ACOPIO]),
      'GET /api/acopio/pedidos': () => [200, copia(DATOS.pedidos)],
    })
    abrirEn('/inicio')
    await screen.findByRole('heading', { name: 'Pedidos' })

    expirada = true
    await act(() => vi.advanceTimersByTimeAsync(122_000))

    expect(await screen.findByText('Tu sesión expiró')).toBeInTheDocument()
    expect(screen.getByText(/tras 2 minutos sin actividad/)).toBeInTheDocument()
    expect(window.location.pathname).toBe('/ingresar')
  })

  it('cualquier peticion rechazada por expiracion tambien cierra la pantalla', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_ACOPIO], 'GET /api/acopio/pedidos': EXPIRADA })
    abrirEn('/acopio/pedidos')
    expect(await screen.findByText('Tu sesión expiró')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/ingresar')
    expect(screen.queryByRole('button', { name: 'Mi cuenta' })).not.toBeInTheDocument()
  })
})
