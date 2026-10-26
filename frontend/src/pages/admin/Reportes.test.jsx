import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const VENTAS = {
  metrica: 'VENTAS',
  desde: '2026-01-01',
  hasta: '2026-03-18',
  agrupacion: 'MES',
  totales: { b2c: 1300, b2b: 900, total: 2200 },
  periodos: [
    { inicio: '2026-01-01', fin: '2026-01-31', b2c: 500, b2b: 0, total: 500, acumulado: 500 },
    { inicio: '2026-02-01', fin: '2026-02-28', b2c: 0, b2b: 0, total: 0, acumulado: 500 },
    { inicio: '2026-03-01', fin: '2026-03-18', b2c: 800, b2b: 900, total: 1700, acumulado: 2200 },
  ],
}

const SEMANAS = {
  ...VENTAS,
  desde: '2026-03-04',
  hasta: '2026-03-18',
  agrupacion: 'SEMANA',
  periodos: [
    { inicio: '2026-03-04', fin: '2026-03-08', b2c: 0, b2b: 0, total: 0, acumulado: 0 },
    { inicio: '2026-03-09', fin: '2026-03-15', b2c: 800, b2b: 900, total: 1700, acumulado: 1700 },
    { inicio: '2026-03-16', fin: '2026-03-18', b2c: 0, b2b: 0, total: 0, acumulado: 1700 },
  ],
}

function comoSuper(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUPER],
    'GET /api/admin/reportes/ventas': () => [200, copia(VENTAS)],
    ...rutas,
  })
}

describe('CCPIP-345 Ventas acumuladas de la plataforma (SA-05)', () => {
  it('se llega desde el menu y muestra el total con su desglose B2C y B2B', async () => {
    comoSuper({ 'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)] })
    const user = userEvent.setup()
    abrirEn('/admin')
    await user.click(await screen.findByRole('link', { name: 'Reportes' }))
    expect(window.location.pathname).toBe('/admin/reportes')

    expect(await screen.findByRole('region', { name: 'Ventas acumuladas' })).toHaveTextContent('S/ 2,200.00')
    expect(screen.getByRole('region', { name: 'B2C (minorista)' })).toHaveTextContent('S/ 1,300.00')
    expect(screen.getByRole('region', { name: 'B2B (mayorista)' })).toHaveTextContent('S/ 900.00')
    expect(screen.getByLabelText('Agrupar por')).toHaveValue('MES')

    const tabla = screen.getByRole('table', { name: 'Ventas por periodo' })
    const filas = within(tabla).getAllByRole('row')
    expect(filas).toHaveLength(4)
    expect(within(filas[1]).getByText('Enero de 2026')).toBeInTheDocument()
    // Un mes sin ventas aparece en cero y conserva el acumulado
    expect(within(filas[2]).getAllByText('S/ 500.00')).toHaveLength(1)
    expect(within(filas[3]).getByText('S/ 1,700.00')).toBeInTheDocument()
    expect(within(filas[3]).getByText('S/ 2,200.00')).toBeInTheDocument()
  })

  it('agrupar por semana consulta el rango y nombra cada semana', async () => {
    const fetchMock = comoSuper({
      'GET /api/admin/reportes/ventas?desde=2026-01-01&hasta=2026-03-18&agrupacion=SEMANA': () => [200, copia(SEMANAS)],
    })
    abrirEn('/admin/reportes')
    await screen.findByRole('table', { name: 'Ventas por periodo' })
    fireEvent.change(screen.getByLabelText('Agrupar por'), { target: { value: 'SEMANA' } })
    expect(await screen.findByText('9 mar 2026 al 15 mar 2026')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/admin/reportes/ventas?desde=2026-01-01&hasta=2026-03-18&agrupacion=SEMANA')).toHaveLength(1)
  })

  it('un rango invalido muestra el error', async () => {
    comoSuper({ 'GET /api/admin/reportes/ventas': () => [422, { code: 'RANGO_MUY_LARGO', error: 'Consulta como máximo un año a la vez.' }] })
    abrirEn('/admin/reportes')
    expect(await screen.findByText('No pudimos cargar el reporte')).toBeInTheDocument()
  })

  it('el sub administrador no ve los reportes', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_SUB], 'GET /api/admin/autorizaciones': () => [200, copia(DATOS.autorizaciones ?? { contadores: {}, solicitudes: [] })] })
    abrirEn('/admin/reportes')
    await screen.findByRole('navigation', { name: 'Sub administrador' })
    expect(window.location.pathname).toBe('/admin/autorizaciones')
    expect(screen.queryByRole('link', { name: 'Reportes' })).not.toBeInTheDocument()
  })

  it('CCPIP-346: la pestana Comisiones consulta las comisiones del mismo rango', async () => {
    const fetchMock = comoSuper({
      'GET /api/admin/reportes/comisiones?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES': () => [200, {
        ...copia(VENTAS), metrica: 'COMISIONES', totales: { b2c: 130, b2b: 90, total: 220 },
      }],
    })
    const user = userEvent.setup()
    abrirEn('/admin/reportes')
    await screen.findByRole('table', { name: 'Ventas por periodo' })
    await user.click(screen.getByRole('tab', { name: 'Comisiones' }))
    expect(await screen.findByRole('region', { name: 'Comisiones acumuladas' })).toHaveTextContent('S/ 220.00')
    expect(screen.getByRole('tab', { name: 'Comisiones', selected: true })).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Comisiones por periodo' })).toBeInTheDocument()
    expect(llamadas(fetchMock, 'GET /api/admin/reportes/comisiones?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES')).toHaveLength(1)
  })

  it('CCPIP-93: la pestana Ingresos muestra lo cobrado del mismo rango', async () => {
    comoSuper({
      'GET /api/admin/reportes/ingresos?desde=2026-01-01&hasta=2026-03-18&agrupacion=MES': () => [200, {
        ...copia(VENTAS), metrica: 'INGRESOS', totales: { b2c: 1250, b2b: 900, total: 2150 },
      }],
    })
    const user = userEvent.setup()
    abrirEn('/admin/reportes')
    await screen.findByRole('table', { name: 'Ventas por periodo' })
    await user.click(screen.getByRole('tab', { name: 'Ingresos' }))
    expect(await screen.findByRole('region', { name: 'Ingresos acumulados' })).toHaveTextContent('S/ 2,150.00')
    expect(screen.getByRole('table', { name: 'Ingresos por periodo' })).toBeInTheDocument()
    expect(screen.getByText(/pagos aprobados/)).toBeInTheDocument()
  })
})

const RANGO = 'desde=2026-01-01&hasta=2026-03-18'

/** Abre los reportes y pasa a la pestana `nombre`, que consulta el mismo rango que las ventas. */
async function abrirPestana(nombre) {
  const user = userEvent.setup()
  abrirEn('/admin/reportes')
  await screen.findByRole('table', { name: 'Ventas por periodo' })
  await user.click(screen.getByRole('tab', { name: nombre }))
  return user
}

const PRODUCTORES = {
  desde: '2026-01-01',
  hasta: '2026-03-18',
  totales: { b2c: 1300, b2b: 900, total: 2200 },
  productoresConVentas: 2,
  limite: 20,
  productores: [
    { productorId: 7, productor: 'Tienda Asháninka', pueblo: 'Asháninka', pedidos: 3, unidades: 40, b2c: 400, b2b: 900, total: 1300, participacion: 59.1 },
    { productorId: 9, productor: 'Café Yanesha', pueblo: null, pedidos: 2, unidades: 12.5, b2c: 900, b2b: 0, total: 900, participacion: 40.9 },
  ],
}

describe('CCPIP-122 Productores que mas venden (SA-10)', () => {
  it('la pestana Productores consulta el mismo rango y los lista de mayor a menor venta', async () => {
    const fetchMock = comoSuper({ [`GET /api/admin/reportes/productores?${RANGO}`]: () => [200, copia(PRODUCTORES)] })
    await abrirPestana('Productores')

    expect(await screen.findByRole('region', { name: 'Ventas de la plataforma' })).toHaveTextContent('S/ 2,200.00')
    expect(screen.getByRole('region', { name: 'Productores con ventas' })).toHaveTextContent('2')
    expect(screen.getByRole('region', { name: 'Mayor participación' })).toHaveTextContent('59.1%')
    expect(screen.getByRole('region', { name: 'Mayor participación' })).toHaveTextContent('Tienda Asháninka')
    // Un reporte por rango no se agrupa
    expect(screen.queryByLabelText('Agrupar por')).not.toBeInTheDocument()

    const filas = within(screen.getByRole('table', { name: 'Productores que más venden' })).getAllByRole('row')
    expect(filas).toHaveLength(3)
    expect(within(filas[1]).getByText('Tienda Asháninka')).toBeInTheDocument()
    expect(within(filas[1]).getByText('S/ 1,300.00')).toBeInTheDocument()
    expect(within(filas[2]).getByText('12.5')).toBeInTheDocument()
    expect(within(filas[2]).getByText('40.9%')).toBeInTheDocument()
    expect(llamadas(fetchMock, `GET /api/admin/reportes/productores?${RANGO}`)).toHaveLength(1)
  })

  it('cambiar el rango vuelve a consultar los productores sin agrupacion', async () => {
    const fetchMock = comoSuper({
      [`GET /api/admin/reportes/productores?${RANGO}`]: () => [200, copia(PRODUCTORES)],
      'GET /api/admin/reportes/productores?desde=2026-02-01&hasta=2026-03-18': () => [200, {
        ...copia(PRODUCTORES), desde: '2026-02-01', productoresConVentas: 0, productores: [],
      }],
    })
    await abrirPestana('Productores')
    await screen.findByRole('table', { name: 'Productores que más venden' })
    fireEvent.change(screen.getByLabelText('Desde'), { target: { value: '2026-02-01' } })
    expect(await screen.findByText('Ningún productor vendió en este período')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Mayor participación' })).toHaveTextContent('Nadie vendió en este período')
    expect(llamadas(fetchMock, 'GET /api/admin/reportes/productores?desde=2026-02-01&hasta=2026-03-18')).toHaveLength(1)
  })
})

const PUNTOS = {
  desde: '2026-01-01',
  hasta: '2026-03-18',
  totales: { b2c: 1300, b2b: 900, total: 2200 },
  pedidos: 5,
  denuncias: { total: 3, abiertas: 1, resueltas: 1, desestimadas: 1 },
  puntos: [
    { puntoId: 1, punto: 'Satipo', departamento: 'Junín', pedidos: 5, b2c: 1300, b2b: 900, total: 2200, participacion: 100, denuncias: 2, denunciasAbiertas: 1 },
    { puntoId: 2, punto: 'Oxapampa', departamento: 'Pasco', pedidos: 0, b2c: 0, b2b: 0, total: 0, participacion: 0, denuncias: 0, denunciasAbiertas: 0 },
  ],
}

describe('CCPIP-355 Ventas por punto de acopio (SA-10)', () => {
  it('lista cada punto activo con sus ventas, tambien los que no vendieron', async () => {
    const fetchMock = comoSuper({ [`GET /api/admin/reportes/puntos?${RANGO}`]: () => [200, copia(PUNTOS)] })
    await abrirPestana('Puntos de acopio')

    expect(await screen.findByRole('region', { name: 'Pedidos' })).toHaveTextContent('5')
    expect(screen.getByRole('region', { name: 'Puntos con ventas' })).toHaveTextContent('De 2 puntos activos')
    const filas = within(screen.getByRole('table', { name: 'Ventas por punto de acopio' })).getAllByRole('row')
    expect(filas).toHaveLength(3)
    expect(within(filas[1]).getByText('Junín')).toBeInTheDocument()
    expect(within(filas[1]).getByText('100%')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Oxapampa')).toBeInTheDocument()
    expect(within(filas[2]).getAllByText('S/ 0.00')).toHaveLength(3)
    expect(llamadas(fetchMock, `GET /api/admin/reportes/puntos?${RANGO}`)).toHaveLength(1)
  })
})

describe('CCPIP-356 Denuncias por punto de acopio (SA-10)', () => {
  it('muestra las denuncias de la plataforma y las de cada punto donde esta la oferta', async () => {
    comoSuper({ [`GET /api/admin/reportes/puntos?${RANGO}`]: () => [200, copia(PUNTOS)] })
    await abrirPestana('Puntos de acopio')

    const denuncias = await screen.findByRole('region', { name: 'Denuncias recibidas' })
    expect(denuncias).toHaveTextContent('3')
    expect(denuncias).toHaveTextContent('1 abiertas · 1 resueltas · 1 desestimadas')
    const tabla = screen.getByRole('table', { name: 'Ventas por punto de acopio' })
    expect(within(tabla).getByRole('columnheader', { name: 'Abiertas' })).toBeInTheDocument()
    const satipo = within(tabla).getAllByRole('row')[1]
    expect(within(satipo).getByText('2')).toBeInTheDocument()
    expect(screen.getByText(/una denuncia suma en cada punto/)).toBeInTheDocument()
  })
})

const CALIFICACIONES = {
  desde: '2026-01-01',
  hasta: '2026-03-18',
  anteriorDesde: '2025-10-16',
  anteriorHasta: '2025-12-31',
  minimo: 3,
  linea: null,
  resumen: {
    promedio: 4.2,
    resenas: 10,
    distribucion: [
      { estrellas: 5, cantidad: 5 }, { estrellas: 4, cantidad: 3 }, { estrellas: 3, cantidad: 1 },
      { estrellas: 2, cantidad: 0 }, { estrellas: 1, cantidad: 1 },
    ],
  },
  productos: [
    { productoId: 11, producto: 'Café de altura', productor: 'Café Yanesha', promedio: 4.8, resenas: 6, denuncias: 0, tendencia: 'SUBE' },
    { productoId: 12, producto: 'Cacao en grano', productor: 'Tienda Asháninka', promedio: 3.4, resenas: 3, denuncias: 2, tendencia: 'BAJA' },
    { productoId: 13, producto: 'Miel', productor: 'Tienda Asháninka', promedio: null, resenas: 1, denuncias: 0, tendencia: null },
  ],
  productores: [
    { productorId: 9, productor: 'Café Yanesha', pueblo: 'Yanesha', promedio: 4.8, resenas: 6, productos: 1, denuncias: 0, tendencia: 'ESTABLE' },
    { productorId: 7, productor: 'Tienda Asháninka', pueblo: null, promedio: 3.3, resenas: 4, productos: 2, denuncias: 3, tendencia: null },
  ],
  lineas: [{ codigo: 'CAFE', nombre: 'Café' }, { codigo: 'MIEL', nombre: 'Miel y derivados' }],
}

describe('CCPIP-123 Calificaciones de los productos (SA-05)', () => {
  it('muestra el promedio, la distribucion y cada producto con su tendencia', async () => {
    comoSuper({ [`GET /api/admin/reportes/calificaciones?${RANGO}`]: () => [200, copia(CALIFICACIONES)] })
    await abrirPestana('Calificaciones')

    expect(await screen.findByRole('region', { name: 'Calificación promedio' })).toHaveTextContent('4.2')
    expect(screen.getByRole('region', { name: 'Productos en caída' })).toHaveTextContent('1')
    const distribucion = within(screen.getByRole('table', { name: 'Distribución de calificaciones' })).getAllByRole('row')
    expect(distribucion).toHaveLength(6)
    expect(within(distribucion[1]).getByText('5 estrellas')).toBeInTheDocument()
    expect(within(distribucion[5]).getByText('1 estrella')).toBeInTheDocument()

    const filas = within(screen.getByRole('table', { name: 'Calificaciones por producto' })).getAllByRole('row')
    expect(within(filas[1]).getByText('Sube')).toBeInTheDocument()
    expect(within(filas[2]).getByText('En caída')).toBeInTheDocument()
    // Bajo el minimo no hay promedio ni tendencia
    expect(within(filas[3]).getAllByText('—')).toHaveLength(2)
    expect(screen.getByText(/desde 3 calificaciones/)).toBeInTheDocument()
  })

  it('sin el minimo de calificaciones no muestra un promedio', async () => {
    comoSuper({
      [`GET /api/admin/reportes/calificaciones?${RANGO}`]: () => [200, {
        ...copia(CALIFICACIONES), resumen: { ...CALIFICACIONES.resumen, promedio: null, resenas: 2 },
      }],
    })
    await abrirPestana('Calificaciones')
    expect(await screen.findByRole('region', { name: 'Calificación promedio' })).toHaveTextContent('Se necesitan al menos 3 calificaciones')
  })

  it('filtrar por linea consulta el mismo rango con la linea', async () => {
    const fetchMock = comoSuper({
      [`GET /api/admin/reportes/calificaciones?${RANGO}`]: () => [200, copia(CALIFICACIONES)],
      [`GET /api/admin/reportes/calificaciones?${RANGO}&linea=CAFE`]: () => [200, {
        ...copia(CALIFICACIONES), linea: 'CAFE', productos: CALIFICACIONES.productos.slice(0, 1),
      }],
    })
    await abrirPestana('Calificaciones')
    await screen.findByRole('table', { name: 'Calificaciones por producto' })
    expect(screen.queryByLabelText('Agrupar por')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Línea'), { target: { value: 'CAFE' } })
    await waitFor(() => expect(screen.queryByText('Cacao en grano')).not.toBeInTheDocument())
    expect(screen.getByLabelText('Línea')).toHaveValue('CAFE')
    expect(llamadas(fetchMock, `GET /api/admin/reportes/calificaciones?${RANGO}&linea=CAFE`)).toHaveLength(1)
  })
})
