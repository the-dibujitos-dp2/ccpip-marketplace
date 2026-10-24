import { render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from '../App.jsx'
import { copia, DATOS, PUBLICAS, servidor, SESION_ACOPIO, SESION_SUB } from '../test/servidor.js'
import Cargando from './Cargando.jsx'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

/** Una respuesta que no llega hasta que la prueba la suelta; `pedida` dice si la pantalla ya la pidio. */
function retenida() {
  let soltar = null
  const respuesta = () => new Promise((resolver) => { soltar = resolver })
  return { respuesta, pedida: () => soltar !== null, soltar: (r) => soltar(r) }
}

/** El indicador comun: un estado con el aro (decorativo) y el texto. */
function indicador() {
  const estado = screen.getByText('Cargando…').closest('[role="status"]')
  expect(estado).toHaveClass('cargando')
  expect(estado.querySelector('.cargando__giro')).toHaveAttribute('aria-hidden', 'true')
  return estado
}

describe('Indicador de carga (CCPIP-413)', () => {
  it('anuncia que se está cargando, con un texto propio si se le da', () => {
    const { rerender } = render(<Cargando />)
    expect(within(screen.getByRole('status')).getByText('Cargando…')).toBeInTheDocument()

    rerender(<Cargando texto="Cargando tu carrito…" />)
    expect(within(screen.getByRole('status')).getByText('Cargando tu carrito…')).toBeInTheDocument()
  })

  it('el tablero del punto lo muestra mientras espera sus pedidos y lo quita al llegar', async () => {
    const pedidos = retenida()
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_ACOPIO], 'GET /api/acopio/pedidos': pedidos.respuesta })
    abrirEn('/acopio/pedidos')

    // La sesion ya se comprobo y la pantalla espera su consulta
    await waitFor(() => expect(pedidos.pedida()).toBe(true))
    indicador()
    pedidos.soltar([200, copia(DATOS.pedidos)])
    expect(await screen.findByText('MC-00155')).toBeInTheDocument()
    expect(screen.queryByText('Cargando…')).not.toBeInTheDocument()
  })

  it('la tienda pública lo muestra mientras espera las comunidades', async () => {
    const tiendas = retenida()
    servidor({ ...PUBLICAS, 'GET /api/tiendas': tiendas.respuesta })
    abrirEn('/comunidades')

    // La sesion ya se comprobo y la pantalla espera su consulta
    await waitFor(() => expect(tiendas.pedida()).toBe(true))
    indicador()
    tiendas.soltar([200, copia(DATOS.tiendas)])
    expect(await screen.findByText('Asoc. Nii Joi')).toBeInTheDocument()
    expect(screen.queryByText('Cargando…')).not.toBeInTheDocument()
  })

  it('si la consulta falla, se quita y queda el error', async () => {
    const productores = retenida()
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_SUB], 'GET /api/admin/productores': productores.respuesta })
    abrirEn('/admin/productores')

    // La sesion ya se comprobo y la pantalla espera su consulta
    await waitFor(() => expect(productores.pedida()).toBe(true))
    indicador()
    productores.soltar([500, { code: 'ERROR', error: 'Falla' }])
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('Cargando…')).not.toBeInTheDocument()
  })
})
