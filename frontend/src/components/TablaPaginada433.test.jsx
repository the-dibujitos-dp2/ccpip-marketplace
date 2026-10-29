import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import TablaReporte from './TablaReporte.jsx'
import { TablaBandeja } from '../pages/admin/revision.jsx'

const registros = (prefijo = 'registro') => Array.from({ length: 21 }, (_, i) => ({ id: i + 1, nombre: `${prefijo}-${i + 1}` }))
const columnasReporte = [{ clave: 'nombre', etiqueta: 'Nombre', valor: (fila) => fila.nombre, texto: true }]
const columnasBandeja = [{ clase: 'tabla__celda--flex', texto: 'Nombre' }]
const filaBandeja = (solicitud) => <div key={solicitud.id} role="row"><span role="cell">{solicitud.nombre}</span></div>

function reporte(filas, filterKey = 'todos', nota = 'Nota completa de prueba') {
  return <TablaReporte id="reporte" titulo="Reporte" columnas={columnasReporte} filas={filas} clave={(f) => f.id}
    vacio="Sin resultados" vacioTexto="No hay registros" nota={nota} filterKey={filterKey} />
}

function bandeja(solicitudes, estado = 'PENDIENTE') {
  return <TablaBandeja textos="moderacion" estado={estado} datos={{ solicitudes }} columnas={columnasBandeja} fila={filaBandeja} />
}

describe('tablas paginadas 433', () => {
  it('pagina las 21 filas del componente compartido y conserva metadata y nota', async () => {
    const user = userEvent.setup()
    render(reporte(registros()))
    let tabla = screen.getByRole('table', { name: 'Reporte' })
    expect(within(tabla).getAllByRole('row')).toHaveLength(21)
    expect(within(tabla).getByText('registro-1')).toBeInTheDocument()
    expect(within(tabla).queryByText('registro-21')).not.toBeInTheDocument()
    expect(screen.getByText('Página 1 · 21 registros')).toBeInTheDocument()
    expect(screen.getByText('Nota completa de prueba')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))
    tabla = screen.getByRole('table', { name: 'Reporte' })
    expect(within(tabla).getAllByRole('row')).toHaveLength(2)
    expect(within(tabla).getByText('registro-21')).toBeInTheDocument()
    expect(screen.getByText('Página 2 · 21 registros')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Anterior' }))
    expect(within(screen.getByRole('table', { name: 'Reporte' })).getByText('registro-1')).toBeInTheDocument()
  })

  it('reinicia a la primera pagina al cambiar el filtro del reporte', async () => {
    const user = userEvent.setup()
    const { rerender } = render(reporte(registros(), 'filtro-a'))
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))
    rerender(reporte(registros('filtrado'), 'filtro-b'))
    expect(screen.getByText('Página 1 · 21 registros')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Reporte' })).getByText('filtrado-1')).toBeInTheDocument()
  })

  it('pagina las 21 solicitudes reales de TablaBandeja y conserva el total exacto', async () => {
    const user = userEvent.setup()
    render(bandeja(registros('solicitud')))
    let tabla = screen.getByRole('table', { name: 'Pendientes' })
    expect(within(tabla).getAllByRole('row')).toHaveLength(21)
    expect(within(tabla).getByText('solicitud-1')).toBeInTheDocument()
    expect(within(tabla).queryByText('solicitud-21')).not.toBeInTheDocument()
    expect(screen.getByText('Página 1 · 21 registros')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))
    tabla = screen.getByRole('table', { name: 'Pendientes' })
    expect(within(tabla).getAllByRole('row')).toHaveLength(2)
    expect(within(tabla).getByText('solicitud-21')).toBeInTheDocument()
    expect(screen.getByText('Página 2 · 21 registros')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Anterior' }))
    expect(within(screen.getByRole('table', { name: 'Pendientes' })).getByText('solicitud-1')).toBeInTheDocument()
  })

  it('reinicia TablaBandeja a pagina uno al cambiar estado', async () => {
    const user = userEvent.setup()
    const { rerender } = render(bandeja(registros('pendiente'), 'PENDIENTE'))
    await user.click(screen.getByRole('button', { name: 'Siguiente' }))
    rerender(bandeja(registros('aprobada'), 'APROBADA'))
    expect(screen.getByText('Página 1 · 21 registros')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Aprobadas' })).getByText('aprobada-1')).toBeInTheDocument()
  })

  it('muestra el resumen vacio y metadata exacta para cero y un registro', () => {
    const { rerender } = render(reporte([]))
    expect(screen.getByRole('status')).toHaveTextContent('Sin resultados')
    expect(screen.getByText('Página 1 · 0 registros')).toBeInTheDocument()
    rerender(reporte(registros().slice(0, 1)))
    expect(screen.getByText('Página 1 · 1 registros')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Reporte' })).toHaveTextContent('registro-1')
    expect(screen.getByText('Nota completa de prueba')).toBeInTheDocument()
  })
})
