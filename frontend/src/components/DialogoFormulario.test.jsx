import React from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import DialogoFormulario from './DialogoFormulario.jsx'

describe('DialogoFormulario', () => {
  it('restaura el foco al disparador conectado cuando Escape desmonta el formulario', async () => {
    const user = userEvent.setup()
    const cancelar = vi.fn()
    function Caso() {
      const [abierto, setAbierto] = React.useState(false)
      return <>
        <button onClick={() => setAbierto(true)}>Abrir formulario</button>
        {abierto && <DialogoFormulario titulo="Formulario" confirmar="Guardar" onEnviar={() => {}} onCancelar={() => { cancelar(); setAbierto(false) }} />}
      </>
    }
    render(<Caso />)
    const disparador = screen.getByRole('button', { name: 'Abrir formulario' })
    await user.click(disparador)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    screen.getByRole('button', { name: 'Cancelar' }).focus()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toHaveFocus()
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
    expect(cancelar).toHaveBeenCalledOnce()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(disparador).toHaveFocus()
  })

  it('cierra con Cancelar y devuelve el foco al disparador', async () => {
    const user = userEvent.setup()
    function Caso() {
      const [abierto, setAbierto] = React.useState(false)
      return <>
        <button onClick={() => setAbierto(true)}>Abrir formulario</button>
        {abierto && <DialogoFormulario titulo="Formulario" confirmar="Guardar" onEnviar={() => {}} onCancelar={() => setAbierto(false)} />}
      </>
    }
    render(<Caso />)
    const disparador = screen.getByRole('button', { name: 'Abrir formulario' })
    await user.click(disparador)
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(disparador).toHaveFocus()
  })

  it('mantiene el dialogo ocupado abierto y no cancela mientras espera', async () => {
    const onCancelar = vi.fn()
    render(<DialogoFormulario titulo="Formulario" confirmar="Guardar" enviando onEnviar={() => {}} onCancelar={onCancelar} />)
    const dialogo = screen.getByRole('dialog')
    expect(dialogo).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    expect(onCancelar).not.toHaveBeenCalled()
  })
})
