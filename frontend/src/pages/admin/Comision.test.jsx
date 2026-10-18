import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR, SESION_SUB, SESION_SUPER, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

// Intl separa la moneda con un espacio no separable (U+00A0)
const normal = (texto) => texto.replace(/\u00a0/g, ' ')
const COMISION = '/api/admin/comision'
const PEDIDOS = '/api/admin/comision/pedidos'

const comoSuper = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUPER],
  [`GET ${COMISION}`]: () => [200, copia(DATOS.comisionAdmin)],
  [`GET ${PEDIDOS}`]: () => [200, copia(DATOS.comisionesAdmin)],
  'GET /api/admin/autorizaciones': () => [200, copia(DATOS.autorizacionesAdmin)],
  ...rutas,
})

const comoSub = (rutas = {}) => comoSuper({ 'GET /api/auth/me': [200, SESION_SUB], ...rutas })

/** El estado que devolveria el servidor tras programar un cambio. */
function conProgramado(porcentaje, desde) {
  const e = copia(DATOS.comisionAdmin)
  e.programado = { ...e.vigente, id: 3, porcentaje, vigenteDesde: desde, motivo: 'Ajuste para diciembre', estado: 'PROGRAMADA' }
  e.historial.unshift(e.programado)
  return e
}

const previa = () => within(screen.getByRole('complementary', { name: /^Vista previa por venta de S\/\s100\.00$/ }))

describe('SA-02 Comisión (CCPIP-90)', () => {
  it('muestra la comisión vigente, quién la configuró, la vista previa y el historial', async () => {
    servidor(comoSuper())
    abrirEn('/admin/comision')

    expect(await screen.findByRole('heading', { level: 1, name: 'Comisión por venta digital' })).toBeInTheDocument()
    expect(screen.getByText('Plataforma / Comisión')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: 'Comisión vigente: 10 %' })).toBeInTheDocument()
    expect(screen.getByText('Desde el 1 de octubre de 2026 · configurada por Ana Shuñaqui')).toBeInTheDocument()

    // La base es fija hasta que CCPIP-101 defina el flete
    const base = screen.getByLabelText('Base de cálculo')
    expect(base).toBeDisabled()
    expect(base).toHaveValue('PRECIO_SIN_ENVIO')
    expect(screen.getByText('Pendiente de confirmar con la Cámara (CCPIP-101).')).toBeInTheDocument()
    expect(screen.getByLabelText('Vigente desde')).toHaveValue('2026-10-16')

    expect(normal(previa().getByText('Comisión de la Cámara').nextSibling.textContent)).toBe('S/ 10.00')
    expect(normal(previa().getByText('Recibe el productor').nextSibling.textContent)).toBe('S/ 90.00')
    const historial = within(screen.getByRole('list', { name: 'Historial' })).getAllByRole('listitem')
    expect(historial.map((li) => within(li).getByText(/%$/).textContent)).toEqual(['10 %', '8 %'])
    expect(within(historial[0]).getByText('Vigente')).toBeInTheDocument()
    expect(within(historial[1]).getByText('Anterior')).toBeInTheDocument()
    expect(within(historial[1]).getByText('Desde 01/01/2026 · Ana Shuñaqui')).toBeInTheDocument()

    const nav = within(screen.getByRole('navigation', { name: 'Super administrador' }))
    expect(nav.getByRole('link', { name: 'Comisión' })).toHaveAttribute('aria-current', 'page')
  })

  it('la vista previa compara el porcentaje vigente con el nuevo', async () => {
    servidor(comoSuper())
    abrirEn('/admin/comision')
    await userEvent.type(await screen.findByLabelText('Nueva comisión (%)'), '12')
    expect(normal(previa().getByText('Comisión de la Cámara').nextSibling.textContent)).toBe('S/ 10.00→S/ 12.00')
    expect(normal(previa().getByText('Recibe el productor').nextSibling.textContent)).toBe('S/ 90.00→S/ 88.00')
  })

  it('valida porcentaje, fecha y motivo antes de enviar', async () => {
    const fetchMock = servidor(comoSuper())
    abrirEn('/admin/comision')
    const porcentaje = await screen.findByLabelText('Nueva comisión (%)')
    fireEvent.change(screen.getByLabelText('Vigente desde'), { target: { value: '' } })
    await userEvent.click(screen.getByRole('button', { name: 'Programar cambio' }))
    expect(screen.getByText('Indica el porcentaje')).toBeInTheDocument()
    expect(screen.getByText('Indica desde cuándo rige')).toBeInTheDocument()
    expect(screen.getByText('Explica el motivo del cambio')).toBeInTheDocument()

    await userEvent.type(porcentaje, '100')
    fireEvent.change(screen.getByLabelText('Vigente desde'), { target: { value: '2026-10-15' } })
    await userEvent.click(screen.getByRole('button', { name: 'Programar cambio' }))
    expect(screen.getByText('Usa un número de 0 a 99.99, con hasta 2 decimales')).toBeInTheDocument()
    expect(screen.getByText('La vigencia empieza hoy o en una fecha futura')).toBeInTheDocument()

    await userEvent.clear(porcentaje)
    await userEvent.type(porcentaje, '10')
    await userEvent.click(screen.getByRole('button', { name: 'Programar cambio' }))
    expect(screen.getByText('Ese ya es el porcentaje vigente')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${COMISION}`)).toHaveLength(0)
  })

  it('programa un cambio futuro con su motivo y después lo puede cancelar', async () => {
    const programado = conProgramado(12, '2026-12-01')
    const cancelado = copia(DATOS.comisionAdmin)
    cancelado.historial.unshift({ ...programado.programado, estado: 'CANCELADA', canceladoPor: 'Ana Shuñaqui' })
    const fetchMock = servidor(comoSuper({
      [`POST ${COMISION}`]: [201, programado],
      [`POST ${COMISION}/3/cancelar`]: [200, cancelado],
    }))
    abrirEn('/admin/comision')

    await userEvent.type(await screen.findByLabelText('Nueva comisión (%)'), '12')
    fireEvent.change(screen.getByLabelText('Vigente desde'), { target: { value: '2026-12-01' } })
    expect(screen.getByText('El nuevo porcentaje se aplica solo a pedidos confirmados desde el 01/12/2026.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Motivo del cambio'), 'Ajuste para diciembre')
    await userEvent.click(screen.getByRole('button', { name: 'Programar cambio' }))

    const [, opciones] = llamadas(fetchMock, `POST ${COMISION}`)[0]
    expect(JSON.parse(opciones.body)).toEqual({ porcentaje: 12, vigenteDesde: '2026-12-01', motivo: 'Ajuste para diciembre' })
    expect(await screen.findByText('Cambio programado: 12 % desde el 1 de diciembre de 2026', { selector: '.alerta__titulo' })).toBeInTheDocument()
    // Uno pendiente a la vez: el formulario se oculta hasta cancelarlo
    expect(screen.queryByLabelText('Nueva comisión (%)')).not.toBeInTheDocument()
    expect(screen.getByText('Motivo: Ajuste para diciembre')).toBeInTheDocument()
    expect(normal(previa().getByText('Comisión de la Cámara').nextSibling.textContent)).toBe('S/ 10.00')

    await userEvent.click(screen.getByRole('button', { name: 'Cancelar cambio programado' }))
    const dialogo = within(screen.getByRole('dialog', { name: '¿Cancelar el cambio programado?' }))
    expect(dialogo.getByText(/no cambiará a 12 % el 1 de diciembre de 2026/)).toBeInTheDocument()
    await userEvent.click(dialogo.getByRole('button', { name: 'Sí, cancelar cambio' }))

    expect(llamadas(fetchMock, `POST ${COMISION}/3/cancelar`)).toHaveLength(1)
    expect(await screen.findByText('Cambio programado cancelado')).toBeInTheDocument()
    expect(screen.getByLabelText('Nueva comisión (%)')).toBeInTheDocument()
    const historial = within(screen.getByRole('list', { name: 'Historial' })).getAllByRole('listitem')
    expect(within(historial[0]).getByText('Cancelada')).toBeInTheDocument()
  })

  it('con la fecha de hoy el cambio rige de inmediato', async () => {
    const aplicado = copia(DATOS.comisionAdmin)
    aplicado.vigente = { ...aplicado.vigente, id: 3, porcentaje: 12, vigenteDesde: '2026-10-16', motivo: 'Junta' }
    aplicado.historial = [aplicado.vigente, { ...DATOS.comisionAdmin.vigente, estado: 'ANTERIOR' }]
    servidor(comoSuper({ [`POST ${COMISION}`]: [201, aplicado] }))
    abrirEn('/admin/comision')

    await userEvent.type(await screen.findByLabelText('Nueva comisión (%)'), '12')
    expect(screen.getByText('El nuevo porcentaje se aplica a los pedidos que se confirmen desde ahora.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Motivo del cambio'), 'Junta')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar desde hoy' }))

    expect(await screen.findByText('La comisión del 12 % ya rige para los pedidos nuevos')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Comisión vigente: 12 %' })).toBeInTheDocument()
    expect(screen.getByLabelText('Nueva comisión (%)')).toHaveValue('')
  })

  it('un rechazo del servidor sobre la fecha se muestra en el campo', async () => {
    servidor(comoSuper({
      [`POST ${COMISION}`]: [409, { code: 'VIGENCIA_OCUPADA', error: 'Ya hay un cambio que rige desde esa fecha. Elige otra fecha.' }],
    }))
    abrirEn('/admin/comision')
    await userEvent.type(await screen.findByLabelText('Nueva comisión (%)'), '12')
    await userEvent.type(screen.getByLabelText('Motivo del cambio'), 'Junta')
    await userEvent.click(screen.getByRole('button', { name: 'Aplicar desde hoy' }))
    expect(await screen.findByText('Ya hay un cambio que rige desde esa fecha. Elige otra fecha.')).toBeInTheDocument()
    expect(screen.getByLabelText('Vigente desde')).toHaveAttribute('aria-invalid', 'true')
  })

  it('sin comisión configurada lo dice y no muestra montos de ejemplo', async () => {
    const sin = { ...copia(DATOS.comisionAdmin), vigente: null, historial: [] }
    servidor(comoSuper({ [`GET ${COMISION}`]: [200, sin] }))
    abrirEn('/admin/comision')
    expect(await screen.findByRole('heading', { level: 2, name: 'Sin comisión configurada' })).toBeInTheDocument()
    expect(previa().getAllByText('—')).toHaveLength(2)
    expect(screen.getByText('Aún no hay cambios registrados.')).toBeInTheDocument()
  })

  it('el sub administrador no configura la comisión: va a su módulo de autorizaciones', async () => {
    const fetchMock = servidor(comoSub())
    abrirEn('/admin/comision')
    expect(await screen.findByRole('heading', { level: 1, name: 'Autorizaciones de productos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/admin/autorizaciones')
    expect(llamadas(fetchMock, `GET ${COMISION}`)).toHaveLength(0)
  })
})

describe('Comisión por pedido (CCPIP-91)', () => {
  it('totaliza el rango y lista cada pedido con su comisión y neto, sin datos del comprador', async () => {
    servidor(comoSuper())
    abrirEn('/admin/comisiones')

    expect(await screen.findByRole('heading', { level: 1, name: 'Comisión por pedido' })).toBeInTheDocument()
    // El campo existe antes de que llegue la respuesta: se espera a que traiga el rango del servidor
    await waitFor(() => expect(screen.getByLabelText('Desde')).toHaveValue('2026-10-01'))
    expect(screen.getByLabelText('Hasta')).toHaveValue('2026-10-16')

    const kpi = (nombre) => within(screen.getByRole('region', { name: nombre }))
    expect(normal(kpi('Ventas confirmadas').getByText(/^S\//).textContent)).toBe('S/ 216.70')
    expect(kpi('Ventas confirmadas').getByText('3 pedidos')).toBeInTheDocument()
    expect(normal(kpi('Comisión de la Cámara').getByText(/^S\//).textContent)).toBe('S/ 13.59')
    expect(normal(kpi('Neto para productores').getByText(/^S\//).textContent)).toBe('S/ 101.11')
    expect(screen.getByText('1 pedido se confirmó sin comisión configurada')).toBeInTheDocument()

    const filas = within(screen.getByRole('table', { name: 'Pedidos confirmados' })).getAllByRole('row')
    expect(filas).toHaveLength(4)
    const [, varios, uno, sin] = filas.map((f) => within(f))
    expect(varios.getByText('CCP-000139')).toBeInTheDocument()
    expect(normal(varios.getByText(/\(12\.5 %\)/).textContent)).toBe('S/ 10.59 (12.5 %)')
    expect(normal(varios.getByText('S/ 74.11').textContent)).toBe('S/ 74.11')
    // Con varios productores, el neto de cada uno
    expect(normal(varios.getByText(/C\.N\. Yarina/).nextSibling.textContent)).toBe('Venta S/ 59.70 · neto S/ 52.24')
    expect(uno.queryByText(/neto/)).not.toBeInTheDocument()
    expect(normal(uno.getByText(/\(10 %\)/).textContent)).toBe('S/ 3.00 (10 %)')
    expect(sin.getByText('Sin comisión')).toBeInTheDocument()
    expect(sin.getByText('—')).toBeInTheDocument()
  })

  it('al cambiar las fechas vuelve a consultar ese rango', async () => {
    const fetchMock = servidor(comoSuper())
    abrirEn('/admin/comisiones')
    const desde = await screen.findByLabelText('Desde')
    await screen.findByRole('table', { name: 'Pedidos confirmados' })
    fireEvent.change(desde, { target: { value: '2026-09-01' } })
    expect(llamadas(fetchMock, `GET ${PEDIDOS}?desde=2026-09-01&hasta=2026-10-16`)).toHaveLength(1)
  })

  it('sin pedidos en el rango lo dice', async () => {
    const vacio = { ...copia(DATOS.comisionesAdmin), pedidos: [], resumen: { pedidos: 0, ventas: 0, comision: 0, neto: 0, sinComision: 0 } }
    servidor(comoSuper({ [`GET ${PEDIDOS}`]: [200, vacio] }))
    abrirEn('/admin/comisiones')
    expect(await screen.findByText('No hay pedidos confirmados en estas fechas.')).toBeInTheDocument()
    expect(screen.queryByText(/sin comisión configurada/)).not.toBeInTheDocument()
  })

  it('el sub administrador ingresa a autorizaciones y solo ve sus módulos (autorizaciones, denuncias, comisión por pedido y cuentas)', async () => {
    let conSesion = false
    servidor(comoSub({
      'GET /api/auth/me': () => (conSesion ? [200, SESION_SUB] : SIN_SESION),
      'POST /api/auth/login': () => {
        conSesion = true
        return [200, SESION_SUB]
      },
    }))
    abrirEn('/ingresar')
    await userEvent.type(await screen.findByLabelText('Correo electrónico'), 'sub_admin@ccpip.test')
    await userEvent.type(screen.getByLabelText('Contraseña'), 'Demo1234!')
    await userEvent.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Autorizaciones de productos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/admin/autorizaciones')
    const nav = within(screen.getByRole('navigation', { name: 'Sub administrador' }))
    expect(nav.getAllByRole('link').map((a) => a.textContent)).toEqual(['Autorizaciones', 'Denuncias', 'Comisión por pedido', 'Cuentas'])
    await userEvent.click(nav.getByRole('link', { name: 'Comisión por pedido' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Comisión por pedido' })).toBeInTheDocument()
  })

  it('el tablero tampoco es del sub administrador', async () => {
    const fetchMock = servidor(comoSub())
    abrirEn('/admin')
    expect(await screen.findByRole('heading', { level: 1, name: 'Autorizaciones de productos' })).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/admin/tablero')).toHaveLength(0)
  })

  it.each([
    ['productor', SESION_PRODUCTOR, 'productor'],
    ['consumidor', SESION, 'consumidor'],
  ])('un %s no la ve ni la pide al servidor', async (_, sesion, panel) => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, sesion], [`GET /api/panel/${panel}`]: [200, {}], 'GET /api/carrito': [200, copia(DATOS.carrito)] })
    abrirEn('/admin/comisiones')
    await screen.findByRole('heading', { level: 1 })
    expect(window.location.pathname).toBe('/inicio')
    expect(llamadas(fetchMock, `GET ${PEDIDOS}`)).toHaveLength(0)
  })
})

describe('Comisión en inglés (CCPIP-90, CCPIP-91)', () => {
  it('la configuración y la consulta salen en inglés', async () => {
    await i18n.changeLanguage('en')
    servidor(comoSuper())
    const { unmount } = abrirEn('/admin/comision')
    expect(await screen.findByRole('heading', { level: 1, name: 'Digital sales commission' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: 'Current commission: 10 %' })).toBeInTheDocument()
    expect(screen.getByText('Since 1 October 2026 · set by Ana Shuñaqui')).toBeInTheDocument()
    expect(screen.getByLabelText('Calculation base')).toBeDisabled()
    unmount()

    abrirEn('/admin/comisiones')
    expect(await screen.findByRole('heading', { level: 1, name: 'Commission by order' })).toBeInTheDocument()
    expect(await screen.findByText('1 order was confirmed without a commission set')).toBeInTheDocument()
    expect(screen.queryByText(/Comisión|pedidos/)).not.toBeInTheDocument()
  })
})
