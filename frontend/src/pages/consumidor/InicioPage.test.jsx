import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import InicioPage from './InicioPage.jsx'
import { FOTOGRAFIAS_REFERENCIA } from '../../components/fotografiasEditoriales.js'
import { ContextoVisualConsumidor } from '../../components/ContextoVisualConsumidor.jsx'

const obtenerInicio = vi.fn()
vi.mock('../../api/catalogo.js', () => ({ obtenerInicio: (...args) => obtenerInicio(...args), obtenerMasVendidos: vi.fn(), obtenerNovedades: vi.fn() }))
vi.mock('../../components/layout/LayoutTienda.jsx', () => ({ default: ({ children }) => <div>{children}</div> }))

const preparar = (lineas, fotosLineas = {}) => {
  obtenerInicio.mockResolvedValue({ tiendas: [], lineas, fotosLineas, destacados: [] })
  return render(<ContextoVisualConsumidor.Provider value={true}><InicioPage /></ContextoVisualConsumidor.Provider>)
}

describe('categorías del inicio', () => {
  it('prioriza la foto propia API sobre la referencial', async () => {
    preparar([{ codigo: 'SUPERFRUTOS', nombre: 'Superfrutos' }], { SUPERFRUTOS: '/api/foto-propia.webp' })
    await screen.findByRole('heading', { name: /Todo tiene/ })
    const enlace = document.querySelector('.inicio-categoria')
    expect(enlace.querySelector('img')).toHaveAttribute('src', '/api/foto-propia.webp')
    expect(enlace).not.toHaveTextContent(/LeRoc|referencial/i)
  })

  it('usa imagen de referencia existente y leyenda atribuida cuando falta foto API', async () => {
    preparar([{ codigo: 'SUPERFRUTOS', nombre: 'Superfrutos' }])
    await screen.findByRole('heading', { name: /Todo tiene/ })
    const enlace = document.querySelector('.inicio-categoria')
    expect(enlace.querySelector('img')).toHaveAttribute('src', FOTOGRAFIAS_REFERENCIA.SUPERFRUTOS.src)
    expect(enlace).toHaveTextContent('Planta de camu camu')
    expect(enlace).toHaveTextContent('LeRoc, CC BY-SA 3.0')
  })

  it('muestra ausencia compacta para línea desconocida sin inventar foto', async () => {
    preparar([{ codigo: 'NUEVA_LINEA', nombre: 'Nueva línea' }])
    await screen.findByRole('heading', { name: /Todo tiene/ })
    const enlace = document.querySelector('.inicio-categoria')
    expect(enlace.querySelector('img')).toBeNull()
    expect(enlace.querySelector('.inicio-categoria__sin-foto')).toBeInTheDocument()
  })
})
