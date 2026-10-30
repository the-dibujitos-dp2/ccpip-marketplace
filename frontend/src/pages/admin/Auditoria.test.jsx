import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const RUTA = '/api/admin/auditoria'
const PERIODO = 'desde=2026-09-30&hasta=2026-10-29'

const AUDITORIA = {
  desde: '2026-09-30',
  hasta: '2026-10-29',
  pagina: 0,
  tamano: 25,
  total: 4,
  totalPaginas: 1,
  acciones: ['CATALOGO_EDITAR', 'HABILITAR_TIENDA', 'PAGO', 'PRODUCTO_EDITAR'],
  registros: [
    { clave: 'PG-8', fecha: '2026-10-29T15:00:00Z', usuarioId: 1, usuario: 'Ana Quispe', correo: 'ana@correo.pe',
      rol: 'CONSUMIDOR', accion: 'PAGO', entidad: 'PEDIDO', entidadId: 40, detalle: 'CCPIP-000040', resultado: 'RECHAZADO' },
    { clave: 'PA-3', fecha: '2026-10-28T15:00:00Z', usuarioId: 2, usuario: 'Comunidad Shipibo-Konibo', correo: 'productor@ccpip.test',
      rol: 'PRODUCTOR', accion: 'PRODUCTO_EDITAR', entidad: 'PRODUCTO', entidadId: 17, detalle: 'Miel de abeja nativa', resultado: 'EXITOSA' },
    { clave: 'PD-1', fecha: '2026-10-27T15:00:00Z', usuarioId: 6, usuario: 'Sub-Administrador de Prueba', correo: 'sub_admin@ccpip.test',
      rol: 'SUB_ADMIN', accion: 'HABILITAR_TIENDA', entidad: 'PRODUCTOR', entidadId: 1, detalle: 'Bordadoras de San Francisco', resultado: 'EXITOSA' },
    { clave: 'CM-1', fecha: '2026-10-26T15:00:00Z', usuarioId: 7, usuario: 'Super Administrador de Prueba', correo: 'sudo@ccpip.test',
      rol: 'SUPER_ADMIN', accion: 'CATALOGO_EDITAR', entidad: 'LINEA_MAESTRA', entidadId: 4, detalle: 'Superfrutos', resultado: 'EXITOSA' },
  ],
}

const comoSuper = (rutas = {}) =>
  servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_SUPER], [`GET ${RUTA}`]: () => [200, copia(AUDITORIA)], ...rutas })

const filas = async (nombre = 'Auditoría de operaciones sensibles') =>
  within(await screen.findByRole('table', { name: nombre })).getAllByRole('row').slice(1)

afterEach(async () => {
  await i18n.changeLanguage('es')
})

describe('Auditoría de operaciones sensibles (CCPIP-97)', () => {
  it('lista usuario, rol, acción, entidad, fecha y resultado, sin poder editar', async () => {
    comoSuper()
    abrirEn('/admin/auditoria')
    expect(await screen.findByRole('heading', { level: 1, name: 'Auditoría de operaciones sensibles' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Auditoría' })).toHaveAttribute('aria-current', 'page')

    const [pago, producto, decision, catalogo] = await filas()
    expect(within(pago).getByText('Ana Quispe')).toBeInTheDocument()
    expect(within(pago).getByText('ana@correo.pe')).toBeInTheDocument()
    expect(within(pago).getByText('Consumidor final')).toBeInTheDocument()
    expect(within(pago).getByText('Intentó un pago')).toBeInTheDocument()
    expect(within(pago).getByText('Pedido')).toBeInTheDocument()
    expect(within(pago).getByText('CCPIP-000040')).toBeInTheDocument()
    expect(within(pago).getByText('Rechazado')).toBeInTheDocument()
    expect(within(producto).getByText('Editó producto')).toBeInTheDocument()
    expect(within(producto).getByText('Exitosa')).toBeInTheDocument()
    // Las acciones de gobernanza usan los mismos nombres que Mi historial
    expect(within(decision).getByText('Habilitó tienda')).toBeInTheDocument()
    expect(within(decision).getByText('Sub administrador')).toBeInTheDocument()
    expect(within(catalogo).getByText('Línea maestra')).toBeInTheDocument()
    expect(within(catalogo).getByText('Editó el catálogo maestro')).toBeInTheDocument()

    expect(screen.queryByRole('button', { name: /editar|eliminar|borrar/i })).not.toBeInTheDocument()
    expect(screen.getByText('Registro inalterable. Las correcciones se registran como nuevas operaciones.')).toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toHaveValue('2026-09-30')
    expect(screen.getByLabelText('Hasta')).toHaveValue('2026-10-29')
  })

  it('filtra por rol, acción y resultado al elegir, y por usuario al buscar', async () => {
    const soloPago = { ...copia(AUDITORIA), total: 1, registros: [copia(AUDITORIA.registros[0])] }
    const fetchMock = comoSuper({
      [`GET ${RUTA}?${PERIODO}&rol=CONSUMIDOR`]: [200, soloPago],
      [`GET ${RUTA}?${PERIODO}&rol=CONSUMIDOR&accion=PAGO`]: [200, soloPago],
      [`GET ${RUTA}?${PERIODO}&rol=CONSUMIDOR&accion=PAGO&resultado=RECHAZADO`]: [200, soloPago],
      [`GET ${RUTA}?${PERIODO}&usuario=ana&rol=CONSUMIDOR&accion=PAGO&resultado=RECHAZADO`]: [200, soloPago],
    })
    const user = userEvent.setup()
    abrirEn('/admin/auditoria')
    await filas()

    await user.selectOptions(screen.getByLabelText('Rol'), 'Consumidor final')
    expect(await filas()).toHaveLength(1)
    expect(llamadas(fetchMock, `GET ${RUTA}?${PERIODO}&rol=CONSUMIDOR`)).toHaveLength(1)
    // Las acciones que se pueden elegir son las del periodo
    await user.selectOptions(screen.getByLabelText('Acción'), 'Intentó un pago')
    await user.selectOptions(screen.getByLabelText('Resultado'), 'Rechazado')
    expect(llamadas(fetchMock, `GET ${RUTA}?${PERIODO}&rol=CONSUMIDOR&accion=PAGO&resultado=RECHAZADO`)).toHaveLength(1)

    // Escribir no consulta en cada tecla; Buscar si
    await user.type(screen.getByLabelText('Usuario'), 'ana')
    expect(llamadas(fetchMock, `GET ${RUTA}?${PERIODO}&usuario=ana&rol=CONSUMIDOR&accion=PAGO&resultado=RECHAZADO`)).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Buscar' }))
    expect(llamadas(fetchMock, `GET ${RUTA}?${PERIODO}&usuario=ana&rol=CONSUMIDOR&accion=PAGO&resultado=RECHAZADO`)).toHaveLength(1)
  })

  it('pagina en el servidor', async () => {
    const pagina2 = { ...copia(AUDITORIA), pagina: 1, total: 30, totalPaginas: 2, registros: [copia(AUDITORIA.registros[3])] }
    const fetchMock = comoSuper({
      [`GET ${RUTA}`]: [200, { ...copia(AUDITORIA), total: 30, totalPaginas: 2 }],
      [`GET ${RUTA}?${PERIODO}&pagina=1`]: [200, pagina2],
    })
    const user = userEvent.setup()
    abrirEn('/admin/auditoria')
    await filas()
    await user.click(screen.getByRole('button', { name: 'Página 2' }))
    expect(await filas()).toHaveLength(1)
    expect(llamadas(fetchMock, `GET ${RUTA}?${PERIODO}&pagina=1`)).toHaveLength(1)
  })

  it('sin registros lo dice', async () => {
    comoSuper({ [`GET ${RUTA}`]: [200, { ...copia(AUDITORIA), total: 0, registros: [] }] })
    abrirEn('/admin/auditoria')
    expect(await screen.findByText('No hay operaciones que coincidan con los filtros en este periodo.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('muestra el error del servidor', async () => {
    comoSuper({ [`GET ${RUTA}`]: [422, { code: 'RANGO_MUY_LARGO', error: 'Consulta como máximo un año a la vez.' }] })
    abrirEn('/admin/auditoria')
    expect(await screen.findByText('Consulta como máximo un año a la vez.')).toBeInTheDocument()
  })

  it('el sub administrador no la ve', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_SUB],
      'GET /api/admin/autorizaciones': () => [200, copia(DATOS.autorizacionesAdmin)],
      [`GET ${RUTA}`]: [200, copia(AUDITORIA)],
    })
    abrirEn('/admin/auditoria')
    await screen.findByRole('heading', { level: 1 })
    expect(window.location.pathname).not.toBe('/admin/auditoria')
    expect(screen.queryByRole('link', { name: 'Auditoría' })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${RUTA}`)).toHaveLength(0)
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    comoSuper()
    abrirEn('/admin/auditoria')
    expect(await screen.findByRole('heading', { level: 1, name: 'Audit of sensitive operations' })).toBeInTheDocument()
    const [pago] = await filas('Audit of sensitive operations')
    expect(within(pago).getByText('Attempted a payment')).toBeInTheDocument()
    expect(within(pago).getByText('Declined')).toBeInTheDocument()
    expect(within(pago).getByText('Order')).toBeInTheDocument()
  })
})
