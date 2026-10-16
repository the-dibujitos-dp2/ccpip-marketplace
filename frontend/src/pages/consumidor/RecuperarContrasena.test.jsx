import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { llamadas, PUBLICAS, servidor, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const sinSesion = (rutas = {}) => servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, ...rutas })
const cuerpo = (fetchMock, clave) => JSON.parse(llamadas(fetchMock, clave).at(-1)[1].body)

describe('CCPIP-33 Recuperar la contraseña', () => {
  it('desde "Iniciar sesión" lleva el correo escrito y pide el enlace sin revelar si la cuenta existe', async () => {
    const fetchMock = sinSesion({ 'POST /api/auth/recuperar-contrasena': [202, null] })
    const user = userEvent.setup()
    abrirEn('/ingresar')

    await user.type(await screen.findByLabelText('Correo electrónico'), 'rosa@correo.com')
    await user.click(screen.getByRole('link', { name: '¿Olvidaste tu contraseña?' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Recupera tu contraseña' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/recuperar-contrasena')
    expect(screen.getByLabelText('Correo electrónico')).toHaveValue('rosa@correo.com')

    await user.click(screen.getByRole('button', { name: 'Enviar enlace' }))
    expect(await screen.findByText('Revisa tu correo')).toBeInTheDocument()
    expect(screen.getByText('Si rosa@correo.com tiene una cuenta, te enviamos un enlace. Sirve una sola vez y vence en 30 minutos.')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'POST /api/auth/recuperar-contrasena')).toEqual({ email: 'rosa@correo.com' })
  })

  it('no envía un correo mal escrito', async () => {
    const fetchMock = sinSesion()
    const user = userEvent.setup()
    abrirEn('/recuperar-contrasena')
    await user.type(await screen.findByLabelText('Correo electrónico'), 'rosa@')
    await user.click(screen.getByRole('button', { name: 'Enviar enlace' }))
    expect(screen.getByLabelText('Correo electrónico')).toHaveAccessibleDescription('Escribe un correo válido, por ejemplo nombre@correo.com')
    expect(llamadas(fetchMock, 'POST /api/auth/recuperar-contrasena')).toHaveLength(0)
  })

  it('el enlace pide la clave dos veces, la cambia y quita el token de la barra', async () => {
    const fetchMock = sinSesion({ 'POST /api/auth/restablecer-contrasena': [200, { email: 'rosa@correo.com' }] })
    const user = userEvent.setup()
    abrirEn('/restablecer-contrasena?token=abc123')

    expect(await screen.findByRole('heading', { level: 1, name: 'Elige una contraseña nueva' })).toBeInTheDocument()
    expect(window.location.search).toBe('')

    await user.type(screen.getByLabelText('Contraseña nueva'), 'corta')
    await user.type(screen.getByLabelText('Repite la contraseña nueva'), 'otra')
    await user.click(screen.getByRole('button', { name: 'Guardar contraseña' }))
    expect(screen.getByLabelText('Contraseña nueva')).toHaveAccessibleDescription('Usa al menos 8 caracteres, con una mayúscula y un número')
    expect(screen.getByLabelText('Repite la contraseña nueva')).toHaveAccessibleDescription('Las contraseñas no coinciden.')
    expect(llamadas(fetchMock, 'POST /api/auth/restablecer-contrasena')).toHaveLength(0)

    await user.clear(screen.getByLabelText('Contraseña nueva'))
    await user.type(screen.getByLabelText('Contraseña nueva'), 'Selva2026')
    await user.clear(screen.getByLabelText('Repite la contraseña nueva'))
    await user.type(screen.getByLabelText('Repite la contraseña nueva'), 'Selva2026')
    await user.click(screen.getByRole('button', { name: 'Guardar contraseña' }))

    expect(await screen.findByText('Contraseña actualizada')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'POST /api/auth/restablecer-contrasena')).toEqual({ token: 'abc123', password: 'Selva2026' })

    // Al ingresar, el correo ya esta escrito
    await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
    expect(await screen.findByLabelText('Correo electrónico')).toHaveValue('rosa@correo.com')
  })

  it.each([
    ['ENLACE_USADO', 410, 'Este enlace ya se usó'],
    ['ENLACE_VENCIDO', 410, 'El enlace venció'],
    ['ENLACE_INVALIDO', 400, 'El enlace no es válido'],
  ])('si el servidor responde %s lo explica y ofrece pedir otro', async (code, status, titulo) => {
    sinSesion({ 'POST /api/auth/restablecer-contrasena': [status, { code, error: 'x' }] })
    const user = userEvent.setup()
    abrirEn('/restablecer-contrasena?token=abc123')
    await user.type(await screen.findByLabelText('Contraseña nueva'), 'Selva2026')
    await user.type(screen.getByLabelText('Repite la contraseña nueva'), 'Selva2026')
    await user.click(screen.getByRole('button', { name: 'Guardar contraseña' }))
    expect(await screen.findByText(titulo)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Pedir un enlace nuevo' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Recupera tu contraseña' })).toBeInTheDocument()
  })

  it('sin token no muestra el formulario', async () => {
    sinSesion()
    abrirEn('/restablecer-contrasena')
    expect(await screen.findByText('El enlace no es válido')).toBeInTheDocument()
    expect(screen.queryByLabelText('Contraseña nueva')).not.toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    sinSesion()
    abrirEn('/recuperar-contrasena')
    expect(await screen.findByRole('heading', { level: 1, name: 'Recover your password' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send link' })).toBeInTheDocument()
    expect(screen.queryByText(/contraseña|Enviar/)).not.toBeInTheDocument()
  })
})
