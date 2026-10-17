import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const VIGENCIA = '/api/admin/parametros/vigencia-carrito'
const CONFIGURADA = { horas: 48, provisional: false, registradoPor: 'Ana Shuñaqui', registradoEn: '2026-10-17T19:30:00Z' }

const comoSuper = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUPER],
  'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
  'GET /api/admin/comision/pedidos': () => [200, copia(DATOS.comisionesAdmin)],
  [`GET ${VIGENCIA}`]: () => [200, copia(DATOS.vigenciaCarritoAdmin)],
  ...rutas,
})

const campo = () => screen.findByLabelText('Vida del carrito sin pagar (horas)')

describe('SA-02 Parámetros de la plataforma: vida del carrito (CCPIP-142)', () => {
  it('sin configurar muestra el valor provisional marcado como pendiente de definición', async () => {
    servidor(comoSuper())
    abrirEn('/admin/parametros')

    expect(await screen.findByRole('heading', { level: 1, name: 'Parámetros de la plataforma' })).toBeInTheDocument()
    expect(screen.getByText('Plataforma / Parámetros')).toBeInTheDocument()
    expect(await campo()).toHaveValue('2')
    expect(screen.getByText('Pendiente de definición del stakeholder')).toBeInTheDocument()
    expect(screen.getByText('Rige el valor provisional de 2 horas: aún no se configuró.')).toBeInTheDocument()
    expect(screen.getByText(/El carrito no reserva stock/)).toBeInTheDocument()

    const nav = within(screen.getByRole('navigation', { name: 'Super administrador' }))
    expect(nav.getByRole('link', { name: 'Parámetros' })).toHaveAttribute('aria-current', 'page')
  })

  it('configurada muestra quién y cuándo la cambió, sin la marca de pendiente', async () => {
    servidor(comoSuper({ [`GET ${VIGENCIA}`]: [200, CONFIGURADA] }))
    abrirEn('/admin/parametros')
    expect(await campo()).toHaveValue('48')
    expect(screen.getByText('Rige 48 horas · configurada por Ana Shuñaqui el 17 de octubre, 14:30.')).toBeInTheDocument()
    expect(screen.queryByText('Pendiente de definición del stakeholder')).not.toBeInTheDocument()
  })

  it('guarda las horas con PUT y muestra la nueva configuración', async () => {
    const fetchMock = servidor(comoSuper({
      [`PUT ${VIGENCIA}`]: (o) => [200, { ...CONFIGURADA, horas: JSON.parse(o.body).horas }],
    }))
    const user = userEvent.setup()
    abrirEn('/admin/parametros')
    const horas = await campo()
    await user.clear(horas)
    await user.type(horas, '24')
    await user.click(screen.getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('La vida del carrito ahora es de 24 horas')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, `PUT ${VIGENCIA}`)[0][1].body)).toEqual({ horas: 24 })
    expect(screen.getByText('Rige 24 horas · configurada por Ana Shuñaqui el 17 de octubre, 14:30.')).toBeInTheDocument()
    expect(screen.queryByText('Pendiente de definición del stakeholder')).not.toBeInTheDocument()
  })

  it('confirmar el valor provisional también lo configura', async () => {
    const fetchMock = servidor(comoSuper({ [`PUT ${VIGENCIA}`]: [200, { ...CONFIGURADA, horas: 2 }] }))
    const user = userEvent.setup()
    abrirEn('/admin/parametros')
    await campo()
    await user.click(screen.getByRole('button', { name: 'Guardar' }))
    expect(await screen.findByText('La vida del carrito ahora es de 2 horas')).toBeInTheDocument()
    expect(llamadas(fetchMock, `PUT ${VIGENCIA}`)).toHaveLength(1)
  })

  it.each([
    ['', 'Indica cuántas horas se guarda el carrito.'],
    ['0', 'Escribe un número entero de horas entre 1 y 720.'],
    ['721', 'Escribe un número entero de horas entre 1 y 720.'],
    ['1.5', 'Escribe un número entero de horas entre 1 y 720.'],
    ['48', 'Ya rige ese valor.'],
  ])('valida "%s" sin llamar al servidor', async (texto, mensaje) => {
    const fetchMock = servidor(comoSuper({ [`GET ${VIGENCIA}`]: [200, CONFIGURADA] }))
    const user = userEvent.setup()
    abrirEn('/admin/parametros')
    const horas = await campo()
    await user.clear(horas)
    if (texto) await user.type(horas, texto)
    await user.click(screen.getByRole('button', { name: 'Guardar' }))
    expect(await screen.findByText(mensaje)).toBeInTheDocument()
    expect(horas).toHaveAttribute('aria-invalid', 'true')
    expect(llamadas(fetchMock, `PUT ${VIGENCIA}`)).toHaveLength(0)
  })

  it('si el servidor rechaza el campo lo marca; otro error se explica en una alerta', async () => {
    let respuesta = [400, { code: 'DATOS_INVALIDOS', error: 'Revisa los datos del formulario', campos: { horas: 'El carrito se guarda al menos una hora' } }]
    servidor(comoSuper({ [`PUT ${VIGENCIA}`]: () => respuesta }))
    const user = userEvent.setup()
    abrirEn('/admin/parametros')
    const horas = await campo()
    await user.clear(horas)
    await user.type(horas, '5')
    await user.click(screen.getByRole('button', { name: 'Guardar' }))
    expect(await screen.findByText('Escribe un número entero de horas entre 1 y 720.')).toBeInTheDocument()

    respuesta = [403, { code: 'ACCESO_DENEGADO', error: 'No tienes permiso para esta acción' }]
    await user.click(screen.getByRole('button', { name: 'Guardar' }))
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No pudimos guardar la vida del carrito')
    expect(alerta).toHaveTextContent('No tienes permiso para esta acción')
  })

  it('si no carga lo explica', async () => {
    servidor(comoSuper({ [`GET ${VIGENCIA}`]: new Error('sin red') }))
    abrirEn('/admin/parametros')
    expect(await screen.findByText('No pudimos cargar los parámetros')).toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    try {
      servidor(comoSuper({ [`GET ${VIGENCIA}`]: [200, CONFIGURADA] }))
      abrirEn('/admin/parametros')
      expect(await screen.findByRole('heading', { level: 1, name: 'Platform parameters' })).toBeInTheDocument()
      expect(await screen.findByLabelText('Unpaid cart lifetime (hours)')).toHaveValue('48')
      expect(screen.getByText('48 hours apply · set by Ana Shuñaqui on 17 October, 14:30.')).toBeInTheDocument()
    } finally {
      await i18n.changeLanguage('es')
    }
  })

  it('el sub administrador no entra: vuelve a la comisión por pedido', async () => {
    const fetchMock = servidor(comoSuper({ 'GET /api/auth/me': [200, SESION_SUB] }))
    abrirEn('/admin/parametros')
    await waitFor(() => expect(window.location.pathname).toBe('/admin/comisiones'))
    expect(llamadas(fetchMock, `GET ${VIGENCIA}`)).toHaveLength(0)
  })

  it('el comprador no entra', async () => {
    servidor(comoSuper({ 'GET /api/auth/me': [200, SESION], 'GET /api/carrito': [200, copia(DATOS.carrito)] }))
    abrirEn('/admin/parametros')
    await waitFor(() => expect(window.location.pathname).not.toBe('/admin/parametros'))
  })
})
