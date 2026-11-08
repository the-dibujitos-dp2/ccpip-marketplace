import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { GraficaAdmin } from './GraficaAdmin.jsx'

describe('visualizaciones administrativas', () => {
  it('presenta periodos de B2C y B2B seleccionables conservando valores nulos', () => {
    render(<GraficaAdmin tipo="finanzas" datos={{ periodos: [{ inicio: '2026-01-01', b2c: 5, b2b: 8, total: 13, acumulado: 13 }] }} titulo="Ventas por periodo" />)
    expect(screen.getByRole('heading', { name: 'Ventas por periodo — gráfico' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'B2C' }))
    expect(screen.getByRole('button', { name: /B2C.*S\/ 5/ })).toBeTruthy()
    expect(within(screen.getByLabelText('Puntos de la serie')).queryByRole('button', { name: /B2B/ })).toBeNull()
  })
})
