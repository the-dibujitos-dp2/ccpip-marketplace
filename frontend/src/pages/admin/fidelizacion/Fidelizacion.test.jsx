import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../../App.jsx'
import { copia, llamadas, PUBLICAS, servidor, SESION, SESION_SUB } from '../../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

// Intl separa la moneda con un espacio no separable (U+00A0)
const normal = (texto) => texto.replace(/ /g, ' ')
const BASE = '/api/admin/fidelizacion'

const resumen = (pedidos, ventas, puntosOtorgados) => ({ pedidos, ventas, clientes: pedidos, puntosOtorgados })
const campana = (id, nombre, estado, extra = {}) => ({
  id, nombre, descripcion: null, desde: '2026-11-14', hasta: '2026-11-20', finEfectivo: '2026-11-20', puntos: 10, cadaSoles: 1,
  montoMinimo: 50, topePuntos: 900, estado, registradoPor: 'Lucía Paredes', registradoEn: '2026-11-13T15:00:00Z',
  finalizadoPor: null, finalizadoEn: null, resumen: resumen(0, 0, 0), ...extra,
})

const PANEL = {
  hoy: '2026-11-16',
  reglaBase: { puntos: 1, cadaSoles: 10, tope: null },
  totales: { puntosOtorgados: 4000, puntosDeCampanas: 3000, clientes: 12, pedidos: 30 },
  campanas: [
    campana(3, 'Fiestas de fin de año', 'PROGRAMADA', { desde: '2026-12-20', hasta: '2026-12-31', finEfectivo: '2026-12-31', puntos: 2, cadaSoles: 10, montoMinimo: 0, topePuntos: null }),
    campana(2, 'Semana del cacao', 'ACTIVA', { resumen: resumen(8, 960.5, 1250) }),
    campana(1, 'Lanzamiento', 'FINALIZADA', { desde: '2026-10-01', hasta: '2026-10-07', finEfectivo: '2026-10-07', resumen: resumen(5, 400, 700) }),
  ],
}

const indicador = (valor, anterior, variacion) => ({ valor, anterior, variacion })
const METRICAS = {
  campana: PANEL.campanas[1], desde: '2026-11-14', hasta: '2026-11-16', anteriorDesde: '2026-11-11', anteriorHasta: '2026-11-13',
  pedidos: indicador(8, 4, 100), ventas: indicador(960.5, 600, 60.1), ticketPromedio: indicador(120.06, 150, -20), clientes: indicador(6, 6, 0),
  puntosOtorgados: 1250, puntosPorAcreditar: 3400, pedidosEntregados: 3, pedidosPorEntregar: 5, pedidosBajoMinimo: 2,
  clientesNuevos: 2, clientesRecurrentes: 1,
  dias: [{ fecha: '2026-11-14', pedidos: 3, ventas: 300, puntos: 3000 }, { fecha: '2026-11-15', pedidos: 5, ventas: 660.5, puntos: 6600 }],
  productos: [{ id: 8, nombre: 'Cacao en grano 1 kg', comunidad: 'C.N. Yarina', unidades: 6, ventas: 500 }],
}

const comoSub = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUB],
  'GET /api/admin/autorizaciones': [200, { items: [], conteo: {} }],
  [`GET ${BASE}`]: () => [200, copia(PANEL)],
  [`GET ${BASE}/campanas/2/metricas`]: () => [200, copia(METRICAS)],
  [`GET ${BASE}/campanas/2/codigos`]: () => [200, []],
  [`GET ${BASE}/campanas/3/codigos`]: () => [200, []],
  ...rutas,
})

describe('Fidelización del sub administrador (CCPIP-86)', () => {
  it('aparece en el menú del sub administrador', async () => {
    servidor(comoSub())
    abrirEn('/admin/fidelizacion')
    expect(await screen.findByRole('link', { name: 'Fidelización' })).toHaveAttribute('aria-current', 'page')
  })

  it('resume lo acumulado y destaca la campaña activa', async () => {
    servidor(comoSub())
    abrirEn('/admin/fidelizacion')

    expect(await screen.findByRole('heading', { level: 1, name: 'Fidelización' })).toBeInTheDocument()
    expect(within(await screen.findByRole('region', { name: 'Puntos otorgados' })).getByText('75 % por campañas')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Campaña activa' })).getByText('Semana del cacao')).toBeInTheDocument()

    const activa = screen.getByRole('region', { name: 'Semana del cacao' })
    expect(within(activa).getByText('10 puntos por cada S/ 1.00 · Pedido mínimo S/ 50.00 · Tope de 900 puntos por pedido')).toBeInTheDocument()
    expect(within(activa).getByRole('progressbar', { name: 'Día 3 de 7' })).toBeInTheDocument()
    expect(normal(within(activa).getByText('Ventas').nextSibling.textContent)).toBe('S/ 960.50')
    expect(within(activa).getByRole('link', { name: 'Ver métricas' })).toHaveAttribute('href', '/admin/fidelizacion/campanas/2')
  })

  it('filtra las campañas por estado y solo deja editar las que aún no empiezan', async () => {
    const user = userEvent.setup()
    servidor(comoSub())
    abrirEn('/admin/fidelizacion')
    const tabla = await screen.findByRole('table', { name: 'Campañas' })
    expect(within(tabla).getAllByRole('row')).toHaveLength(4)
    expect(within(tabla).getByRole('link', { name: 'Editar Fiestas de fin de año' })).toHaveAttribute('href', '/admin/fidelizacion/campanas/3/editar')
    expect(within(tabla).queryByRole('link', { name: 'Editar Semana del cacao' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Finalizadas (1)' }))
    expect(within(screen.getByRole('table', { name: 'Campañas' })).getAllByRole('row')).toHaveLength(2)
    expect(screen.getByRole('link', { name: 'Lanzamiento' })).toBeInTheDocument()
  })

  it('finaliza la campaña activa tras confirmarlo', async () => {
    const user = userEvent.setup()
    const finalizada = campana(2, 'Semana del cacao', 'FINALIZADA')
    const fetch = servidor(comoSub({ [`POST ${BASE}/campanas/2/finalizar`]: [200, finalizada] }))
    abrirEn('/admin/fidelizacion')
    await user.click(await screen.findByRole('button', { name: 'Finalizar Semana del cacao' }))
    const dialogo = screen.getByRole('dialog', { name: '¿Finalizar la campaña?' })
    expect(within(dialogo).getByText(/«Semana del cacao» dejará de regir desde este momento/)).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('button', { name: 'Finalizar ahora' }))

    expect(await screen.findByText('Finalizaste «Semana del cacao».')).toBeInTheDocument()
    expect(llamadas(fetch, `POST ${BASE}/campanas/2/finalizar`)).toHaveLength(1)
    expect(llamadas(fetch, `GET ${BASE}`).length).toBeGreaterThan(1)
  })

  it('sin campañas invita a programar la primera', async () => {
    servidor(comoSub({ [`GET ${BASE}`]: () => [200, { ...copia(PANEL), campanas: [] }] }))
    abrirEn('/admin/fidelizacion')
    expect(await screen.findByText('Aún no hay campañas')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Campaña activa' })).getByText('Hoy rige la regla base')).toBeInTheDocument()
  })
})

describe('Nueva campaña (CCPIP-86)', () => {
  it('con una regla frecuente muestra su efecto y la programa', async () => {
    const user = userEvent.setup()
    const creada = campana(9, 'Semana del café', 'PROGRAMADA')
    const fetch = servidor(comoSub({
      [`POST ${BASE}/campanas`]: [201, creada],
      [`GET ${BASE}`]: () => [200, { ...copia(PANEL), campanas: [creada] }],
    }))
    abrirEn('/admin/fidelizacion/nueva')
    await user.type(await screen.findByLabelText('Nombre'), 'Semana del café')
    await user.type(screen.getByLabelText('Hasta'), '22/11/2026')
    await user.click(screen.getByRole('button', { name: 'Triple (3 por S/ 10)' }))
    await user.type(screen.getByLabelText('Pedido mínimo en S/ (opcional)'), '60')

    expect(screen.getByText('3 veces la regla base')).toBeInTheDocument()
    const previa = within(screen.getByRole('complementary', { name: 'Vista previa' }))
    // S/ 50 queda debajo del minimo: rige la base
    expect(previa.getByRole('row', { name: /S\/\s50\.00/ })).toHaveTextContent(/55$/)
    expect(previa.getByRole('row', { name: /S\/\s100\.00/ })).toHaveTextContent(/1030$/)

    await user.click(screen.getByRole('button', { name: 'Programar campaña' }))
    await waitFor(() => expect(llamadas(fetch, `POST ${BASE}/campanas`)).toHaveLength(1))
    expect(JSON.parse(llamadas(fetch, `POST ${BASE}/campanas`)[0][1].body)).toEqual({
      nombre: 'Semana del café', descripcion: null, desde: '2026-11-16', hasta: '2026-11-22', puntos: 3, cadaSoles: 10,
      montoMinimo: 60, topePuntos: null,
    })
    expect(await screen.findByRole('heading', { level: 1, name: 'Semana del café' })).toBeInTheDocument()
  })

  it('no deja una regla que no mejora la base y marca el campo que el servidor rechaza', async () => {
    const user = userEvent.setup()
    servidor(comoSub({
      [`POST ${BASE}/campanas`]: [409, { code: 'CAMPANA_SE_CRUZA', error: 'Se cruza con la campaña «Semana del cacao». Elige otras fechas.' }],
    }))
    abrirEn('/admin/fidelizacion/nueva')
    await user.type(await screen.findByLabelText('Nombre'), 'Otra')
    await user.type(screen.getByLabelText('Hasta'), '18/11/2026')
    await user.clear(screen.getByLabelText('Puntos que gana'))
    await user.type(screen.getByLabelText('Puntos que gana'), '1')
    await user.click(screen.getByRole('button', { name: 'Programar campaña' }))
    expect(await screen.findByText('La campaña debe dar más puntos que la regla base (1 punto por cada S/ 10)')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Puntos que gana'))
    await user.type(screen.getByLabelText('Puntos que gana'), '2')
    await user.click(screen.getByRole('button', { name: 'Programar campaña' }))
    expect(await screen.findByText('Se cruza con la campaña «Semana del cacao». Elige otras fechas.')).toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toHaveAttribute('aria-invalid', 'true')
  })

  it('una campaña que ya empezó no se edita', async () => {
    servidor(comoSub())
    abrirEn('/admin/fidelizacion/campanas/2/editar')
    expect(await screen.findByText('Esta campaña ya empezó y no se puede editar')).toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
  })
})

describe('Métricas de una campaña (CCPIP-86)', () => {
  it('compara con el periodo anterior y muestra los puntos otorgados y por acreditar', async () => {
    servidor(comoSub())
    abrirEn('/admin/fidelizacion/campanas/2')

    expect(await screen.findByRole('heading', { level: 1, name: 'Semana del cacao' })).toBeInTheDocument()
    const pedidos = await screen.findByRole('region', { name: 'Pedidos' })
    expect(within(pedidos).getByText('+100% frente al periodo anterior')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Ticket promedio' })).getByText('-20% frente al periodo anterior')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Por acreditar' })).getByText('Cuando se entreguen 5 pedidos')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Puntos otorgados' })).getByText('De 3 pedidos entregados')).toBeInTheDocument()
    expect(screen.getByText('2 pedidos no llegaron al mínimo de S/ 50.00')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Ventas por día' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Productos más vendidos' })).toBeInTheDocument()
    expect(screen.getByText('Cacao en grano 1 kg · C.N. Yarina')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Editar' })).not.toBeInTheDocument()
  })

  it('una campaña programada muestra cuándo empieza y su vista previa, sin pedir métricas', async () => {
    const fetch = servidor(comoSub())
    abrirEn('/admin/fidelizacion/campanas/3')
    expect(await screen.findByText(/^Empieza el 20 de diciembre de 2026$/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Editar' })).toHaveAttribute('href', '/admin/fidelizacion/campanas/3/editar')
    expect(screen.getByRole('complementary', { name: 'Vista previa' })).toHaveTextContent('Puntos por el total de productos')
    expect(llamadas(fetch, `GET ${BASE}/campanas/3/metricas`)).toHaveLength(0)
  })
})

describe('Puntos del comprador (CCPIP-86)', () => {
  const PUNTO = { id: 1, nombre: 'Centro Nacional Lima', departamento: 'Lima', provincia: 'Lima', distrito: 'Lima', direccion: 'Sede central CCPIP' }
  const ENTREGADO = {
    id: 77, numero: 'CCP-000077', estado: 'ENTREGADO', modalidad: 'RECOJO', total: 85.5, creadoEn: '2026-11-14T15:00:00Z',
    confirmadoEn: '2026-11-14T15:01:00Z', punto: PUNTO, personaAutorizada: null, celularContacto: '987654321',
    lineas: [{ productoId: 8, nombre: 'Cacao en grano 1 kg', tienda: 'C.N. Yarina', cantidad: 1, precioUnitario: 85.5, subtotal: 85.5 }],
    pagos: [], historial: [], comision: null, entrega: null, costoEnvio: 0, totalAPagar: 85.5, incidencias: [], reembolsado: 0,
    totalFinal: 85.5, moneda: 'PEN', puntos: { puntos: 850, campana: 'Semana del cacao' },
  }

  it('ve los puntos que ganó su pedido entregado y con qué campaña', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION], 'GET /api/carrito': [200, { items: [] }], 'GET /api/pedidos/77': [200, ENTREGADO] })
    abrirEn('/pedidos/77')
    const puntos = await screen.findByRole('region', { name: 'Ganaste 850 puntos' })
    expect(within(puntos).getByText('Con la campaña «Semana del cacao», al recibir tu pedido.')).toBeInTheDocument()
  })
})

describe('Mi historial con las campañas (CCPIP-86)', () => {
  it('el sub administrador ve las campañas que programó y llega a cada una', async () => {
    const accion = { clave: 'CP-2', fecha: '2026-11-16T15:00:00Z', tipo: 'FIDELIZACION', accion: 'CAMPANA_PUNTOS_CREADA',
      sobre: 'Semana del cacao', detalle: null, objeto: 'CAMPANA', objetoId: 2, motivo: null }
    servidor(comoSub({ 'GET /api/admin/mi-historial': [200, { desde: '2026-10-18', hasta: '2026-11-16', tipo: null, acciones: [accion] }] }))
    abrirEn('/admin/mi-historial')
    expect(await screen.findByText('Programó campaña de puntos')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Semana del cacao' })).toHaveAttribute('href', '/admin/fidelizacion/campanas/2')
    expect(screen.getByRole('option', { name: 'Fidelización' })).toBeInTheDocument()
  })
})
