import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { llamadas, PUBLICAS, servidor, SESION, SIN_SESION } from '../../test/servidor.js'
import { olvidarEnlacesUsados } from './VerificarCorreoPage.jsx'

const CREADA = [201, { email: 'lucia@correo.pe', emailSent: true }]

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

async function llenar(user, { clave = 'Bosque2026', terminos = true } = {}) {
  await user.type(await screen.findByLabelText('Nombres'), 'Lucía')
  await user.type(screen.getByLabelText('Apellidos'), 'Ramírez Torres')
  await user.type(screen.getByLabelText('Correo electrónico'), 'lucia@correo.pe')
  await user.type(screen.getByLabelText('Celular (opcional)'), '987 654 321')
  await user.type(screen.getByLabelText('Contraseña'), clave)
  if (terminos) await user.click(screen.getByLabelText('Acepto los términos y la política de privacidad'))
}

function cuerpo(fetchMock, clave) {
  return JSON.parse(llamadas(fetchMock, clave)[0][1].body)
}

afterEach(() => olvidarEnlacesUsados())

describe('Crear cuenta (CCPIP-26)', () => {
  it('la pestana "Crear cuenta" lleva a /registro y conserva a donde volver', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    const user = userEvent.setup()
    abrirEn('/ingresar?volver=%2Fcarrito')
    await user.click(await screen.findByRole('link', { name: 'Crear cuenta' }))

    expect(window.location.pathname).toBe('/registro')
    expect(window.location.search).toBe('?volver=%2Fcarrito')
    expect(await screen.findByRole('heading', { name: 'Crea tu cuenta' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Crear cuenta' })).toHaveAttribute('aria-current', 'page')
    // Sin genero ni fecha de nacimiento (RF-01)
    expect(screen.queryByLabelText(/género|nacimiento/i)).not.toBeInTheDocument()
  })

  it('valida en pantalla antes de llamar al servidor', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/registro': CREADA })
    const user = userEvent.setup()
    abrirEn('/registro')
    await user.type(await screen.findByLabelText('Contraseña'), 'sinmayuscula1')
    await user.type(screen.getByLabelText('Celular (opcional)'), 'abc')
    await user.click(screen.getByRole('button', { name: 'Crear cuenta' }))

    expect(screen.getByText('Escribe tus nombres')).toBeInTheDocument()
    expect(screen.getByText('Escribe tus apellidos')).toBeInTheDocument()
    expect(screen.getByText(/Escribe un correo válido/)).toBeInTheDocument()
    expect(screen.getByText(/Escribe un celular válido/)).toBeInTheDocument()
    expect(screen.getByText('Usa al menos 8 caracteres, con una mayúscula y un número')).toBeInTheDocument()
    expect(screen.getByLabelText('Acepto los términos y la política de privacidad')).toHaveAttribute('aria-invalid', 'true')
    expect(llamadas(fetchMock, 'POST /api/auth/registro')).toHaveLength(0)
  })

  it('crea la cuenta y pide revisar el correo; luego lleva a ingresar con el correo escrito', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/registro': CREADA })
    const user = userEvent.setup()
    abrirEn('/registro')
    await llenar(user)
    await user.click(screen.getByRole('button', { name: 'Crear cuenta' }))

    expect(await screen.findByRole('heading', { name: 'Revisa tu correo' })).toBeInTheDocument()
    expect(screen.getByText(/Enviamos un enlace de verificación a lucia@correo.pe/)).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'POST /api/auth/registro')).toEqual({
      firstName: 'Lucía',
      lastName: 'Ramírez Torres',
      email: 'lucia@correo.pe',
      phone: '987 654 321',
      password: 'Bosque2026',
      acceptTerms: true,
    })
    // El servidor acepta otro enlace recien despues de un minuto
    expect(screen.getByRole('button', { name: /Reenviar en \d+ s/ })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Ya verifiqué · iniciar sesión' }))
    expect(window.location.pathname).toBe('/ingresar')
    expect(await screen.findByLabelText('Correo electrónico')).toHaveValue('lucia@correo.pe')
  })

  it('"Cambiar correo" vuelve al formulario con los datos, sin la contrasena', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/registro': CREADA })
    const user = userEvent.setup()
    abrirEn('/registro')
    await llenar(user)
    await user.click(screen.getByRole('button', { name: 'Crear cuenta' }))
    await user.click(await screen.findByRole('button', { name: 'Cambiar correo' }))

    expect(await screen.findByLabelText('Nombres')).toHaveValue('Lucía')
    expect(screen.getByLabelText('Correo electrónico')).toHaveValue('lucia@correo.pe')
    expect(screen.getByLabelText('Contraseña')).toHaveValue('')
  })

  it('un correo ya registrado se marca en el campo y borra la contrasena', async () => {
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': SIN_SESION,
      'POST /api/auth/registro': [409, { code: 'CORREO_YA_REGISTRADO', error: 'Ya existe una cuenta con ese correo.' }],
    })
    const user = userEvent.setup()
    abrirEn('/registro')
    await llenar(user)
    await user.click(screen.getByRole('button', { name: 'Crear cuenta' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ese correo ya tiene una cuenta')
    expect(screen.getByLabelText('Correo electrónico')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Contraseña')).toHaveValue('')
  })

  it('si el correo no salio, avisa que la cuenta existe y que puede pedir el enlace', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/registro': [201, { email: 'lucia@correo.pe', emailSent: false }] })
    const user = userEvent.setup()
    abrirEn('/registro')
    await llenar(user)
    await user.click(screen.getByRole('button', { name: 'Crear cuenta' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Tu cuenta está creada, pero el correo no salió')
  })

  it('quien ingresa sin verificar puede pedir otro enlace en el momento', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': SIN_SESION,
      'POST /api/auth/login': [403, { code: 'CORREO_NO_CONFIRMADO', error: 'Confirma tu correo antes de ingresar.' }],
      'POST /api/auth/verificar-correo/reenviar': [202, null],
    })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await user.type(await screen.findByLabelText('Correo electrónico'), 'lucia@correo.pe')
    await user.type(screen.getByLabelText('Contraseña'), 'Bosque2026')
    await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
    await user.click(await screen.findByRole('link', { name: 'Pedir un enlace nuevo' }))

    await user.click(await screen.findByRole('button', { name: 'Reenviar enlace' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Te reenviamos el enlace')
    expect(cuerpo(fetchMock, 'POST /api/auth/verificar-correo/reenviar')).toEqual({ email: 'lucia@correo.pe' })
  })

  it('con sesion iniciada, /registro lleva a donde corresponde', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION] })
    abrirEn('/registro')
    await waitFor(() => expect(window.location.pathname).toBe('/'))
  })

  it('en ingles, la pantalla y el pedido al servidor van en ingles', async () => {
    await i18n.changeLanguage('en')
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/registro': CREADA })
    const user = userEvent.setup()
    abrirEn('/registro')
    await user.type(await screen.findByLabelText('First names'), 'Lucía')
    await user.type(screen.getByLabelText('Last names'), 'Ramírez')
    await user.type(screen.getByLabelText('Email'), 'lucia@correo.pe')
    await user.type(screen.getByLabelText('Password'), 'Bosque2026')
    await user.click(screen.getByLabelText('I accept the terms and the privacy policy'))
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/auth/registro')[0][1].headers['Accept-Language']).toBe('en')
  })
})

describe('Verificar el correo (CCPIP-328)', () => {
  it('usa el enlace una sola vez, quita el token de la direccion y ofrece ingresar', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/verificar-correo': [200, { email: 'lucia@correo.pe' }] })
    const user = userEvent.setup()
    abrirEn('/verificar-correo?token=abc123')

    const exito = (await screen.findByText('Correo verificado')).closest('[role="status"]')
    expect(within(exito).getByText(/lucia@correo.pe ya está activa/)).toBeInTheDocument()
    expect(window.location.search).toBe('')
    expect(llamadas(fetchMock, 'POST /api/auth/verificar-correo')).toHaveLength(1)
    expect(cuerpo(fetchMock, 'POST /api/auth/verificar-correo')).toEqual({ token: 'abc123' })

    await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
    expect(window.location.pathname).toBe('/ingresar')
    expect(await screen.findByLabelText('Correo electrónico')).toHaveValue('lucia@correo.pe')
  })

  it('un enlace ya usado invita a ingresar', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION, 'POST /api/auth/verificar-correo': [410, { code: 'ENLACE_USADO', error: 'Este enlace ya se usó.' }] })
    abrirEn('/verificar-correo?token=usado')
    expect(await screen.findByText('Este enlace ya se usó')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeInTheDocument()
  })

  it('con un enlace vencido se pide otro con el correo', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': SIN_SESION,
      'POST /api/auth/verificar-correo': [410, { code: 'ENLACE_VENCIDO', error: 'El enlace venció.' }],
      'POST /api/auth/verificar-correo/reenviar': [202, null],
    })
    const user = userEvent.setup()
    abrirEn('/verificar-correo?token=vencido')
    expect(await screen.findByText('El enlace venció')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Correo electrónico'), 'lucia@correo.pe')
    await user.click(screen.getByRole('button', { name: 'Enviar un enlace nuevo' }))
    expect(await screen.findByText(/te enviamos un enlace nuevo/)).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'POST /api/auth/verificar-correo/reenviar')).toEqual({ email: 'lucia@correo.pe' })
  })

  it('sin token no llama al servidor y explica que el enlace no sirve', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/verificar-correo')
    expect(await screen.findByText('El enlace no es válido')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'POST /api/auth/verificar-correo')).toHaveLength(0)
  })
})
