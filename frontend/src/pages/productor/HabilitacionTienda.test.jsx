import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const productor = (tienda, rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, { ...copia(DATOS.tiendaProductor), ...tienda }],
    ...rutas,
  })

afterEach(async () => {
  await i18n.changeLanguage('es')
})

describe('Lo que el productor ve de las decisiones de la Cámara (CCPIP-291, CCPIP-330, CCPIP-292)', () => {
  it('sin habilitar, arma su tienda pero no la puede publicar', async () => {
    productor({ publicada: false, habilitada: false })
    abrirEn('/productor/tienda')
    expect(await screen.findByText('Tu tienda aún no está habilitada')).toBeInTheDocument()
    expect(screen.getByText(/La Cámara la habilita y te avisaremos por correo/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Publicar tienda' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Guardar borrador' })).toBeEnabled()
  })

  it('con la venta revocada ve el motivo', async () => {
    productor({ ventaRevocada: true, motivoRevocacion: 'Documentación observada' })
    abrirEn('/productor/tienda')
    expect(await screen.findByText('La Cámara revocó la venta de tu organización')).toBeInTheDocument()
    expect(screen.getByText(/Motivo: «Documentación observada»/)).toBeInTheDocument()
    expect(screen.queryByText('Tu tienda aún no está habilitada')).not.toBeInTheDocument()
  })

  it('con las ventas bloqueadas ve el motivo (CCPIP-85)', async () => {
    productor({ ventaBloqueada: true, motivoBloqueo: 'Denuncias sin respuesta' })
    abrirEn('/productor/tienda')
    expect(await screen.findByText('La Cámara bloqueó las ventas de tu organización')).toBeInTheDocument()
    expect(screen.getByText(/Motivo: «Denuncias sin respuesta»/)).toBeInTheDocument()
    expect(screen.queryByText('La Cámara revocó la venta de tu organización')).not.toBeInTheDocument()
  })

  it('una línea nueva que la autorización no cubre pausa la venta y permite pedir otra revisión', async () => {
    const evidencias = copia(DATOS.evidenciasProductor)
    evidencias.autorizacion = { ...evidencias.autorizacion, estado: 'APROBADA', vigenteHasta: '2027-03-31', cubreLineas: false }
    productor({}, { 'GET /api/productor/productos/9/evidencias': () => [200, evidencias] })
    abrirEn('/productor/productos/9/evidencias')
    expect(await screen.findByText('La autorización no cubre una línea nueva')).toBeInTheDocument()
    expect(screen.getByText(/Adjunta evidencias para pedir una nueva revisión/)).toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    productor({ publicada: false, habilitada: false })
    abrirEn('/productor/tienda')
    expect(await screen.findByText('Your store is not enabled yet')).toBeInTheDocument()
  })
})
