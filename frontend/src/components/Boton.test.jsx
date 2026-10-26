import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.jsx'
import iconoCheck from '../assets/icons/check.svg'
import { PUBLICAS, servidor, SIN_SESION } from '../test/servidor.js'
import Boton from './Boton.jsx'
import Dialogo from './Dialogo.jsx'

/** El aro de la accion en curso: decorativo, dentro del boton. */
const aro = (boton) => boton.querySelector('.boton__giro')

describe('Botón con acción en curso (CCPIP-413)', () => {
  it('mientras carga muestra el aro en lugar del ícono, se deshabilita y lo anuncia', () => {
    const { rerender } = render(<Boton icono={iconoCheck}>Guardar</Boton>)
    const boton = screen.getByRole('button', { name: 'Guardar' })
    expect(aro(boton)).toBeNull()
    expect(boton.querySelector('.icono')).not.toBeNull()
    expect(boton).toBeEnabled()
    expect(boton).not.toHaveAttribute('aria-busy')

    rerender(<Boton icono={iconoCheck} cargando>Guardando…</Boton>)
    const ocupado = screen.getByRole('button', { name: 'Guardando…' })
    expect(aro(ocupado)).toHaveAttribute('aria-hidden', 'true')
    expect(ocupado.querySelector('.icono')).toBeNull()
    expect(ocupado).toBeDisabled()
    expect(ocupado).toHaveAttribute('aria-busy', 'true')
    // Conserva su tipo y su color: no se ve como un boton bloqueado
    expect(ocupado).toHaveClass('boton--primario', 'boton--cargando')
  })

  it('un botón solo deshabilitado no muestra el aro', () => {
    render(<Boton disabled>Cancelar</Boton>)
    const boton = screen.getByRole('button', { name: 'Cancelar' })
    expect(boton).toBeDisabled()
    expect(aro(boton)).toBeNull()
    expect(boton).not.toHaveClass('boton--cargando')
  })

  it('en un diálogo, solo el botón de confirmar muestra el aro; cancelar solo se bloquea', () => {
    render(<Dialogo abierto titulo="Desactivar" confirmar="Desactivando…" onConfirmar={vi.fn()} onCancelar={vi.fn()} ocupado>Seguro</Dialogo>)
    const confirmar = screen.getByRole('button', { name: 'Desactivando…' })
    const cancelar = screen.getByRole('button', { name: 'Cancelar' })
    expect(aro(confirmar)).not.toBeNull()
    expect(confirmar).toHaveAttribute('aria-busy', 'true')
    expect(aro(cancelar)).toBeNull()
    expect(cancelar).toBeDisabled()
  })

  it('al ingresar, el botón muestra el aro mientras espera la respuesta y lo quita al terminar', async () => {
    let responder
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': SIN_SESION,
      'POST /api/auth/login': () => new Promise((resolver) => { responder = resolver }),
    })
    window.history.replaceState(null, '', '/ingresar')
    render(<App />)
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Correo electrónico'), 'consumidor@ccpip.test')
    await user.type(screen.getByLabelText('Contraseña'), 'incorrecta')
    await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))

    const ingresando = await screen.findByRole('button', { name: 'Ingresando…' })
    expect(aro(ingresando)).not.toBeNull()
    expect(ingresando).toBeDisabled()

    responder([401, { code: 'CREDENCIALES_INVALIDAS', error: 'Correo o contraseña incorrectos' }])
    const boton = await screen.findByRole('button', { name: 'Iniciar sesión' })
    expect(aro(boton)).toBeNull()
    expect(boton).toBeEnabled()
  })
})
