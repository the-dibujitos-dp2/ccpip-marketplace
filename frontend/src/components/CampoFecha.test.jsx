import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import i18n from '../i18n/index.js'
import Campo from './Campo.jsx'
import { aIso, aTexto } from './CampoFecha.jsx'

function Filtro({ inicial = '', min, max, cambios = [] }) {
  const [valor, setValor] = useState(inicial)
  return (
    <>
      <Campo etiqueta="Desde" type="date" value={valor} min={min} max={max}
        onChange={(e) => { cambios.push(e.target.value); setValor(e.target.value) }} />
      <button type="button" onClick={() => setValor('2026-01-15')}>Restablecer</button>
      <output aria-label="Valor">{valor}</output>
    </>
  )
}

afterEach(async () => {
  await i18n.changeLanguage('es')
})

describe('CCPIP-455 Filtros de fecha en español', () => {
  it('convierte lo escrito: dd/mm/aaaa, ddmmaaaa y aaaa-mm-dd; nada si no es una fecha real', () => {
    expect(aIso('05/11/2026')).toBe('2026-11-05')
    expect(aIso('5/1/2026')).toBe('2026-01-05')
    expect(aIso('05-11-2026')).toBe('2026-11-05')
    expect(aIso('05112026')).toBe('2026-11-05')
    expect(aIso('2026-11-05')).toBe('2026-11-05')
    expect(aIso('31/02/2026')).toBeNull()
    expect(aIso('05/11/26')).toBeNull()
    expect(aIso('')).toBeNull()
    expect(aTexto('2026-11-05')).toBe('05/11/2026')
    expect(aTexto('')).toBe('')
  })

  it('muestra la fecha como dd/mm/aaaa con su indicación en español y avisa en ISO solo al completarla', async () => {
    const cambios = []
    const user = userEvent.setup()
    render(<Filtro inicial="2026-11-05" cambios={cambios} />)
    const campo = screen.getByLabelText('Desde')
    expect(campo).toHaveValue('05/11/2026')
    expect(campo).toHaveAttribute('placeholder', 'dd/mm/aaaa')
    expect(campo).toHaveAttribute('type', 'text')

    await user.clear(campo)
    expect(cambios).toEqual([''])
    await user.type(campo, '01/10/202')
    expect(cambios).toEqual([''])
    await user.type(campo, '6')
    expect(cambios).toEqual(['', '2026-10-01'])
    expect(screen.getByLabelText('Valor')).toHaveTextContent('2026-10-01')

    // Lo incompleto vuelve a la ultima fecha valida al salir del campo
    await user.clear(campo)
    await user.type(campo, '3/1')
    await user.tab()
    expect(campo).toHaveValue('')
    await user.type(campo, '07112026')
    expect(campo).toHaveValue('07/11/2026')
    expect(screen.getByLabelText('Valor')).toHaveTextContent('2026-11-07')
  })

  it('pegar una fecha ISO también vale y un cambio desde afuera se muestra', async () => {
    const user = userEvent.setup()
    render(<Filtro inicial="2026-11-05" />)
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '2026-09-30' } })
    expect(screen.getByLabelText('Desde')).toHaveValue('30/09/2026')
    await user.click(screen.getByRole('button', { name: 'Restablecer' }))
    expect(screen.getByLabelText('Desde')).toHaveValue('15/01/2026')
  })

  it('el calendario muestra meses y días en español, respeta los límites y elige la fecha', async () => {
    const user = userEvent.setup()
    render(<Filtro inicial="2026-11-05" min="2026-11-03" max="2026-11-20" />)
    await user.click(screen.getByRole('button', { name: 'Abrir calendario: Desde' }))

    const calendario = within(screen.getByRole('dialog', { name: 'Calendario: Desde' }))
    expect(calendario.getByText('noviembre 2026')).toBeInTheDocument()
    expect(calendario.getAllByRole('columnheader').map((th) => th.textContent)).toEqual(['lu', 'ma', 'mi', 'ju', 'vi', 'sá', 'do'])
    // El dia elegido tiene el foco y su nombre completo
    const elegido = calendario.getByRole('button', { name: 'jueves 5 de noviembre de 2026' })
    expect(elegido).toHaveAttribute('aria-pressed', 'true')
    expect(elegido).toHaveFocus()
    expect(calendario.getByRole('button', { name: 'lunes 2 de noviembre de 2026' })).toBeDisabled()
    expect(calendario.getByRole('button', { name: 'sábado 21 de noviembre de 2026' })).toBeDisabled()

    // Con el teclado: una semana despues
    await user.keyboard('{ArrowDown}')
    expect(calendario.getByRole('button', { name: 'jueves 12 de noviembre de 2026' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toHaveValue('12/11/2026')
    expect(screen.getByLabelText('Valor')).toHaveTextContent('2026-11-12')
    expect(screen.getByRole('button', { name: 'Abrir calendario: Desde' })).toHaveFocus()
  })

  it('cambia de mes, se cierra con Escape y puede borrarse', async () => {
    const user = userEvent.setup()
    render(<Filtro inicial="2026-01-15" />)
    await user.click(screen.getByRole('button', { name: 'Abrir calendario: Desde' }))
    const calendario = () => within(screen.getByRole('dialog'))
    await user.click(calendario().getByRole('button', { name: 'Mes anterior' }))
    expect(calendario().getByText('diciembre 2025')).toBeInTheDocument()
    await user.click(calendario().getByRole('button', { name: 'Mes siguiente' }))
    await user.click(calendario().getByRole('button', { name: 'Mes siguiente' }))
    expect(calendario().getByText('febrero 2026')).toBeInTheDocument()
    expect(calendario().getByRole('button', { name: 'Hoy' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toHaveValue('15/01/2026')

    await user.click(screen.getByRole('button', { name: 'Abrir calendario: Desde' }))
    await user.click(calendario().getByRole('button', { name: 'Borrar' }))
    expect(screen.getByLabelText('Desde')).toHaveValue('')
    expect(screen.getByLabelText('Valor')).toHaveTextContent('')
  })

  it('en inglés el calendario y la indicación siguen el idioma elegido', async () => {
    await i18n.changeLanguage('en')
    const user = userEvent.setup()
    render(<Filtro inicial="2026-11-05" />)
    expect(screen.getByLabelText('Desde')).toHaveAttribute('placeholder', 'dd/mm/yyyy')
    await user.click(screen.getByRole('button', { name: 'Open calendar: Desde' }))
    const calendario = within(screen.getByRole('dialog'))
    expect(calendario.getByText('November 2026')).toBeInTheDocument()
    expect(calendario.getAllByRole('columnheader')[0]).toHaveTextContent('Mo')
    expect(calendario.getByRole('button', { name: 'Thursday, 5 November 2026' })).toBeInTheDocument()
  })
})
