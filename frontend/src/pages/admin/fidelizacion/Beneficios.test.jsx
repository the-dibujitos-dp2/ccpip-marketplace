import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../../App.jsx'
import { diaLima } from '../../../formato.js'
import { copia, llamadas, PUBLICAS, servidor, SESION, SESION_SUB } from '../../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const BASE = '/api/admin/fidelizacion'
const normal = (texto) => texto.replace(/ /g, ' ')

const NIVEL = { id: 1, nombre: 'Plata', pedidosMinimos: 5, multiplicador: 1.5 }
const CUPON = {
  id: 4, nombre: 'S/ 10 en tu próxima compra', descripcion: null, costoPuntos: 100, tipo: 'MONTO', valor: 10, montoMinimo: 30,
  topeDescuento: null, vigenciaDias: 30, activo: true, canjes: 3, usados: 1, descuentoOtorgado: 10, registradoPor: 'Lucía Paredes',
  registradoEn: '2026-11-16T15:00:00Z', disponibleDesde: '2026-11-16', disponibleHasta: null, estado: 'ACTIVO',
}
const CAMPANA = {
  id: 2, nombre: 'Semana del cacao', descripcion: null, desde: '2026-11-14', hasta: '2026-11-20', finEfectivo: '2026-11-20', puntos: 2,
  cadaSoles: 10, montoMinimo: 0, topePuntos: null, estado: 'PROGRAMADA', registradoPor: 'Lucía Paredes', registradoEn: '2026-11-13T15:00:00Z',
  finalizadoPor: null, finalizadoEn: null, resumen: { pedidos: 0, ventas: 0, clientes: 0, puntosOtorgados: 0 },
}
const CODIGO = {
  id: 7, campanaId: 2, codigo: 'CACAO10', tipo: 'PORCENTAJE', valor: 10, montoMinimo: 0, topeDescuento: 4, usosMaximos: 100, usosPorCliente: 1,
  activo: true, usos: 12, descuentoOtorgado: 48, registradoEn: '2026-11-16T15:00:00Z',
}

const comoSub = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUB],
  [`GET ${BASE}/beneficios`]: () => [200, { niveles: [NIVEL], cupones: [CUPON] }],
  [`GET ${BASE}`]: () => [200, { hoy: '2026-11-13', reglaBase: { puntos: 1, cadaSoles: 10 }, totales: { puntosOtorgados: 0, puntosDeCampanas: 0, clientes: 0, pedidos: 0 }, campanas: [CAMPANA] }],
  [`GET ${BASE}/campanas/2/codigos`]: () => [200, [CODIGO]],
  ...rutas,
})

describe('Niveles y cupones del sub administrador (CCPIP-88, CCPIP-89)', () => {
  it('lista los niveles y el catálogo de cupones con sus canjes', async () => {
    servidor(comoSub())
    abrirEn('/admin/fidelizacion/beneficios')
    expect(await screen.findByRole('link', { name: 'Niveles y cupones' })).toHaveAttribute('aria-current', 'page')
    const niveles = await screen.findByRole('table', { name: 'Niveles por recurrencia' })
    expect(within(niveles).getByText('Plata')).toBeInTheDocument()
    expect(within(niveles).getByText('5 pedidos entregados')).toBeInTheDocument()
    expect(within(niveles).getByText('×1.5')).toBeInTheDocument()
    const cupones = screen.getByRole('table', { name: 'Cupones para canjear con puntos' })
    expect(normal(within(cupones).getByText(/de descuento/).textContent)).toBe('S/ 10.00 de descuento · desde S/ 30.00 · vale 30 días tras canjearlo')
    expect(within(cupones).getByText('3 / 1')).toBeInTheDocument()
  })

  it('agrega un nivel y valida el multiplicador', async () => {
    const user = userEvent.setup()
    const fetch = servidor(comoSub({ [`POST ${BASE}/niveles`]: [201, [NIVEL, { id: 2, nombre: 'Oro', pedidosMinimos: 15, multiplicador: 2 }]] }))
    abrirEn('/admin/fidelizacion/beneficios')
    await user.click(await screen.findByRole('button', { name: 'Agregar nivel' }))
    const dialogo = screen.getByRole('dialog', { name: 'Agregar nivel' })
    await user.type(within(dialogo).getByLabelText('Nombre'), 'Oro')
    await user.type(within(dialogo).getByLabelText('Desde cuántos pedidos entregados'), '15')
    await user.type(within(dialogo).getByLabelText('Multiplicador de puntos'), '1')
    await user.click(within(dialogo).getByRole('button', { name: 'Guardar' }))
    expect(within(dialogo).getByText('Usa un número mayor que 1 y hasta 5')).toBeInTheDocument()

    await user.clear(within(dialogo).getByLabelText('Multiplicador de puntos'))
    await user.type(within(dialogo).getByLabelText('Multiplicador de puntos'), '2')
    await user.click(within(dialogo).getByRole('button', { name: 'Guardar' }))
    expect(await screen.findByText('Agregaste el nivel.')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetch, `POST ${BASE}/niveles`)[0][1].body)).toEqual({ nombre: 'Oro', pedidosMinimos: 15, multiplicador: 2 })
    expect(within(screen.getByRole('table', { name: 'Niveles por recurrencia' })).getByText('Oro')).toBeInTheDocument()
  })

  it('crea un cupón de porcentaje con tope y lo oculta del catálogo', async () => {
    const user = userEvent.setup()
    const nuevo = { ...CUPON, id: 5, nombre: '15 % en cacao', tipo: 'PORCENTAJE', valor: 15, topeDescuento: 20, montoMinimo: 0, canjes: 0, usados: 0, descuentoOtorgado: 0 }
    const fetch = servidor(comoSub({
      [`POST ${BASE}/cupones`]: [201, [CUPON, nuevo]],
      [`PUT ${BASE}/cupones/4/activo`]: [200, [{ ...CUPON, activo: false }, nuevo]],
    }))
    abrirEn('/admin/fidelizacion/beneficios')
    await user.click(await screen.findByRole('button', { name: 'Nuevo cupón' }))
    const dialogo = screen.getByRole('dialog', { name: 'Nuevo cupón' })
    await user.type(within(dialogo).getByLabelText('Nombre'), '15 % en cacao')
    await user.type(within(dialogo).getByLabelText('Cuesta (puntos)'), '150')
    await user.selectOptions(within(dialogo).getByLabelText('Tipo de descuento'), 'PORCENTAJE')
    await user.type(within(dialogo).getByLabelText('Porcentaje de descuento'), '15')
    await user.type(within(dialogo).getByLabelText('Tope en S/ (opcional)'), '20')
    await user.click(within(dialogo).getByRole('button', { name: 'Guardar' }))

    expect(await screen.findByText('Agregaste el cupón al catálogo.')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetch, `POST ${BASE}/cupones`)[0][1].body)).toEqual({
      nombre: '15 % en cacao', descripcion: null, costoPuntos: 150, vigenciaDias: 30,
      beneficio: { tipo: 'PORCENTAJE', valor: 15, montoMinimo: null, topeDescuento: 20 },
      disponibleDesde: diaLima(), disponibleHasta: null,
    })
    await user.click(screen.getByRole('button', { name: 'Finalizar S/ 10 en tu próxima compra' }))
    expect(await screen.findByText(/Finalizaste «S\/ 10 en tu próxima compra»/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reactivar S/ 10 en tu próxima compra' })).toBeInTheDocument()
  })

  it('separa los cupones por estado, como las campañas, con su periodo', async () => {
    const user = userEvent.setup()
    const programado = { ...CUPON, id: 6, nombre: 'Navidad', disponibleDesde: '2026-12-20', disponibleHasta: '2026-12-31', estado: 'PROGRAMADO' }
    const finalizado = { ...CUPON, id: 8, nombre: 'Fiestas Patrias', disponibleDesde: '2026-07-20', disponibleHasta: '2026-07-31', estado: 'FINALIZADO' }
    servidor(comoSub({ [`GET ${BASE}/beneficios`]: () => [200, { niveles: [NIVEL], cupones: [CUPON, programado, finalizado] }] }))
    abrirEn('/admin/fidelizacion/beneficios')

    const pestanas = await screen.findByRole('tablist', { name: 'Filtrar cupones por estado' })
    expect(within(pestanas).getAllByRole('tab').map((p) => p.textContent))
      .toEqual(['Todos (3)', 'Activos (1)', 'Programados (1)', 'Finalizados (1)'])
    const tabla = () => screen.getByRole('table', { name: 'Cupones para canjear con puntos' })
    expect(within(tabla()).getByText(/Desde el 16 nov 2026, sin fecha de fin/)).toBeInTheDocument()

    await user.click(within(pestanas).getByRole('tab', { name: 'Programados (1)' }))
    expect(within(tabla()).getByText('Navidad')).toBeInTheDocument()
    expect(within(tabla()).queryByText('Fiestas Patrias')).not.toBeInTheDocument()
    expect(within(tabla()).getByText('Programado')).toBeInTheDocument()

    // Uno que termino por su fecha se reabre editando el periodo, no con un boton
    await user.click(within(pestanas).getByRole('tab', { name: 'Finalizados (1)' }))
    expect(within(tabla()).getByText('Fiestas Patrias')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^(Finalizar|Reactivar) Fiestas Patrias$/ })).not.toBeInTheDocument()
  })

  it('el fin del periodo de un cupón no puede ser antes de su inicio', async () => {
    const user = userEvent.setup()
    const fetch = servidor(comoSub())
    abrirEn('/admin/fidelizacion/beneficios')
    await user.click(await screen.findByRole('button', { name: 'Editar S/ 10 en tu próxima compra' }))
    const dialogo = screen.getByRole('dialog', { name: 'Editar cupón' })
    expect(within(dialogo).getByLabelText('Disponible desde')).toHaveValue('16/11/2026')
    await user.type(within(dialogo).getByLabelText('Hasta (opcional)'), '10/11/2026')
    await user.click(within(dialogo).getByRole('button', { name: 'Guardar' }))
    expect(within(dialogo).getByText('El fin es el mismo día del inicio o después')).toBeInTheDocument()
    expect(llamadas(fetch, `PUT ${BASE}/cupones/4`)).toHaveLength(0)
  })
})

describe('Códigos de descuento de una campaña', () => {
  it('lista sus usos y crea uno nuevo en mayúsculas', async () => {
    const user = userEvent.setup()
    const fetch = servidor(comoSub({
      [`POST ${BASE}/campanas/2/codigos`]: [201, [{ ...CODIGO, id: 8, codigo: 'CAFE5', tipo: 'MONTO', valor: 5, topeDescuento: null, usos: 0, descuentoOtorgado: 0, usosMaximos: null }, CODIGO]],
    }))
    abrirEn('/admin/fidelizacion/campanas/2')
    const tabla = await screen.findByRole('table', { name: 'Códigos de descuento' })
    expect(within(tabla).getByText('CACAO10')).toBeInTheDocument()
    expect(within(tabla).getByText('12 de 100')).toBeInTheDocument()
    expect(normal(within(tabla).getByText(/% de descuento/).textContent)).toBe('10 % de descuento, hasta S/ 4.00 · 1 uso por cliente')

    await user.click(screen.getByRole('button', { name: 'Nuevo código' }))
    const dialogo = screen.getByRole('dialog', { name: 'Nuevo código' })
    await user.type(within(dialogo).getByLabelText('Código'), 'cafe5')
    await user.type(within(dialogo).getByLabelText('Descuento en S/'), '5')
    await user.click(within(dialogo).getByRole('button', { name: 'Crear código' }))
    expect(await screen.findByText('Creaste el código CAFE5.')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetch, `POST ${BASE}/campanas/2/codigos`)[0][1].body)).toEqual({
      codigo: 'CAFE5', beneficio: { tipo: 'MONTO', valor: 5, montoMinimo: null, topeDescuento: null }, usosMaximos: null, usosPorCliente: 1,
    })
  })
})

describe('Mis puntos del comprador (CCPIP-173, 374, 88, 89)', () => {
  const PUNTOS = {
    saldo: 120,
    nivel: { pedidosEntregados: 3, actual: null, siguiente: NIVEL, faltan: 2 },
    niveles: [NIVEL],
    movimientos: [
      { tipo: 'ACUMULACION', puntos: 85, fecha: '2026-11-15T15:00:00Z', referencia: 'CCP-000021', campana: 'Semana del cacao', nivel: null },
      { tipo: 'CANJE', puntos: 50, fecha: '2026-11-14T15:00:00Z', referencia: 'S/ 5 de regalo', campana: null, nivel: null },
    ],
    catalogo: [
      { id: 4, nombre: 'S/ 10 en tu próxima compra', descripcion: null, costoPuntos: 100, tipo: 'MONTO', valor: 10, montoMinimo: 30, topeDescuento: null, vigenciaDias: 30, alcanza: true },
      { id: 6, nombre: 'S/ 25 de regalo', descripcion: null, costoPuntos: 250, tipo: 'MONTO', valor: 25, montoMinimo: 0, topeDescuento: null, vigenciaDias: 30, alcanza: false },
    ],
    cupones: [],
  }
  const canjeado = { id: 11, codigo: 'PTSABCD234', nombre: 'S/ 10 en tu próxima compra', tipo: 'MONTO', valor: 10, montoMinimo: 30, topeDescuento: null, canjeadoEn: '2026-11-16T15:00:00Z', venceEn: '2026-12-16T15:00:00Z', estado: 'DISPONIBLE', pedido: null }
  const rutas = (extra = {}) => ({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION], 'GET /api/carrito': [200, { items: [] }], 'GET /api/puntos': () => [200, copia(PUNTOS)], ...extra })

  it('ve su saldo, cuánto le falta para el siguiente nivel y canjea un cupón', async () => {
    const user = userEvent.setup()
    const fetch = servidor(rutas({ 'POST /api/puntos/cupones/4/canjear': [200, { ...copia(PUNTOS), saldo: 20, cupones: [canjeado] }] }))
    abrirEn('/cuenta/puntos')
    expect(await screen.findByText('120')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Te faltan 2 pedidos para Plata' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Canjear S/ 25 de regalo' })).toBeDisabled()
    expect(screen.getByText('Te faltan 130 puntos')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Canjear S/ 10 en tu próxima compra' }))
    const dialogo = screen.getByRole('dialog', { name: '¿Canjear tus puntos?' })
    expect(within(dialogo).getByText('Usarás 100 puntos en «S/ 10 en tu próxima compra». Te quedarán 20.')).toBeInTheDocument()
    await user.click(within(dialogo).getByRole('button', { name: 'Canjear' }))

    expect(await screen.findByText('Canjeaste «S/ 10 en tu próxima compra»: tu código es PTSABCD234')).toBeInTheDocument()
    expect(llamadas(fetch, 'POST /api/puntos/cupones/4/canjear')).toHaveLength(1)
    const panel = screen.getByRole('tabpanel', { name: 'Mis cupones' })
    expect(within(panel).getByText('PTSABCD234')).toBeInTheDocument()
    expect(within(panel).getByText('Disponible')).toBeInTheDocument()
  })

  it('muestra sus movimientos: lo ganado por pedidos y lo canjeado', async () => {
    const user = userEvent.setup()
    servidor(rutas())
    abrirEn('/cuenta/puntos')
    await user.click(await screen.findByRole('tab', { name: 'Movimientos' }))
    const panel = screen.getByRole('tabpanel', { name: 'Movimientos' })
    expect(within(panel).getByText('Pedido CCP-000021')).toBeInTheDocument()
    expect(within(panel).getByText('+85')).toBeInTheDocument()
    expect(within(panel).getByText('Canje: S/ 5 de regalo')).toBeInTheDocument()
    expect(within(panel).getByText('−50')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mis puntos' })).toHaveAttribute('aria-current', 'page')
  })
})
