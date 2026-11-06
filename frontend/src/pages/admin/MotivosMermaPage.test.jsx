import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, PUBLICAS, servidor, SESION_SUPER } from '../../test/servidor.js'

function abrir(datos) {
  window.history.replaceState(null, '', '/admin/motivos-merma')
  servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_SUPER], 'GET /api/admin/motivos-merma': [200, copia(datos)] })
  return render(<App />)
}

describe('Paginacion local de motivos merma', () => {
  it('navega más de 20 motivos y el resumen conserva el total completo', async () => {
    const datos = Array.from({ length: 24 }, (_, i) => ({ id: i + 1, nombre: `Motivo ${i + 1}`, nombreEn: `Reason ${i + 1}`, activo: true, requiereDetalle: false, usos: 0, sistema: false }))
    abrir(datos)
    const tabla = within(await screen.findByRole('table'))
    expect(await tabla.findByText('Motivo 1')).toBeInTheDocument()
    expect(tabla.queryByText('Motivo 21')).not.toBeInTheDocument()
    await screen.findByRole('button', { name: 'Siguiente' }).then((b) => b.click())
    expect(await tabla.findByText('Motivo 21')).toBeInTheDocument()
    expect(tabla.queryByText('Motivo 1')).not.toBeInTheDocument()
    expect(screen.getByText('24 causas activas de 24')).toBeInTheDocument()
  })
})

describe('Acciones de cada causa de merma (mejoras de laboratorio)', () => {
  it('editar lleva su marco y desactivar va en rojo', async () => {
    abrir([{ id: 1, nombre: 'Golpe', nombreEn: 'Damage', activo: true, requiereDetalle: false, usos: 0, sistema: false }])
    const tabla = within(await screen.findByRole('table'))
    expect(await tabla.findByRole('button', { name: /^Editar/ })).toHaveClass('boton--secundario')
    expect(tabla.getByRole('button', { name: /^Desactivar/ })).toHaveClass('boton--peligro')
  })
})

describe('CCPIP-449 listado de causas de merma', () => {
  it('no muestra la columna "En inglés"; el nombre en inglés sigue en el formulario', async () => {
    abrir([{ id: 1, nombre: 'Golpe', nombreEn: 'Damage', activo: true, requiereDetalle: false, usos: 0, sistema: false }])
    const tabla = within(await screen.findByRole('table'))
    expect(await tabla.findByText('Golpe')).toBeInTheDocument()
    expect(tabla.queryByRole('columnheader', { name: 'En inglés' })).not.toBeInTheDocument()
    expect(tabla.queryByText('Damage')).not.toBeInTheDocument()
  })
})
