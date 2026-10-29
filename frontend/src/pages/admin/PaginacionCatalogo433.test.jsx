import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, PUBLICAS, servidor, SESION_SUPER } from '../../test/servidor.js'

const LINEAS = '/api/admin/catalogo/lineas'
const PRODUCTOS = '/api/admin/catalogo/productos'
const nombres = (prefijo, cantidad) => Array.from({ length: cantidad }, (_, i) => `${prefijo} ${String(i + 1).padStart(2, '0')}`)
const lineas = nombres('Línea paginada', 21).map((nombre, i) => ({
  ...copia(DATOS.lineasMaestras.lineas[0]), id: 100 + i, codigo: `PAG-${i + 1}`, nombre,
}))
const productos = nombres('Producto paginado', 21).map((nombre, i) => ({
  ...copia(DATOS.productosMaestros.productos[0]), id: 100 + i, codigo: `PAG-${i + 1}`, nombre,
  linea: { codigo: 'PAG', nombre: 'Línea paginada', activa: true },
}))
function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}
const comoSuper = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUPER],
  'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
  'GET /api/admin/autorizaciones': () => [200, copia(DATOS.autorizacionesAdmin)],
  [ `GET ${LINEAS}` ]: () => [200, { contadores: { activos: 21, inactivos: 0 }, lineas }],
  [ `GET ${PRODUCTOS}` ]: () => [200, { contadores: { activos: 21, inactivos: 0 }, productos }],
  ...rutas,
})

describe('Paginación del catálogo (HU-433)', () => {
  it('muestra 20 líneas en la primera página y la línea 21 en la segunda; permite volver', async () => {
    servidor(comoSuper())
    abrirEn('/admin/catalogo')
    await screen.findByRole('table', { name: 'Líneas' })
    expect(screen.getByText('Línea paginada 01')).toBeInTheDocument()
    expect(screen.getByText('Línea paginada 20')).toBeInTheDocument()
    expect(screen.queryByText('Línea paginada 21')).not.toBeInTheDocument()
    expect(screen.getByText(/21 registros/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Siguiente|Next/i }))
    expect(await screen.findByText('Línea paginada 21')).toBeInTheDocument()
    expect(screen.getByText(/21 registros/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Anterior|Previous/i }))
    expect(await screen.findByText('Línea paginada 01')).toBeInTheDocument()
    expect(screen.queryByText('Línea paginada 21')).not.toBeInTheDocument()
  })

  it('muestra 20 productos en la primera página y el registro 21 con total 21 en la segunda', async () => {
    servidor(comoSuper())
    abrirEn('/admin/catalogo')
    await userEvent.click(await screen.findByRole('tab', { name: 'Productos' }))
    await screen.findByRole('table', { name: 'Productos' })
    expect(screen.getByText('Producto paginado 01')).toBeInTheDocument()
    expect(screen.getByText('Producto paginado 20')).toBeInTheDocument()
    expect(screen.queryByText('Producto paginado 21')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Siguiente|Next/i }))
    expect(await screen.findByText('Producto paginado 21')).toBeInTheDocument()
    expect(screen.getByText(/21 registros/)).toBeInTheDocument()
  })

  it('reinicia a la página 1 al aplicar búsqueda de productos', async () => {
    const filtrados = productos.slice(0, 1)
    servidor(comoSuper({ [`GET ${PRODUCTOS}?q=Producto`]: [200, { contadores: { activos: 1, inactivos: 0 }, productos: filtrados }] }))
    abrirEn('/admin/catalogo')
    await userEvent.click(await screen.findByRole('tab', { name: 'Productos' }))
    await screen.findByRole('table', { name: 'Productos' })
    await userEvent.click(screen.getByRole('button', { name: /Siguiente|Next/i }))
    await screen.findByText('Producto paginado 21')
    await userEvent.type(screen.getByLabelText('Buscar por nombre'), 'Producto')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))
    expect(await screen.findByText('Producto paginado 01')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Producto paginado 21')).not.toBeInTheDocument())
  })

  it('muestra correctamente los totales de un solo registro y de un catálogo vacío', async () => {
    servidor(comoSuper({
      [ `GET ${LINEAS}` ]: [200, { contadores: { activos: 1, inactivos: 0 }, lineas: lineas.slice(0, 1) }],
      [ `GET ${PRODUCTOS}` ]: [200, { contadores: { activos: 0, inactivos: 0 }, productos: [] }],
    }))
    abrirEn('/admin/catalogo')
    await screen.findByRole('table', { name: 'Líneas' })
    expect(screen.getByText('Línea paginada 01')).toBeInTheDocument()
    expect(screen.getByText('1 activos · 0 inactivos')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'Productos' }))
    expect(await screen.findByText(/No hay productos/)).toBeInTheDocument()
    expect(screen.queryByText('Producto paginado 01')).not.toBeInTheDocument()
  })
})
