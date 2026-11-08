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
    // La fecha y la hora de Lima van en columnas separadas (mejoras de laboratorio)
    expect(within(pago).getByText('29 oct 2026')).toBeInTheDocument()
    expect(within(pago).getByText('10:00')).toBeInTheDocument()
    expect(within(producto).getByText('Editó producto')).toBeInTheDocument()
    expect(within(producto).getByText('Exitosa')).toBeInTheDocument()
    // Las acciones de gobernanza usan los mismos nombres que Mi historial
    expect(within(decision).getByText('Habilitó tienda')).toBeInTheDocument()
    expect(within(decision).getByText('Sub administrador')).toBeInTheDocument()
    expect(within(catalogo).getByText('Línea maestra')).toBeInTheDocument()
    expect(within(catalogo).getByText('Editó el catálogo maestro')).toBeInTheDocument()

    expect(screen.queryByRole('button', { name: /editar|eliminar|borrar/i })).not.toBeInTheDocument()
    expect(screen.getByText('Registro inalterable. Las correcciones se registran como nuevas operaciones.')).toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toHaveValue('30/09/2026')
    expect(screen.getByLabelText('Hasta')).toHaveValue('29/10/2026')
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

  it('CCPIP-504: muestra la operación del punto de acopio y del distribuidor y filtra por movimiento', async () => {
    const operacion = {
      ...copia(AUDITORIA), total: 3, acciones: ['ENTREGA_ASUMIDA', 'MOVIMIENTO_ENTRADA', 'PEDIDO_EN_PREPARACION'],
      registros: [
        { clave: 'MI-9', fecha: '2026-10-29T15:00:00Z', usuarioId: 3, usuario: 'Admin Acopio', correo: 'admin_acopio@ccpip.test',
          rol: 'ADMIN_PUNTO', accion: 'MOVIMIENTO_ENTRADA', entidad: 'MOVIMIENTO', entidadId: 9,
          detalle: 'Miel de abeja nativa · lote L-01 · 3 und · Centro Nacional Lima', resultado: 'EXITOSA' },
        { clave: 'PH-5', fecha: '2026-10-29T14:00:00Z', usuarioId: 3, usuario: 'Admin Acopio', correo: 'admin_acopio@ccpip.test',
          rol: 'ADMIN_PUNTO', accion: 'PEDIDO_EN_PREPARACION', entidad: 'PEDIDO', entidadId: 40, detalle: 'CCPIP-000040', resultado: 'EXITOSA' },
        { clave: 'EA-2', fecha: '2026-10-29T13:00:00Z', usuarioId: 8, usuario: 'Distribuidor de Prueba', correo: 'distribuidor@ccpip.test',
          rol: 'DISTRIBUIDOR', accion: 'ENTREGA_ASUMIDA', entidad: 'PEDIDO', entidadId: 40, detalle: 'CCPIP-000040', resultado: 'EXITOSA' },
      ],
    }
    const soloMovimiento = { ...copia(operacion), total: 1, registros: [copia(operacion.registros[0])] }
    const fetchMock = comoSuper({
      [`GET ${RUTA}`]: [200, operacion],
      [`GET ${RUTA}?${PERIODO}&entidad=MOVIMIENTO`]: [200, soloMovimiento],
    })
    const user = userEvent.setup()
    abrirEn('/admin/auditoria')
    const [movimiento, preparo, asumio] = await filas()
    expect(within(movimiento).getByText('Registró entrada de inventario')).toBeInTheDocument()
    expect(within(movimiento).getByText('Movimiento de inventario')).toBeInTheDocument()
    expect(within(movimiento).getByText('Miel de abeja nativa · lote L-01 · 3 und · Centro Nacional Lima')).toBeInTheDocument()
    expect(within(preparo).getByText('Preparó pedido')).toBeInTheDocument()
    expect(within(asumio).getByText('Tomó entrega')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Entidad'), 'Movimiento de inventario')
    expect(await filas()).toHaveLength(1)
    expect(llamadas(fetchMock, `GET ${RUTA}?${PERIODO}&entidad=MOVIMIENTO`)).toHaveLength(1)
  })

  it('CCPIP-509: diferencia actor anónimo, valida ubicación positiva y traduce decisiones', async () => {
    const ampliado = { ...copia(AUDITORIA), registros: [{ ...copia(AUDITORIA.registros[0]), usuario: null, correo: null, actor: 'ANONIMO', ubicacionId: 12, referencia: 'PEDIDO-40', correlacion: 'corr-abc', decision: 'APROBADA' }] }
    comoSuper({ [`GET ${RUTA}`]: [200, ampliado] })
    abrirEn('/admin/auditoria')
    const [fila] = await filas()
    expect(within(fila).getByText('Anónimo')).toBeInTheDocument()
    expect(within(fila).getByText('Decisión: Aprobada')).toBeInTheDocument()
    expect(screen.getByLabelText('Ubicación (ID)')).toHaveAttribute('min', '1')
  })

  it('CCPIP-509: filtros de actor, ubicación y referencia más trazabilidad', async () => {
    const ampliado = { ...copia(AUDITORIA), registros: [{ ...copia(AUDITORIA.registros[0]), actor: 'USUARIO', ubicacionId: 12, referencia: 'PEDIDO-40', correlacion: 'corr-abc', decision: 'RECHAZADO' }] }
    const fetchMock = comoSuper({ [`GET ${RUTA}?${PERIODO}&actor=USUARIO&ubicacion=12&referencia=PEDIDO-40`]: [200, ampliado] })
    const user = userEvent.setup()
    abrirEn('/admin/auditoria')
    await filas()
    await user.selectOptions(screen.getByLabelText('Actor'), 'USUARIO')
    await user.type(screen.getByLabelText('Ubicación (ID)'), '12')
    await user.type(screen.getByLabelText('Referencia'), 'PEDIDO-40')
    await user.click(screen.getByRole('button', { name: 'Buscar' }))
    const [fila] = await filas()
    expect(llamadas(fetchMock, `GET ${RUTA}?${PERIODO}&actor=USUARIO&ubicacion=12&referencia=PEDIDO-40`)).toHaveLength(1)
    expect(within(fila).getByText(/corr-abc/)).toBeInTheDocument()
    expect(within(fila).getByText(/PEDIDO-40/)).toBeInTheDocument()
    expect(within(fila).getByText(/12/)).toBeInTheDocument()
    expect(within(fila).getByText(/Decisión/)).toHaveTextContent('Rechazado')
    expect(within(fila).getByText('Rechazado')).toBeInTheDocument()
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

  it('CCPIP-509: traduce filtros, entidad nueva y trazabilidad al inglés', async () => {
    await i18n.changeLanguage('en')
    const registro = { ...copia(AUDITORIA.registros[0]), entidad: 'CUENTA_BANCARIA', actor: 'ANONIMO',
      referencia: 'BANK-7', ubicacionId: 12, correlacion: 'trace-en', decision: 'APROBADO' }
    const respuesta = { ...copia(AUDITORIA), registros: [registro] }
    const fetchMock = comoSuper({
      [`GET ${RUTA}`]: [200, respuesta],
      [`GET ${RUTA}?${PERIODO}&actor=ANONIMO&ubicacion=12&referencia=BANK-7`]: [200, respuesta],
    })
    const user = userEvent.setup()
    abrirEn('/admin/auditoria')
    const [fila] = await filas('Audit of sensitive operations')
    expect(within(fila).getByText('Bank account')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Actor'), 'Anonymous')
    await user.type(screen.getByLabelText('Location (ID)'), '12')
    await user.type(screen.getByLabelText('Reference'), 'BANK-7')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(llamadas(fetchMock, `GET ${RUTA}?${PERIODO}&actor=ANONIMO&ubicacion=12&referencia=BANK-7`)).toHaveLength(1)
    const [filtrada] = await filas('Audit of sensitive operations')
    expect(within(filtrada).getByText(/trace-en/)).toBeInTheDocument()
    expect(within(filtrada).getByText(/Approved/)).toBeInTheDocument()
  })
})
