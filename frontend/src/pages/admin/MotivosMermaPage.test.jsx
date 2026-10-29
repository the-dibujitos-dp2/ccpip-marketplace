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
