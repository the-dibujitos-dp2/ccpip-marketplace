import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { validarDatos } from './PasoDatos.jsx'
import { copia, DATOS, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

// CCPIP-98: el panel del productor en ingles, sin restos en espanol
function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/panel': () => [200, copia(DATOS.panelProductor)],
    'GET /api/productor/productos': () => [200, copia(DATOS.productosProductor)],
    'GET /api/productor/productos/9': () => [200, copia(DATOS.edicion)],
    'GET /api/productor/productos/9/lotes': () => [200, copia(DATOS.lotes)],
    'GET /api/productor/ubicaciones': () => [200, copia(DATOS.ubicaciones)],
    ...rutas,
  })
}

describe('Productor en inglés (CCPIP-98)', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('la barra lateral, el panel y sus acciones salen en inglés', async () => {
    productor()
    abrirEn('/productor')
    expect(await screen.findByRole('link', { name: 'My store' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Batches' })).toBeInTheDocument()
    expect(await screen.findByText('Published products')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Publish product' }).length).toBeGreaterThan(0)
    expect(screen.queryByText('Productos publicados')).not.toBeInTheDocument()
  })

  it('lo que falta para publicar se traduce desde el texto del servidor', async () => {
    const borrador = {
      ...copia(DATOS.edicion),
      estado: 'BORRADOR',
      publicable: { listo: false, faltan: ['Un lote vigente con stock en un punto de acopio', 'Publicar tu tienda (Mi tienda)'] },
    }
    productor({ 'GET /api/productor/productos/9': () => [200, copia(borrador)] })
    abrirEn('/productor/productos/9?paso=lote')
    expect(await screen.findByText('A current batch with stock at a collection point')).toBeInTheDocument()
    expect(screen.getByText('Publish your store (My store)')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to My store' })).toHaveAttribute('href', '/productor/tienda')
  })

  it('Mi tienda sale en inglés', async () => {
    productor()
    abrirEn('/productor/tienda')
    expect(await screen.findByRole('heading', { name: 'How buyers see you' })).toBeInTheDocument()
    expect(screen.getByLabelText('Store name')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Filter gallery' })).toBeInTheDocument()
  })

  it('las validaciones del formulario de producto están en inglés', () => {
    const errores = validarDatos({ nombre: '', modalidad: 'AMBAS', precio: '10', precioMayorista: '12', cantidadMinimaMayorista: '', lineas: [], unidad: '' }, { paraPublicar: true })
    expect(errores.nombre).toBe('Enter the product name')
    expect(errores.precioMayorista).toBe('It must be lower than the retail price')
    expect(errores.lineas).toBe('Choose at least one line')
  })
})
