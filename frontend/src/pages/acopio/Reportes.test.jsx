import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, llamadas, PUBLICAS, servidor, SESION_ACOPIO } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const BASE = '/api/acopio/reportes'
const RANGO = 'desde=2026-01-01&hasta=2026-03-18&agrupacion=MES'

const OPCIONES = {
  productores: [
    { id: 3, nombre: 'Asoc. Nii Joi' },
    { id: 5, nombre: 'C.N. Puerto Alegre' },
  ],
  productos: [
    { id: 9, nombre: 'Aceite de sacha inchi 250 ml' },
    { id: 10, nombre: 'Harina de aguaje 500 g' },
  ],
}

const COMUN = {
  puntos: 'Centro Nacional Lima',
  desde: '2026-01-01',
  hasta: '2026-03-18',
  agrupacion: 'MES',
  productor: null,
  producto: null,
  ...OPCIONES,
}

const ENTRADAS = {
  ...COMUN,
  totales: { recepcion: 80, transferencias: 12, total: 92, transferenciasPendientes: 1, unidadesPendientes: 7 },
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', recepcion: 50, transferencias: 12, total: 62, acumulado: 62 },
    { inicio: '2026-02-01', fin: '2026-02-28', recepcion: 0, transferencias: 0, total: 0, acumulado: 62 },
    { inicio: '2026-03-01', fin: '2026-03-18', recepcion: 30, transferencias: 0, total: 30, acumulado: 92 },
  ],
  porProducto: [
    { productoId: 9, producto: 'Aceite de sacha inchi 250 ml', productor: 'Asoc. Nii Joi', recepcion: 50, transferencias: 12, total: 62 },
    { productoId: 10, producto: 'Harina de aguaje 500 g', productor: 'C.N. Puerto Alegre', recepcion: 30, transferencias: 0, total: 30 },
  ],
}

const SALIDAS = {
  ...COMUN,
  totales: { ventasEntregadas: 5, ventasPorEntregar: 3, transferencias: 4, devoluciones: 2, merma: 1, total: 15 },
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', ventasEntregadas: 5, ventasPorEntregar: 0, transferencias: 0, devoluciones: 0, merma: 0, total: 5, acumulado: 5 },
    { inicio: '2026-02-01', fin: '2026-02-28', ventasEntregadas: 0, ventasPorEntregar: 0, transferencias: 0, devoluciones: 0, merma: 0, total: 0, acumulado: 5 },
    { inicio: '2026-03-01', fin: '2026-03-18', ventasEntregadas: 0, ventasPorEntregar: 3, transferencias: 4, devoluciones: 2, merma: 1, total: 10, acumulado: 15 },
  ],
  porProducto: [
    { productoId: 9, producto: 'Aceite de sacha inchi 250 ml', productor: 'Asoc. Nii Joi', ventas: 8, transferencias: 4, devoluciones: 2, merma: 1, total: 15 },
  ],
}

const VENTAS = {
  ...COMUN,
  totales: { pedidos: 2, pedidosAtendidos: 1, pedidosPorEntregar: 1, unidades: 8, b2c: 100, b2b: 60, total: 160 },
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', pedidos: 1, pedidosAtendidos: 1, pedidosPorEntregar: 0, unidades: 5, b2c: 100, b2b: 0, total: 100, acumulado: 100 },
    { inicio: '2026-02-01', fin: '2026-02-28', pedidos: 0, pedidosAtendidos: 0, pedidosPorEntregar: 0, unidades: 0, b2c: 0, b2b: 0, total: 0, acumulado: 100 },
    { inicio: '2026-03-01', fin: '2026-03-18', pedidos: 1, pedidosAtendidos: 0, pedidosPorEntregar: 1, unidades: 3, b2c: 0, b2b: 60, total: 60, acumulado: 160 },
  ],
  porProducto: [
    { productoId: 9, producto: 'Aceite de sacha inchi 250 ml', productor: 'Asoc. Nii Joi', unidades: 8, total: 160 },
  ],
}

const INVENTARIO = {
  ...COMUN,
  totales: { saldoInicial: 20, entradas: 92, salidas: 15, saldoFinal: 97, porEntregar: 3, existenciaFisica: 100 },
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', saldoInicial: 20, entradas: 62, salidas: 5, saldoFinal: 77 },
    { inicio: '2026-02-01', fin: '2026-02-28', saldoInicial: 77, entradas: 0, salidas: 0, saldoFinal: 77 },
    { inicio: '2026-03-01', fin: '2026-03-18', saldoInicial: 77, entradas: 30, salidas: 10, saldoFinal: 97 },
  ],
  porProducto: [
    { productoId: 9, producto: 'Aceite de sacha inchi 250 ml', productor: 'Asoc. Nii Joi', saldoInicial: 20, entradas: 62, salidas: 15, saldoFinal: 67, porEntregar: 3 },
    { productoId: 10, producto: 'Harina de aguaje 500 g', productor: 'C.N. Puerto Alegre', saldoInicial: 0, entradas: 30, salidas: 0, saldoFinal: 30, porEntregar: 0 },
  ],
}

function acopio(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_ACOPIO],
    [`GET ${BASE}/entradas`]: () => [200, copia(ENTRADAS)],
    ...rutas,
  })
}

// Los importes llevan un espacio duro entre S/ y la cifra
const celdas = (fila) => within(fila).getAllByRole('cell').map((c) => c.textContent.replace(/\u00a0/g, ' '))

describe('A-06 Reportes del punto de acopio', () => {
  afterEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('CCPIP-117: abre en las entradas del año, con lo recibido y lo que aun no llega', async () => {
    acopio()
    abrirEn('/acopio/reportes')

    expect(await screen.findByRole('heading', { level: 1, name: 'Reportes' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Reportes' })).toHaveAttribute('href', '/acopio/reportes')
    expect(await screen.findByText('Centro Nacional Lima / Reportes')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Entradas', pressed: true })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Mensual', pressed: true })).toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toHaveValue('2026-01-01')
    expect(screen.getByLabelText('Hasta')).toHaveValue('2026-03-18')
    expect(screen.getByLabelText('Productor')).toHaveValue('')

    expect(screen.getByRole('region', { name: 'Unidades recibidas' })).toHaveTextContent('92 und')
    expect(screen.getByRole('region', { name: 'Unidades recibidas' })).toHaveTextContent('80 por recepción · 12 por transferencia')
    // No atendidas: lo pedido que aun no llega
    expect(screen.getByRole('region', { name: 'Por llegar' })).toHaveTextContent('7 und')
    expect(screen.getByRole('region', { name: 'Por llegar' })).toHaveTextContent('1 transferencia pedida aún no llega')

    const periodos = within(screen.getByRole('table', { name: 'Entradas por período' })).getAllByRole('row')
    expect(periodos).toHaveLength(4)
    expect(celdas(periodos[1])).toEqual(['Enero de 2026', '', '50', '12', '62', '62'])
    // Un mes sin entradas aparece en cero y conserva el acumulado
    expect(celdas(periodos[2])).toEqual(['Febrero de 2026', '', '0', '0', '0', '62'])
    const productos = within(screen.getByRole('table', { name: 'Entradas por producto' })).getAllByRole('row')
    expect(celdas(productos[2])).toEqual(['Harina de aguaje 500 g', 'C.N. Puerto Alegre', '30', '0', '30'])
  })

  it('CCPIP-117: filtra por productor, producto y agrupacion con el rango que se ve', async () => {
    const fetchMock = acopio({
      [`GET ${BASE}/entradas?${RANGO}&productor=5`]: () => [200, {
        ...copia(ENTRADAS), productor: 5, totales: { ...ENTRADAS.totales, recepcion: 30, transferencias: 0, total: 30 },
        porProducto: [copia(ENTRADAS.porProducto[1])],
      }],
      [`GET ${BASE}/entradas?${RANGO}&productor=5&producto=10`]: () => [200, { ...copia(ENTRADAS), productor: 5, producto: 10 }],
      [`GET ${BASE}/entradas?desde=2026-01-01&hasta=2026-03-18&agrupacion=SEMANA&productor=5&producto=10`]: () => [200, {
        ...copia(ENTRADAS), productor: 5, producto: 10, agrupacion: 'SEMANA',
        periodos: [{ inicio: '2026-03-02', fin: '2026-03-08', recepcion: 30, transferencias: 0, total: 30, acumulado: 30 }],
      }],
    })
    const user = userEvent.setup()
    abrirEn('/acopio/reportes')
    await screen.findByRole('table', { name: 'Entradas por período' })

    fireEvent.change(screen.getByLabelText('Productor'), { target: { value: '5' } })
    expect(await screen.findByRole('region', { name: 'Unidades recibidas' })).toHaveTextContent('30 und')
    expect(screen.getByLabelText('Productor')).toHaveValue('5')

    fireEvent.change(screen.getByLabelText('Producto'), { target: { value: '10' } })
    await screen.findByDisplayValue('Harina de aguaje 500 g')
    await user.click(screen.getByRole('button', { name: 'Semanal' }))
    expect(await screen.findByText('2 mar 2026 al 8 mar 2026')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Semanal', pressed: true })).toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${BASE}/entradas?desde=2026-01-01&hasta=2026-03-18&agrupacion=SEMANA&productor=5&producto=10`)).toHaveLength(1)
  })

  it('CCPIP-117: sin entradas lo dice y un rango invalido muestra el error', async () => {
    acopio({
      [`GET ${BASE}/entradas?desde=2026-01-01&hasta=2026-03-18&agrupacion=DIA`]: () => [422, { code: 'RANGO_MUY_LARGO', error: 'Consulta como máximo un año a la vez.' }],
      [`GET ${BASE}/entradas`]: () => [200, { ...copia(ENTRADAS), porProducto: [] }],
    })
    const user = userEvent.setup()
    abrirEn('/acopio/reportes')
    expect(await screen.findByText('No hubo entradas en este período')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Diario' }))
    expect(await screen.findByText('No pudimos cargar el reporte')).toBeInTheDocument()
    expect(screen.getByText('Consulta como máximo un año a la vez.')).toBeInTheDocument()
  })

  it('CCPIP-341: las salidas separan las ventas entregadas de las que siguen por entregar', async () => {
    const fetchMock = acopio({
      [`GET ${BASE}/entradas?${RANGO}&productor=3`]: () => [200, { ...copia(ENTRADAS), productor: 3 }],
      [`GET ${BASE}/salidas?${RANGO}&productor=3`]: () => [200, { ...copia(SALIDAS), productor: 3 }],
    })
    const user = userEvent.setup()
    abrirEn('/acopio/reportes')
    await screen.findByRole('table', { name: 'Entradas por período' })
    fireEvent.change(screen.getByLabelText('Productor'), { target: { value: '3' } })
    await screen.findByDisplayValue('Asoc. Nii Joi')
    await user.click(screen.getByRole('button', { name: 'Salidas' }))

    expect(await screen.findByRole('region', { name: 'Unidades que salieron' })).toHaveTextContent('15 und')
    expect(screen.getByRole('region', { name: 'Unidades que salieron' })).toHaveTextContent('4 transferidas · 2 devueltas · 1 de merma')
    expect(screen.getByRole('region', { name: 'Ventas entregadas' })).toHaveTextContent('5 und')
    expect(screen.getByRole('region', { name: 'Ventas por entregar' })).toHaveTextContent('3 und')
    expect(screen.getByRole('button', { name: 'Salidas', pressed: true })).toBeInTheDocument()
    // Los filtros se conservan al cambiar de reporte
    expect(llamadas(fetchMock, `GET ${BASE}/salidas?${RANGO}&productor=3`)).toHaveLength(1)

    const periodos = within(screen.getByRole('table', { name: 'Salidas por período' })).getAllByRole('row')
    expect(within(periodos[0]).getByText('Venta pendiente')).toBeInTheDocument()
    expect(celdas(periodos[3])).toEqual(['Marzo de 2026', '0', '3', '4', '2', '1', '10', '15'])
    const productos = within(screen.getByRole('table', { name: 'Salidas por producto' })).getAllByRole('row')
    expect(celdas(productos[1])).toEqual(['Aceite de sacha inchi 250 ml', 'Asoc. Nii Joi', '8', '4', '2', '1', '15'])
  })

  it('CCPIP-342: las ventas cuentan los pedidos atendidos y por entregar, con su importe B2C y B2B', async () => {
    acopio({ [`GET ${BASE}/ventas?${RANGO}`]: () => [200, copia(VENTAS)] })
    const user = userEvent.setup()
    abrirEn('/acopio/reportes')
    await screen.findByRole('table', { name: 'Entradas por período' })
    await user.click(screen.getByRole('button', { name: 'Ventas' }))

    expect(await screen.findByRole('region', { name: 'Total vendido' })).toHaveTextContent('S/ 160.00')
    expect(screen.getByRole('region', { name: 'Total vendido' })).toHaveTextContent('8 unidades · B2C S/ 100.00 · B2B S/ 60.00')
    expect(screen.getByRole('region', { name: 'Pedidos atendidos' })).toHaveTextContent('1De 2 pedidos pagados')
    expect(screen.getByRole('region', { name: 'Pedidos por entregar' })).toHaveTextContent('1')

    const periodos = within(screen.getByRole('table', { name: 'Ventas por período' })).getAllByRole('row')
    expect(celdas(periodos[3])).toEqual(['Marzo de 2026', '1', '0', '1', '3', 'S/ 0.00', 'S/ 60.00', 'S/ 60.00', 'S/ 160.00'])
    const productos = within(screen.getByRole('table', { name: 'Ventas por producto' })).getAllByRole('row')
    expect(celdas(productos[1])).toEqual(['Aceite de sacha inchi 250 ml', 'Asoc. Nii Joi', '8', 'S/ 160.00'])
    // Sin datos de los compradores
    expect(screen.queryByText(/@/)).not.toBeInTheDocument()
  })

  it('CCPIP-343: el inventario explica cada saldo y cuenta lo vendido que sigue en el punto', async () => {
    acopio({ [`GET ${BASE}/inventario?${RANGO}`]: () => [200, copia(INVENTARIO)] })
    const user = userEvent.setup()
    abrirEn('/acopio/reportes')
    await screen.findByRole('table', { name: 'Entradas por período' })
    await user.click(screen.getByRole('button', { name: 'Inventario' }))

    expect(await screen.findByRole('region', { name: 'Saldo inicial' })).toHaveTextContent('20 und')
    expect(screen.getByRole('region', { name: 'Variación' })).toHaveTextContent('77 und')
    expect(screen.getByRole('region', { name: 'Variación' })).toHaveTextContent('92 entradas · 15 salidas')
    expect(screen.getByRole('region', { name: 'Saldo final' })).toHaveTextContent('97 und')
    expect(screen.getByRole('region', { name: 'Vendido aún en el punto' })).toHaveTextContent('3 und')
    expect(screen.getByRole('region', { name: 'Vendido aún en el punto' })).toHaveTextContent('Existencia física: 100 und')

    const periodos = within(screen.getByRole('table', { name: 'Inventario por período' })).getAllByRole('row')
    expect(celdas(periodos[3])).toEqual(['Marzo de 2026', '77', '30', '10', '97'])
    const productos = within(screen.getByRole('table', { name: 'Inventario por producto' })).getAllByRole('row')
    expect(within(productos[0]).getByText('Venta pendiente')).toBeInTheDocument()
    expect(celdas(productos[1])).toEqual(['Aceite de sacha inchi 250 ml', 'Asoc. Nii Joi', '20', '62', '15', '67', '3'])
    expect(screen.getByText(/las transferencias sí cuentan/)).toBeInTheDocument()
  })

  it('CCPIP-325: descarga en PDF los cuatro reportes con los filtros que se ven', async () => {
    acopio({
      [`GET ${BASE}/entradas?${RANGO}&productor=5`]: () => [200, { ...copia(ENTRADAS), productor: 5 }],
      [`GET ${BASE}/salidas?${RANGO}&productor=5`]: () => [200, { ...copia(SALIDAS), productor: 5 }],
    })
    const user = userEvent.setup()
    abrirEn('/acopio/reportes')
    const pdf = await screen.findByRole('link', { name: 'Descargar PDF' })
    expect(pdf).toHaveAttribute('href', `${BASE}/pdf?${RANGO}&lang=es`)
    expect(pdf).toHaveAttribute('download')
    // CCPIP-377: los mismos cuatro reportes en Excel, con los mismos filtros
    expect(screen.getByRole('link', { name: 'Descargar Excel' })).toHaveAttribute('href', `${BASE}/xlsx?${RANGO}&lang=es`)

    fireEvent.change(screen.getByLabelText('Productor'), { target: { value: '5' } })
    await screen.findByDisplayValue('C.N. Puerto Alegre')
    expect(screen.getByRole('link', { name: 'Descargar PDF' })).toHaveAttribute('href', `${BASE}/pdf?${RANGO}&productor=5&lang=es`)
    expect(screen.getByRole('link', { name: 'Descargar Excel' })).toHaveAttribute('href', `${BASE}/xlsx?${RANGO}&productor=5&lang=es`)
    // El PDF trae los cuatro reportes: cambiar de reporte no cambia la descarga (CCPIP-378, 380 y 382)
    await user.click(screen.getByRole('button', { name: 'Salidas' }))
    await screen.findByRole('table', { name: 'Salidas por período' })
    expect(screen.getByRole('link', { name: 'Descargar PDF' })).toHaveAttribute('href', `${BASE}/pdf?${RANGO}&productor=5&lang=es`)
  })

  it('sale en ingles', async () => {
    await i18n.changeLanguage('en')
    acopio()
    abrirEn('/acopio/reportes')
    expect(await screen.findByRole('region', { name: 'Units received' })).toHaveTextContent('92 units')
    expect(screen.getByRole('table', { name: 'Stock in by period' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Monthly', pressed: true })).toBeInTheDocument()
    expect(screen.getByLabelText('Producer')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Download PDF' }).getAttribute('href')).toContain('lang=en')
    expect(screen.getByRole('link', { name: 'Download Excel' }).getAttribute('href')).toContain('/xlsx?')
  })
})

/** Una respuesta que el servidor demora hasta que la prueba la suelta. */
function retenida(respuesta) {
  let soltar
  const promesa = new Promise((resolve) => { soltar = () => resolve(respuesta) })
  return { ruta: () => promesa, soltar }
}

describe('A-06 Las descargas siguen los filtros que se ven', () => {
  const pdf = () => screen.getByRole('button', { name: 'Descargar PDF' })

  it('con una fecha vacia no consulta y desactiva las descargas hasta completar el rango', async () => {
    const fetchMock = acopio({
      [`GET ${BASE}/entradas?desde=2026-02-01&hasta=2026-03-18&agrupacion=MES`]: () => [200, { ...copia(ENTRADAS), desde: '2026-02-01' }],
    })
    abrirEn('/acopio/reportes')
    await screen.findByRole('link', { name: 'Descargar PDF' })

    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '' } })
    expect(pdf()).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Descargar Excel' })).toBeDisabled()
    expect(pdf()).toHaveAccessibleDescription(/el reporte debe mostrar los filtros elegidos/)
    expect(screen.queryByRole('link', { name: 'Descargar PDF' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([url]) => url.startsWith(`${BASE}/entradas?`))).toHaveLength(0)

    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '2026-02-01' } })
    expect(await screen.findByRole('link', { name: 'Descargar PDF' }))
      .toHaveAttribute('href', `${BASE}/pdf?desde=2026-02-01&hasta=2026-03-18&agrupacion=MES&lang=es`)
  })

  it('mientras se consulta un filtro nuevo no se descarga el anterior', async () => {
    const respuesta = retenida([200, { ...copia(ENTRADAS), productor: 5 }])
    acopio({ [`GET ${BASE}/entradas?${RANGO}&productor=5`]: respuesta.ruta })
    abrirEn('/acopio/reportes')
    await screen.findByRole('link', { name: 'Descargar PDF' })

    fireEvent.change(screen.getByLabelText('Productor'), { target: { value: '5' } })
    expect(await screen.findByRole('button', { name: 'Descargar PDF' })).toBeDisabled()
    expect(pdf()).toHaveAccessibleDescription(/Actualizando el reporte/)

    respuesta.soltar()
    expect(await screen.findByRole('link', { name: 'Descargar PDF' })).toHaveAttribute('href', `${BASE}/pdf?${RANGO}&productor=5&lang=es`)
  })

  it('si la consulta falla, no descarga lo que se veia antes', async () => {
    acopio({
      [`GET ${BASE}/entradas?desde=2025-01-01&hasta=2026-03-18&agrupacion=MES`]: () => [422, { code: 'RANGO_MUY_LARGO', error: 'Consulta como máximo un año a la vez.' }],
    })
    abrirEn('/acopio/reportes')
    await screen.findByRole('link', { name: 'Descargar PDF' })

    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '2025-01-01' } })
    expect(await screen.findByText('No pudimos cargar el reporte')).toBeInTheDocument()
    expect(pdf()).toBeDisabled()
    expect(pdf()).toHaveAccessibleDescription(/vuelve a intentarlo/)
  })

  it('una respuesta que llega tarde no reemplaza a la de la ultima consulta', async () => {
    const lenta = retenida([200, { ...copia(ENTRADAS), productor: 3 }])
    acopio({
      [`GET ${BASE}/entradas?${RANGO}&productor=3`]: lenta.ruta,
      [`GET ${BASE}/entradas?${RANGO}&productor=5`]: () => [200, { ...copia(ENTRADAS), productor: 5 }],
    })
    abrirEn('/acopio/reportes')
    await screen.findByRole('link', { name: 'Descargar PDF' })

    fireEvent.change(screen.getByLabelText('Productor'), { target: { value: '3' } })
    fireEvent.change(screen.getByLabelText('Productor'), { target: { value: '5' } })
    await screen.findByRole('link', { name: 'Descargar PDF' })
    lenta.soltar()
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.getByLabelText('Productor')).toHaveValue('5')
    expect(screen.getByRole('link', { name: 'Descargar PDF' })).toHaveAttribute('href', `${BASE}/pdf?${RANGO}&productor=5&lang=es`)
  })
})
