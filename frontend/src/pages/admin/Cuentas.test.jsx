import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const CUENTAS = '/api/admin/cuentas'
const DETALLE = `${CUENTAS}/4`

const comoSuper = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUPER],
    [`GET ${CUENTAS}`]: () => [200, copia(DATOS.cuentasAdmin)],
    [`GET ${CUENTAS}/puntos`]: () => [200, copia(DATOS.puntosCuentas)],
    [`GET ${DETALLE}`]: () => [200, copia(DATOS.cuentaAdmin)],
    'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
    ...rutas,
  })

const comoSub = (rutas = {}) => comoSuper({ 'GET /api/auth/me': [200, SESION_SUB], ...rutas })

const cuerpo = (llamada) => JSON.parse(llamada[1].body)
const filas = () => screen.getAllByRole('row').slice(1)

/** La cuenta que devolveria el servidor tras cambiar de estado. */
function conEstado(estado, accion, motivo) {
  const c = copia(DATOS.cuentaAdmin)
  c.estado = estado
  c.acciones = { editar: estado !== 'DESACTIVADA', suspender: estado === 'ACTIVA', reactivar: estado === 'SUSPENDIDA', desactivar: estado !== 'DESACTIVADA', reenviarAcceso: false }
  c.historial.unshift({ id: 13, accion, motivo, cambios: { estado: { antes: 'ACTIVA', despues: estado } }, realizadoPor: 'Ana Shuñaqui', realizadoEn: '2026-10-17T17:00:00Z' })
  return c
}

afterEach(async () => {
  await i18n.changeLanguage('es')
})

describe('SA-03 y SA-04 Cuentas: consulta y búsqueda (CCPIP-124, CCPIP-125, CCPIP-95)', () => {
  it('el superadministrador consulta los sub administradores con su estado', async () => {
    const fetchMock = comoSuper()
    abrirEn('/admin/cuentas')

    expect(await screen.findByRole('heading', { level: 1, name: 'Cuentas' })).toBeInTheDocument()
    expect(await screen.findByText('Lucía Paredes')).toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${CUENTAS}?rol=SUB_ADMIN`)).toHaveLength(1)
    expect(screen.getByText('3 cuentas')).toBeInTheDocument()

    const rosa = within(filas()[1])
    expect(rosa.getByText('rosa@ccpip.test')).toBeInTheDocument()
    expect(rosa.getByText('Acceso pendiente')).toBeInTheDocument()
    expect(within(filas()[2]).getByText('Suspendida')).toBeInTheDocument()
    expect(rosa.getByRole('link', { name: 'Ver la cuenta de Rosa Huamán' })).toHaveAttribute('href', '/admin/cuentas/21')

    // Las pestanas son los roles que devuelve el servidor
    const tabs = screen.getAllByRole('tab').map((t) => t.textContent)
    expect(tabs).toEqual(['Todas', 'Consumidores', 'Productores', 'Administradores de punto', 'Sub administradores', 'Distribuidores'])
    expect(screen.getByRole('tab', { name: 'Sub administradores' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('link', { name: 'Nueva cuenta' })).toHaveAttribute('href', '/admin/cuentas/nueva')

    const nav = within(screen.getByRole('navigation', { name: 'Super administrador' }))
    expect(nav.getByRole('link', { name: 'Cuentas' })).toHaveAttribute('aria-current', 'page')
  })

  it('los administradores de punto se ven con sus puntos de acopio', async () => {
    const datos = copia(DATOS.cuentasAdmin)
    datos.items = [{ ...datos.items[0], id: 4, nombre: 'Admin Acopio', rol: 'ADMIN_PUNTO', puntos: ['Centro Nacional Lima', 'Centro Regional Cusco'] }]
    datos.total = 1
    const fetchMock = comoSuper({ [`GET ${CUENTAS}?rol=ADMIN_PUNTO`]: [200, datos] })
    abrirEn('/admin/cuentas')
    await screen.findByText('Lucía Paredes')

    await userEvent.click(screen.getByRole('tab', { name: 'Administradores de punto' }))
    expect(await screen.findByText('Centro Nacional Lima, Centro Regional Cusco')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Puntos de acopio' })).toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${CUENTAS}?rol=ADMIN_PUNTO`)).toHaveLength(1)
  })

  it('busca por nombre o correo y filtra por estado', async () => {
    const fetchMock = comoSuper()
    abrirEn('/admin/cuentas')
    await screen.findByText('Lucía Paredes')

    await userEvent.type(screen.getByLabelText('Buscar cuentas'), ' rosa {Enter}')
    await waitFor(() => expect(llamadas(fetchMock, `GET ${CUENTAS}?q=rosa&rol=SUB_ADMIN`)).toHaveLength(1))
    await userEvent.selectOptions(screen.getByLabelText('Estado'), 'SUSPENDIDA')
    await waitFor(() => expect(llamadas(fetchMock, `GET ${CUENTAS}?q=rosa&rol=SUB_ADMIN&estado=SUSPENDIDA`)).toHaveLength(1))
  })

  it('sin resultados muestra un estado vacío y un error del servidor se explica', async () => {
    const vacio = { ...copia(DATOS.cuentasAdmin), items: [], total: 0 }
    comoSuper({ [`GET ${CUENTAS}`]: [200, vacio] })
    const { unmount } = abrirEn('/admin/cuentas')
    expect(await screen.findByText('No hay cuentas para mostrar')).toBeInTheDocument()
    unmount()

    comoSuper({ [`GET ${CUENTAS}`]: [403, { code: 'ROL_FUERA_DE_ALCANCE', error: 'Tu rol no permite consultar cuentas de ese tipo.' }] })
    abrirEn('/admin/cuentas')
    expect(await screen.findByText('Tu rol no permite consultar cuentas de ese tipo.')).toBeInTheDocument()
  })

  it('el sub administrador busca en su alcance y no crea cuentas', async () => {
    const datos = copia(DATOS.cuentasAdmin)
    datos.roles = ['CONSUMIDOR', 'PRODUCTOR', 'DISTRIBUIDOR']
    datos.items = [{ ...datos.items[0], id: 1, nombre: 'Consumidor de Prueba', correo: 'consumidor@ccpip.test', rol: 'CONSUMIDOR' }]
    const fetchMock = comoSub({ [`GET ${CUENTAS}`]: [200, datos] })
    abrirEn('/admin/cuentas')

    expect(await screen.findByText('Consumidor de Prueba')).toBeInTheDocument()
    expect(llamadas(fetchMock, `GET ${CUENTAS}`)).toHaveLength(1)
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Todas', 'Consumidores', 'Productores', 'Distribuidores'])
    expect(screen.queryByRole('link', { name: 'Nueva cuenta' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Puntos de acopio' })).not.toBeInTheDocument()
  })

  it('el sub administrador no abre el alta de cuentas', async () => {
    comoSub({ 'GET /api/admin/autorizaciones': () => [200, copia(DATOS.autorizacionesAdmin)] })
    abrirEn('/admin/cuentas/nueva')
    await waitFor(() => expect(window.location.pathname).toBe('/admin/autorizaciones'))
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    comoSuper()
    abrirEn('/admin/cuentas')
    expect(await screen.findByRole('heading', { level: 1, name: 'Accounts' })).toBeInTheDocument()
    expect(await screen.findByText('Access pending')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Sub administrators' })).toBeInTheDocument()
  })
})

describe('Nueva cuenta administrativa (CCPIP-301, CCPIP-304)', () => {
  it('mientras trae los puntos de acopio, la lista muestra el indicador de carga (CCPIP-413)', async () => {
    let responder
    comoSuper({ [`GET ${CUENTAS}/puntos`]: () => new Promise((r) => { responder = r }) })
    abrirEn('/admin/cuentas/nueva')
    await userEvent.selectOptions(await screen.findByLabelText('Rol'), 'ADMIN_PUNTO')

    const cargando = screen.getByText('Cargando los puntos de acopio…').closest('[role="status"]')
    expect(cargando.querySelector('.cargando__giro')).not.toBeNull()
    responder([200, copia(DATOS.puntosCuentas)])
    expect(await screen.findByRole('checkbox', { name: /Centro Regional Cusco/ })).toBeInTheDocument()
    expect(screen.queryByText('Cargando los puntos de acopio…')).not.toBeInTheDocument()
  })

  it('valida antes de enviar y pide puntos para el administrador de punto', async () => {
    const fetchMock = comoSuper()
    abrirEn('/admin/cuentas/nueva')
    await userEvent.click(await screen.findByRole('button', { name: 'Crear cuenta' }))
    expect(screen.getByText('Escribe el nombre')).toBeInTheDocument()
    expect(screen.getByText('Escribe un correo válido')).toBeInTheDocument()
    expect(screen.getByText('Elige el rol')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Rol'), 'ADMIN_PUNTO')
    await userEvent.type(screen.getByLabelText('Teléfono'), 'abc')
    await userEvent.click(screen.getByRole('button', { name: 'Crear cuenta' }))
    expect(screen.getByText('Marca al menos un punto de acopio')).toBeInTheDocument()
    expect(screen.getByText('Escribe un teléfono válido')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${CUENTAS}`)).toHaveLength(0)
  })

  it('crea un administrador de punto con sus puntos, sin contraseña, y abre su cuenta', { timeout: 15_000 }, async () => {
    const creada = { ...copia(DATOS.cuentaAdmin), id: 30, nombre: 'Pedro Quispe', accesoPendiente: true }
    const fetchMock = comoSuper({
      [`POST ${CUENTAS}`]: [201, { cuenta: creada, avisoEnviado: true }],
      [`GET ${CUENTAS}/30`]: [200, creada],
    })
    abrirEn('/admin/cuentas/nueva')
    expect(await screen.findByText(/le enviaremos un enlace de un solo uso/)).toBeInTheDocument()
    expect(screen.queryByLabelText(/contraseña/i)).not.toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Nombre completo'), ' Pedro Quispe ')
    await userEvent.type(screen.getByLabelText('Correo'), 'pedro@ccpip.test')
    await userEvent.selectOptions(screen.getByLabelText('Rol'), 'ADMIN_PUNTO')
    await userEvent.click(await screen.findByRole('checkbox', { name: /Centro Regional Cusco/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /Centro Regional Loreto/ }))
    await userEvent.click(screen.getByRole('checkbox', { name: /Centro Regional Loreto/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Crear cuenta' }))

    await waitFor(() => expect(window.location.pathname).toBe('/admin/cuentas/30'))
    expect(cuerpo(llamadas(fetchMock, `POST ${CUENTAS}`)[0])).toEqual({
      nombre: 'Pedro Quispe', correo: 'pedro@ccpip.test', telefono: null, rol: 'ADMIN_PUNTO', puntos: [3],
    })
    expect(await screen.findByText('Cuenta creada. Le enviamos el enlace para elegir su contraseña.')).toBeInTheDocument()
  })

  it('el sub administrador se crea sin puntos y avisa si el correo no salió', async () => {
    const creada = { ...copia(DATOS.cuentaAdmin), id: 31, rol: 'SUB_ADMIN', puntos: [] }
    const fetchMock = comoSuper({
      [`POST ${CUENTAS}`]: [201, { cuenta: creada, avisoEnviado: false }],
      [`GET ${CUENTAS}/31`]: [200, creada],
    })
    abrirEn('/admin/cuentas/nueva')
    await userEvent.type(await screen.findByLabelText('Nombre completo'), 'Rosa Huamán')
    await userEvent.type(screen.getByLabelText('Correo'), 'rosa@ccpip.test')
    await userEvent.type(screen.getByLabelText('Teléfono'), '+51 984 000 111')
    await userEvent.selectOptions(screen.getByLabelText('Rol'), 'SUB_ADMIN')
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Crear cuenta' }))

    expect(await screen.findByText(/El correo de aviso no se pudo enviar/)).toBeInTheDocument()
    expect(cuerpo(llamadas(fetchMock, `POST ${CUENTAS}`)[0])).toMatchObject({ rol: 'SUB_ADMIN', puntos: [], telefono: '+51 984 000 111' })
  })

  it('un correo en uso se señala en su campo y otros errores en una alerta', async () => {
    let respuesta = [409, { code: 'CORREO_EN_USO', error: 'Ya hay una cuenta con ese correo.' }]
    comoSuper({ [`POST ${CUENTAS}`]: () => respuesta })
    abrirEn('/admin/cuentas/nueva')
    await userEvent.type(await screen.findByLabelText('Nombre completo'), 'Rosa')
    await userEvent.type(screen.getByLabelText('Correo'), 'sub_admin@ccpip.test')
    await userEvent.selectOptions(screen.getByLabelText('Rol'), 'SUB_ADMIN')
    await userEvent.click(screen.getByRole('button', { name: 'Crear cuenta' }))
    expect(await screen.findByText('Ya hay una cuenta con ese correo.')).toBeInTheDocument()
    expect(screen.getByLabelText('Correo')).toHaveAttribute('aria-invalid', 'true')

    respuesta = [403, { code: 'SIN_PERMISO', error: 'No tienes permiso para esta accion' }]
    await userEvent.click(screen.getByRole('button', { name: 'Crear cuenta' }))
    expect(await screen.findByText('No pudimos crear la cuenta.')).toBeInTheDocument()
  })
})

describe('Detalle de la cuenta (CCPIP-302, 303, 305, 306, 307, 308)', () => {
  it('muestra los datos, los puntos y el historial con quién, cuándo y por qué', async () => {
    comoSuper()
    abrirEn('/admin/cuentas/4')
    expect(await screen.findByRole('heading', { level: 1, name: 'Administrador de Acopio de Prueba' })).toBeInTheDocument()
    expect(screen.getByText('admin_acopio@ccpip.test')).toBeInTheDocument()
    expect(screen.getByText('+51 984 111 222')).toBeInTheDocument()
    expect(screen.getAllByText('Centro Nacional Lima').length).toBeGreaterThan(0)

    const historial = within(screen.getByRole('list', { name: 'Historial' })).getAllByRole('listitem')
    expect(within(historial[0]).getByText('Datos modificados')).toBeInTheDocument()
    expect(within(historial[0]).getByText('Puntos de acopio: Centro Regional Cusco → Centro Nacional Lima')).toBeInTheDocument()
    expect(within(historial[1]).getByText('Motivo: Auditoría de inventario')).toBeInTheDocument()
    expect(within(historial[1]).getByText('Estado: Activa → Suspendida')).toBeInTheDocument()
    expect(within(historial[1]).getByText(/Ana Shuñaqui/)).toBeInTheDocument()
  })

  it('modifica los datos autorizados y los puntos; el correo y el rol no se editan', { timeout: 15_000 }, async () => {
    const guardada = copia(DATOS.cuentaAdmin)
    guardada.nombre = 'Administrador del Cusco'
    const fetchMock = comoSuper({ [`PUT ${DETALLE}`]: [200, guardada] })
    abrirEn('/admin/cuentas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }))

    const form = within(screen.getByRole('form', { name: 'Editar datos de la cuenta' }))
    expect(form.queryByLabelText('Correo')).not.toBeInTheDocument()
    expect(form.queryByLabelText('Rol')).not.toBeInTheDocument()
    expect(form.getByRole('checkbox', { name: /Centro Nacional Lima/ })).toBeChecked()

    await userEvent.clear(form.getByLabelText('Nombre completo'))
    await userEvent.click(form.getByRole('button', { name: 'Guardar cambios' }))
    expect(form.getByText('Escribe el nombre')).toBeInTheDocument()

    await userEvent.type(form.getByLabelText('Nombre completo'), 'Administrador del Cusco')
    await userEvent.clear(form.getByLabelText('Teléfono'))
    await userEvent.click(form.getByRole('checkbox', { name: /Centro Regional Cusco/ }))
    await userEvent.click(form.getByRole('checkbox', { name: /Centro Nacional Lima/ }))
    await userEvent.click(form.getByRole('button', { name: 'Guardar cambios' }))

    expect(await screen.findByText('Cambios guardados.')).toBeInTheDocument()
    expect(cuerpo(llamadas(fetchMock, `PUT ${DETALLE}`)[0])).toEqual({ nombre: 'Administrador del Cusco', telefono: null, puntos: [3] })
    expect(screen.getByRole('heading', { level: 1, name: 'Administrador del Cusco' })).toBeInTheDocument()
  })

  it('un error al guardar los puntos se muestra en su sección', async () => {
    comoSuper({ [`PUT ${DETALLE}`]: [422, { code: 'PUNTO_INVALIDO', error: 'Solo se asignan puntos de acopio activos de la Cámara.' }] })
    abrirEn('/admin/cuentas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Editar' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Solo se asignan puntos de acopio activos de la Cámara.')
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('form', { name: 'Editar datos de la cuenta' })).not.toBeInTheDocument()
  })

  it('suspende con motivo obligatorio y luego reactiva', { timeout: 15_000 }, async () => {
    const fetchMock = comoSuper({
      [`POST ${DETALLE}/suspender`]: () => [200, { cuenta: conEstado('SUSPENDIDA', 'SUSPENDER', 'Reportes de pagos'), avisoEnviado: true }],
      [`POST ${DETALLE}/reactivar`]: () => [200, { cuenta: conEstado('ACTIVA', 'REACTIVAR', 'Pagos aclarados'), avisoEnviado: true }],
    })
    abrirEn('/admin/cuentas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Suspender' }))
    expect(screen.getByRole('heading', { name: '¿Suspender la cuenta de Administrador de Acopio de Prueba?' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Suspender cuenta' }))
    expect(screen.getByText('Escribe el motivo')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${DETALLE}/suspender`)).toHaveLength(0)

    await userEvent.type(screen.getByLabelText('Motivo'), 'Reportes de pagos')
    await userEvent.click(screen.getByRole('button', { name: 'Suspender cuenta' }))
    expect(await screen.findByText('Cuenta suspendida.')).toBeInTheDocument()
    expect(cuerpo(llamadas(fetchMock, `POST ${DETALLE}/suspender`)[0])).toEqual({ motivo: 'Reportes de pagos' })
    expect(screen.getAllByText('Suspendida').length).toBeGreaterThan(0)

    await userEvent.click(screen.getByRole('button', { name: 'Reactivar' }))
    await userEvent.type(screen.getByLabelText('Motivo'), 'Pagos aclarados')
    await userEvent.click(screen.getByRole('button', { name: 'Reactivar cuenta' }))
    expect(await screen.findByText('Cuenta reactivada.')).toBeInTheDocument()
  })

  it('desactiva de forma definitiva y avisa si el correo no salió; un rechazo del servidor queda en el diálogo', { timeout: 15_000 }, async () => {
    let respuesta = [409, { code: 'ESTADO_NO_PERMITE', error: 'El estado actual de la cuenta no permite esta acción. Actualiza la pantalla.' }]
    comoSuper({ [`POST ${DETALLE}/desactivar`]: () => respuesta })
    abrirEn('/admin/cuentas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar' }))
    expect(screen.getByText(/Es definitivo/)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Motivo'), 'Terminó su contrato')
    await userEvent.click(screen.getByRole('button', { name: 'Desactivar cuenta' }))
    expect(await screen.findByText('El estado actual de la cuenta no permite esta acción. Actualiza la pantalla.')).toBeInTheDocument()

    const c = conEstado('DESACTIVADA', 'DESACTIVAR', 'Terminó su contrato')
    c.acciones = { editar: false, suspender: false, reactivar: false, desactivar: false, reenviarAcceso: false }
    respuesta = [200, { cuenta: c, avisoEnviado: false }]
    await userEvent.click(screen.getByRole('button', { name: 'Desactivar cuenta' }))
    expect(await screen.findByText('Cuenta desactivada.')).toBeInTheDocument()
    expect(screen.getByText(/El correo de aviso no se pudo enviar/)).toBeInTheDocument()
    expect(screen.getByText(/Esta cuenta está desactivada/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Editar' })).not.toBeInTheDocument()
  })

  it('cancelar el diálogo no cambia nada', async () => {
    const fetchMock = comoSuper()
    abrirEn('/admin/cuentas/4')
    await userEvent.click(await screen.findByRole('button', { name: 'Suspender' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByLabelText('Motivo')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([, o = {}]) => o.method === 'POST')).toHaveLength(0)
  })

  it('reenvía el enlace de acceso a una cuenta que aún no eligió su contraseña', async () => {
    const pendiente = { ...copia(DATOS.cuentaAdmin), accesoPendiente: true }
    pendiente.acciones.reenviarAcceso = true
    let respuesta = [200, { cuenta: pendiente, avisoEnviado: true }]
    const fetchMock = comoSuper({ [`GET ${DETALLE}`]: [200, pendiente], [`POST ${DETALLE}/reenviar-acceso`]: () => respuesta })
    abrirEn('/admin/cuentas/4')
    expect(await screen.findByText('Acceso pendiente')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Reenviar enlace de acceso' }))
    expect(await screen.findByText('Enlace de acceso reenviado.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${DETALLE}/reenviar-acceso`)).toHaveLength(1)

    respuesta = [409, { code: 'ACCESO_YA_CONFIGURADO', error: 'La persona ya eligió su contraseña.' }]
    await userEvent.click(screen.getByRole('button', { name: 'Reenviar enlace de acceso' }))
    expect(await screen.findByText('No pudimos reenviar el acceso.')).toBeInTheDocument()
  })

  it('el sub administrador solo suspende o reactiva cuentas de su alcance', async () => {
    const consumidor = {
      ...copia(DATOS.cuentaAdmin), id: 1, nombre: 'Consumidor de Prueba', rol: 'CONSUMIDOR', telefono: null, puntos: [], historial: [],
      acciones: { editar: false, suspender: true, reactivar: false, desactivar: false, reenviarAcceso: false },
    }
    comoSub({ [`GET ${CUENTAS}/1`]: [200, consumidor] })
    abrirEn('/admin/cuentas/1')
    expect(await screen.findByRole('button', { name: 'Suspender' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Editar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Desactivar' })).not.toBeInTheDocument()
    expect(screen.getByText('Sin cambios registrados desde la plataforma.')).toBeInTheDocument()
  })

  it('una cuenta fuera del alcance se explica', async () => {
    comoSub({ [`GET ${DETALLE}`]: [404, { code: 'CUENTA_NO_ENCONTRADA', error: 'No encontramos esa cuenta.' }] })
    abrirEn('/admin/cuentas/4')
    expect(await screen.findByText('No encontramos esa cuenta.')).toBeInTheDocument()
  })
})
