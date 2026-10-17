import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const ALMACEN = { id: 11, nombre: 'Almacén Comunidad Shipibo', tipo: 'ALMACEN_PRODUCTOR', departamento: 'Ucayali' }
const LIMA = { id: 1, nombre: 'Centro Nacional Lima', tipo: 'PUNTO_ACOPIO', departamento: 'Lima' }
const UCAYALI = { id: 4, nombre: 'Centro Regional Ucayali', tipo: 'PUNTO_ACOPIO', departamento: 'Ucayali' }

// Armado segun InventarioProductorModelos.Inventario del backend (CCPIP-136, CCPIP-162)
const INVENTARIO = {
  resumen: {
    enAlmacen: 64,
    productosEnAlmacen: 2,
    enPuntosAcopio: 225,
    porPunto: [
      { ubicacionId: 1, ubicacion: 'Centro Nacional Lima', tipo: 'PUNTO_ACOPIO', cantidad: 9 },
      { ubicacionId: 4, ubicacion: 'Centro Regional Ucayali', tipo: 'PUNTO_ACOPIO', cantidad: 216 },
    ],
    lotesPorVencer: 1,
    lotesVencidos: 1,
  },
  diasAviso: 15,
  ubicaciones: [ALMACEN, LIMA, UCAYALI],
  productos: [
    {
      id: 9,
      nombre: 'Aceite de sacha inchi 250 ml',
      miniatura: '/api/imagenes/a?tam=min',
      total: 256,
      porUbicacion: [
        { ubicacionId: 11, ubicacion: 'Almacén Comunidad Shipibo', tipo: 'ALMACEN_PRODUCTOR', cantidad: 40 },
        { ubicacionId: 4, ubicacion: 'Centro Regional Ucayali', tipo: 'PUNTO_ACOPIO', cantidad: 216 },
      ],
      lotes: [
        { id: 31, codigo: 'L-0231', ubicacionId: 11, ubicacion: 'Almacén Comunidad Shipibo', tipoUbicacion: 'ALMACEN_PRODUCTOR', cantidad: 40, fechaProduccion: '2026-09-10', fechaVencimiento: '2027-03-15', estado: 'DISPONIBLE' },
        { id: 32, codigo: 'L-0232', ubicacionId: 4, ubicacion: 'Centro Regional Ucayali', tipoUbicacion: 'PUNTO_ACOPIO', cantidad: 216, fechaProduccion: '2026-09-10', fechaVencimiento: '2027-03-15', estado: 'DISPONIBLE' },
        { id: 33, codigo: 'L-0199', ubicacionId: 1, ubicacion: 'Centro Nacional Lima', tipoUbicacion: 'PUNTO_ACOPIO', cantidad: 3, fechaProduccion: '2026-03-01', fechaVencimiento: '2026-10-01', estado: 'VENCIDO' },
      ],
    },
    {
      id: 10,
      nombre: 'Harina de aguaje 500 g',
      total: 33,
      porUbicacion: [
        { ubicacionId: 11, ubicacion: 'Almacén Comunidad Shipibo', tipo: 'ALMACEN_PRODUCTOR', cantidad: 24 },
        { ubicacionId: 1, ubicacion: 'Centro Nacional Lima', tipo: 'PUNTO_ACOPIO', cantidad: 9 },
      ],
      lotes: [
        { id: 41, codigo: 'HA-01', ubicacionId: 11, ubicacion: 'Almacén Comunidad Shipibo', tipoUbicacion: 'ALMACEN_PRODUCTOR', cantidad: 24, fechaProduccion: '2026-10-02', fechaVencimiento: '2026-10-25', estado: 'POR_VENCER' },
        { id: 42, codigo: 'HA-02', ubicacionId: 1, ubicacion: 'Centro Nacional Lima', tipoUbicacion: 'PUNTO_ACOPIO', cantidad: 9, estado: 'DISPONIBLE' },
      ],
    },
  ],
}

const VACIO = {
  resumen: { enAlmacen: 0, productosEnAlmacen: 0, enPuntosAcopio: 0, porPunto: [], lotesPorVencer: 0, lotesVencidos: 0 },
  diasAviso: 15,
  ubicaciones: [ALMACEN],
  productos: [],
}

function productor(rutas = {}) {
  return servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/inventario': () => [200, copia(INVENTARIO)],
    ...rutas,
  })
}

describe('CCPIP-136 y CCPIP-162 Mi almacén del productor (PW-16)', () => {
  afterEach(async () => {
    await i18n.changeLanguage('es')
  })

  it('se llega desde el menu, entre Productos y Lotes, y muestra los indicadores', async () => {
    productor({ 'GET /api/productor/panel': () => [200, copia(DATOS.panelProductor)] })
    const user = userEvent.setup()
    abrirEn('/productor')
    const menu = await screen.findByRole('link', { name: 'Almacén' })
    const enlaces = within(menu.closest('nav')).getAllByRole('link').map((a) => a.textContent)
    expect(enlaces.indexOf('Almacén')).toBe(enlaces.indexOf('Productos') + 1)
    expect(enlaces.indexOf('Lotes')).toBe(enlaces.indexOf('Almacén') + 1)
    expect(enlaces).toContain('Pedidos')

    await user.click(menu)
    expect(window.location.pathname).toBe('/productor/almacen')
    expect(await screen.findByRole('heading', { name: 'Mi almacén' })).toBeInTheDocument()

    const almacen = await screen.findByRole('region', { name: 'Unidades en el almacén' })
    expect(within(almacen).getByText('64 und')).toBeInTheDocument()
    expect(within(almacen).getByText('2 productos')).toBeInTheDocument()
    const porVencer = screen.getByRole('region', { name: 'Por vencer' })
    expect(within(porVencer).getByText('1 lote')).toBeInTheDocument()
    expect(within(porVencer).getByText('En los próximos 15 días')).toBeInTheDocument()
    const acopios = screen.getByRole('region', { name: 'En puntos de acopio' })
    expect(within(acopios).getByText('225 und')).toBeInTheDocument()
    expect(within(acopios).getByText('Lima 9 · Ucayali 216')).toBeInTheDocument()
    // Las transferencias llegan con PW-17: ni su indicador ni botones muertos
    expect(screen.queryByText(/Transferencias en curso/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Transferir|Nueva transferencia/ })).not.toBeInTheDocument()
  })

  it('por defecto muestra los lotes de su almacen con cosecha, vencimiento y estado', async () => {
    productor()
    abrirEn('/productor/almacen')
    expect(await screen.findByRole('tab', { name: 'Mi almacén (2)', selected: true })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Centro Nacional Lima (2)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Centro Regional Ucayali (1)' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Inventario del almacén · Ucayali' })).toBeInTheDocument()

    const tabla = screen.getByRole('table', { name: 'Inventario del almacén · Ucayali' })
    expect(within(tabla).getByRole('columnheader', { name: 'En almacén' })).toBeInTheDocument()
    const filas = within(tabla).getAllByRole('row').slice(1)
    expect(filas).toHaveLength(2)
    expect(within(filas[0]).getByText('Aceite de sacha inchi 250 ml')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Lote L-0231')).toBeInTheDocument()
    expect(within(filas[0]).getByText(/^Cosecha .*2026 · vence 15\/03\/2027$/)).toBeInTheDocument()
    expect(within(filas[0]).getByText('40 und')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Disponible')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Harina de aguaje 500 g')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Por vencer')).toBeInTheDocument()
    // Lo de los puntos no se mezcla con el almacen
    expect(within(tabla).queryByText('Lote L-0232')).not.toBeInTheDocument()
  })

  it('cada punto de acopio muestra sus lotes, incluido el vencido, y avisa de los vencidos', async () => {
    productor()
    const user = userEvent.setup()
    abrirEn('/productor/almacen')
    expect(await screen.findByRole('alert')).toHaveTextContent('Tienes 1 lote vencido')

    await user.click(screen.getByRole('tab', { name: 'Centro Nacional Lima (2)' }))
    const tabla = screen.getByRole('table', { name: 'Inventario en Centro Nacional Lima' })
    expect(within(tabla).getByRole('columnheader', { name: 'En el punto' })).toBeInTheDocument()
    const filas = within(tabla).getAllByRole('row').slice(1)
    expect(filas).toHaveLength(2)
    expect(within(filas[0]).getByText('Lote L-0199')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Vencido')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Lote HA-02')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Sin vencimiento')).toBeInTheDocument()
    expect(within(filas[1]).getByText('9 und')).toBeInTheDocument()
  })

  it('por producto da el total disponible y donde esta', async () => {
    productor()
    const user = userEvent.setup()
    abrirEn('/productor/almacen')
    await user.click(await screen.findByRole('tab', { name: 'Por producto' }))
    const tabla = screen.getByRole('table', { name: 'Inventario por producto' })
    const filas = within(tabla).getAllByRole('row').slice(1)
    expect(within(filas[0]).getByText('Aceite de sacha inchi 250 ml')).toBeInTheDocument()
    expect(within(filas[0]).getByText('Mi almacén 40 · Ucayali 216')).toBeInTheDocument()
    expect(within(filas[0]).getByText('3')).toBeInTheDocument()
    expect(within(filas[0]).getByText('256 und')).toBeInTheDocument()
    expect(within(filas[1]).getByText('Mi almacén 24 · Lima 9')).toBeInTheDocument()
    expect(within(filas[1]).getByText('33 und')).toBeInTheDocument()
  })

  it('el aviso de inventario unico se puede cerrar', async () => {
    productor()
    const user = userEvent.setup()
    abrirEn('/productor/almacen')
    expect(await screen.findByText('Un solo inventario virtual')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cerrar aviso' }))
    expect(screen.queryByText('Un solo inventario virtual')).not.toBeInTheDocument()
  })

  it('sin existencias lo dice y lleva a registrar un lote', async () => {
    productor({ 'GET /api/productor/inventario': () => [200, copia(VACIO)] })
    const user = userEvent.setup()
    abrirEn('/productor/almacen')
    expect(await screen.findByText('Tu almacén está vacío')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Registrar lote' })).toHaveAttribute('href', '/productor/lotes')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'En puntos de acopio' })).getByText('Aún nada en puntos de acopio')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Por producto' }))
    expect(screen.getByText('Aún no tienes existencias')).toBeInTheDocument()
  })

  it('si falla la consulta lo avisa', async () => {
    productor({ 'GET /api/productor/inventario': () => [500, { code: 'ERROR' }] })
    abrirEn('/productor/almacen')
    expect(await screen.findByText('No pudimos cargar tu inventario')).toBeInTheDocument()
  })

  it('sale en ingles', async () => {
    await i18n.changeLanguage('en')
    productor()
    abrirEn('/productor/almacen')
    expect(await screen.findByRole('heading', { name: 'My warehouse' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Warehouse' })).toBeInTheDocument()
    expect(await screen.findByRole('tab', { name: 'My warehouse (2)' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Units in the warehouse' })).toBeInTheDocument()
    expect(screen.getByText('Expiring soon', { selector: '.badge-estado' })).toBeInTheDocument()
    expect(screen.getByText(/^Harvest .*2026 · expires 15\/03\/2027$/)).toBeInTheDocument()
    expect(screen.getByText('One virtual inventory')).toBeInTheDocument()
    expect(screen.queryByText('Mi almacén')).not.toBeInTheDocument()
  })
})
