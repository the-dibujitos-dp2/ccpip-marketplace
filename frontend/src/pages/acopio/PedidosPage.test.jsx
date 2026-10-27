import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { abrirPestana, copia, DATOS, llamadas, panel, PUBLICAS, servidor, SESION, SESION_ACOPIO, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const LISTAR = 'GET /api/acopio/pedidos'
const PREPARACION = 'POST /api/acopio/pedidos/MC-00155/preparacion'
const LISTO = 'POST /api/acopio/pedidos/MC-00155/listo'

const comoAcopio = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_ACOPIO],
  [LISTAR]: () => [200, copia(DATOS.pedidos)],
  ...rutas,
})

const nombresPestanas = () => screen.getAllByRole('tab').map((p) => p.textContent)

describe('A-05 Pedidos del punto de acopio en una tabla por estado (CCPIP-419)', () => {
  it('Confirmado, Listo para recojo y Entregado siempre están; cada pestaña muestra solo los pedidos de su estado', async () => {
    servidor(comoAcopio())
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')

    const confirmado = await panel('Confirmado')
    expect(nombresPestanas()).toEqual(['Confirmado (1)', 'Listo para recojo (1)', 'Entregado hoy (1)'])
    expect(screen.getByRole('tab', { name: 'Confirmado (1)' })).toHaveAttribute('aria-selected', 'true')
    const numeros = (tabla) => tabla.getAllByText(/^MC-\d{5}$/).map((n) => n.textContent)
    expect(confirmado.getByRole('list', { name: 'Pedidos del estado elegido' })).toBeInTheDocument()
    expect(numeros(confirmado)).toEqual(['MC-00155'])
    expect(numeros(await abrirPestana(user, 'Listo para recojo'))).toEqual(['MC-00139'])
    expect(numeros(await abrirPestana(user, 'Entregado hoy'))).toEqual(['MC-00131'])
  })

  it('una pestaña sin pedidos lo dice; los estados intermedios solo aparecen si tienen pedidos', async () => {
    const datos = copia(DATOS.pedidos)
    datos.items = datos.items.filter((p) => p.estado !== 'ENTREGADO')
    datos.items[0].estado = 'EN_PREPARACION'
    servidor(comoAcopio({ [LISTAR]: [200, datos] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')

    expect(await (await panel('Confirmado')).findByText('No hay pedidos por preparar.')).toBeInTheDocument()
    expect(nombresPestanas()).toEqual(['Confirmado (0)', 'En preparación (1)', 'Listo para recojo (1)', 'Entregado hoy (0)'])
    expect((await abrirPestana(user, 'Entregado hoy')).getByText('Aún no se entregó ningún pedido hoy.')).toBeInTheDocument()
  })
})

describe('A-05 Pedidos del punto de acopio (CCPIP-159)', () => {
  it('muestra EN_CAMINO en una pestaña de solo lectura con fecha y responsable del historial', async () => {
    const datos = copia(DATOS.pedidos)
    datos.items[0].estado = 'EN_CAMINO'
    datos.items[0].modalidad = 'ENVIO'
    datos.items[0].historial.push({ estado: 'EN_CAMINO', registradoEn: '2026-10-13T15:33:58.109980Z', responsable: 'Distribuidor de Prueba' })
    servidor(comoAcopio({ [LISTAR]: [200, datos] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')

    const enCamino = await abrirPestana(user, 'En camino')
    expect(enCamino.getByText('MC-00155')).toBeInTheDocument()
    expect(enCamino.getByText(/En camino .*Distribuidor de Prueba/)).toBeInTheDocument()
    expect(enCamino.queryByRole('button')).not.toBeInTheDocument()
  })

  it('muestra EN_PREPARACION en su pestaña y permite continuar a listo para recojo', async () => {
    const datos = copia(DATOS.pedidos)
    datos.items[0].estado = 'EN_PREPARACION'
    datos.items[0].historial.push({
      estado: 'EN_PREPARACION',
      registradoEn: '2026-10-13T15:33:58.109980Z',
      responsable: 'Administrador de Acopio de Prueba',
    })
    servidor(comoAcopio({ [LISTAR]: [200, datos] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')

    const preparacion = await abrirPestana(user, 'En preparación')
    expect(screen.getByRole('tab', { name: 'En preparación (1)' })).toHaveAttribute('aria-selected', 'true')
    expect(preparacion.getByText('MC-00155')).toBeInTheDocument()
    expect(preparacion.getByText(/En preparación .*Administrador de Acopio de Prueba/)).toBeInTheDocument()
    expect(preparacion.getByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' })).toBeEnabled()
  })

  it('registra la preparación por API, conserva historial y maneja conflicto recargando el pedido', async () => {
    const preparando = copia(DATOS.pedidos.items[0])
    preparando.estado = 'EN_PREPARACION'
    preparando.historial.push({
      estado: 'EN_PREPARACION',
      registradoEn: '2026-10-13T15:33:58.109980Z',
      responsable: 'Administrador de Acopio de Prueba',
    })
    const fetchMock = servidor(comoAcopio({ [PREPARACION]: [200, preparando] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: 'Registrar la preparación del pedido MC-00155' }))

    expect(await screen.findByText('El pedido MC-00155 está en preparación')).toBeInTheDocument()
    expect(llamadas(fetchMock, PREPARACION)).toHaveLength(1)
    const preparacion = await abrirPestana(user, 'En preparación')
    expect(preparacion.getByText('MC-00155')).toBeInTheDocument()
    expect(preparacion.getByText(/En preparación .*Administrador de Acopio de Prueba/)).toBeInTheDocument()
  })

  it('muestra cada pedido en la pestaña de su estado, con sus productos, total y quien lo marco', async () => {
    servidor(comoAcopio())
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')

    expect(await screen.findByRole('heading', { level: 1, name: 'Pedidos' })).toBeInTheDocument()
    expect(await screen.findByText('Centro Nacional Lima / Pedidos')).toBeInTheDocument()

    // Confirmado: se puede registrar que ya empezo la preparacion
    const confirmado = await panel('Confirmado')
    expect(confirmado.getByText('MC-00155')).toBeInTheDocument()
    expect(confirmado.getByText('Asoc. Nii Joi · Aceite de ungurahui 250 ml × 1')).toBeInTheDocument()
    expect(confirmado.getByText('Asoc. Nii Joi · Aceite de aguaje 250 ml × 1')).toBeInTheDocument()
    expect(confirmado.getByText('Confirmado 13/10 06:08')).toBeInTheDocument()
    expect(confirmado.getByText('S/ 96.00')).toBeInTheDocument()
    expect(confirmado.getByRole('button', { name: 'Registrar la preparación del pedido MC-00155' })).toBeEnabled()

    // Listo: con el responsable y la hora del historial (hora de Lima), sin boton
    const listo = await abrirPestana(user, 'Listo para recojo')
    expect(listo.getByText('MC-00139')).toBeInTheDocument()
    expect(listo.getByText('C.N. Yarina · Cacao en grano fermentado 1 kg × 2')).toBeInTheDocument()
    expect(listo.getByText('Listo 13/10 07:38 · Administrador de Acopio de Prueba')).toBeInTheDocument()
    expect(listo.getByText('S/ 160.00')).toBeInTheDocument()
    // Lo listo espera su entrega: se registra el recojo, ya no se vuelve a marcar
    expect(listo.getByRole('button', { name: 'Registrar el recojo del pedido MC-00139' })).toBeEnabled()
    expect(listo.queryByRole('button', { name: /Marcar el pedido/ })).not.toBeInTheDocument()

    // Lo entregado cuenta quien se lo llevo y ya no tiene acciones
    const entregado = await abrirPestana(user, 'Entregado hoy')
    expect(entregado.getByText('MC-00131')).toBeInTheDocument()
    expect(entregado.getByText('Recogió Rosa Ancón Pérez')).toBeInTheDocument()
    expect(entregado.queryByRole('button')).not.toBeInTheDocument()

    // Nunca se nombra al comprador (RNF-05)
    expect(screen.queryByText(/Consumidor de Prueba|consumidor@/)).not.toBeInTheDocument()
  })

  it('cada linea muestra los lotes asignados, el que vence antes primero (CCPIP-74)', async () => {
    servidor(comoAcopio())
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')

    // Una linea con un solo lote, en cada pestana
    expect((await panel('Confirmado')).getByText('Lote L-0008 · vence 13/10/2027 · 1 und')).toBeInTheDocument()
    const listo = await abrirPestana(user, 'Listo para recojo')
    expect(listo.getByText('Lote L-0001 · vence 13/10/2027 · 2 und')).toBeInTheDocument()
    // Si el pedido aun no descuenta existencias, lo dice en lugar de callar
    expect(listo.getByText('Sin lotes asignados')).toBeInTheDocument()

    // Un pedido repartido en dos lotes: primero el que vence antes
    const entregado = await abrirPestana(user, 'Entregado hoy')
    const cafe = within(entregado.getByRole('list', { name: 'Lotes de Cafe tostado en grano 500 g' })).getAllByRole('listitem')
    expect(cafe.map((l) => l.textContent)).toEqual([
      'Lote L-0010B · vence 02/11/2026 · 2 und',
      'Lote L-0010 · vence 13/10/2027 · 1 und',
    ])
  })

  it('lo que no vence, como una artesania, lo dice en lugar de mostrar una fecha vacia', async () => {
    const datos = copia(DATOS.pedidos)
    delete datos.items[0].lineas.find((l) => l.producto === 'Aceite de aguaje 250 ml').lotes[0].fechaVencimiento
    servidor(comoAcopio({ [LISTAR]: [200, datos] }))
    abrirEn('/acopio/pedidos')

    expect(await screen.findByText('Lote L-0008 · no vence · 1 und')).toBeInTheDocument()
  })

  it('preparar y luego marcar listo pasan el pedido por las dos pestañas y conservan los movimientos', async () => {
    const preparando = copia(DATOS.pedidos.items[0])
    preparando.estado = 'EN_PREPARACION'
    preparando.historial.push({ estado: 'EN_PREPARACION', registradoEn: '2026-10-13T15:33:58.109980Z', responsable: 'Administrador de Acopio de Prueba' })
    const fetchMock = servidor(comoAcopio({
      [PREPARACION]: [200, preparando],
      [LISTO]: () => [200, copia(DATOS.pedidoListo)],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: 'Registrar la preparación del pedido MC-00155' }))

    expect(await screen.findByText('El pedido MC-00155 está en preparación')).toBeInTheDocument()
    // El pedido deja la pestana abierta y pasa a la de su nuevo estado
    expect((await panel('Confirmado')).getByText('No hay pedidos por preparar.')).toBeInTheDocument()
    const preparacion = await abrirPestana(user, 'En preparación')
    await user.click(preparacion.getByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' }))
    expect(await screen.findByText('El pedido MC-00155 está listo para recojo')).toBeInTheDocument()
    expect(llamadas(fetchMock, PREPARACION)).toHaveLength(1)
    expect(llamadas(fetchMock, LISTO)).toHaveLength(1)
    expect(screen.queryByRole('button', { name: /Marcar el pedido/ })).not.toBeInTheDocument()
    const listo = await abrirPestana(user, 'Listo para recojo')
    expect(screen.getByRole('tab', { name: 'Listo para recojo (2)' })).toBeInTheDocument()
    expect(listo.getByText('MC-00155')).toBeInTheDocument()
    expect(listo.getByText('Listo 13/10 10:33 · Administrador de Acopio de Prueba')).toBeInTheDocument()
    // Los lotes de la respuesta viajan con el pedido a su nueva pestana
    expect(listo.getByText('Lote L-0008 · vence 13/10/2027 · 1 und')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Confirmado (0)' })).toBeInTheDocument()
    // Ya sin pedidos en preparacion, esa pestana intermedia se oculta al salir de ella
    expect(screen.queryByRole('tab', { name: /^En preparación/ })).not.toBeInTheDocument()
    // Solo se cambio ese pedido: la lista no se volvio a pedir
    expect(llamadas(fetchMock, LISTAR)).toHaveLength(1)
  })

  it('si otra persona ya lo cambio, explica el motivo y vuelve a leer la lista', async () => {
    let lecturas = 0
    servidor(comoAcopio({
      [LISTAR]: () => {
        const datos = copia(DATOS.pedidos)
        // Desde la segunda lectura, MC-00155 ya esta listo
        if (++lecturas > 1) Object.assign(datos.items[0], copia(DATOS.pedidoListo), { estado: 'EN_PREPARACION' })
        return [200, datos]
      },
      [PREPARACION]: [409, { code: 'ESTADO_INVALIDO', error: 'Este pedido está en preparación.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: 'Registrar la preparación del pedido MC-00155' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No pudimos registrar la preparación')
    expect(alerta).toHaveTextContent('Este pedido está en preparación.')
    await waitFor(() => expect(lecturas).toBe(2))
    expect((await abrirPestana(user, 'En preparación')).getByText('MC-00155')).toBeInTheDocument()
  })

  it('si otra persona ya marco listo, explica el conflicto y vuelve a leer la lista', async () => {
    let lecturas = 0
    const fetchMock = servidor(comoAcopio({
      [LISTAR]: () => {
        const datos = copia(DATOS.pedidos)
        if (++lecturas === 1) {
          datos.items[0].estado = 'EN_PREPARACION'
        } else {
          datos.items[0] = copia(DATOS.pedidoListo)
        }
        return [200, datos]
      },
      [LISTO]: [409, { code: 'ESTADO_INVALIDO', error: 'Este pedido ya está listo para recojo.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click((await abrirPestana(user, 'En preparación')).getByRole('button', { name: 'Marcar el pedido MC-00155 como listo para recojo' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No pudimos marcar el pedido')
    expect(alerta).toHaveTextContent('Este pedido ya está listo para recojo.')
    expect(llamadas(fetchMock, LISTO)).toHaveLength(1)
    await waitFor(() => expect(lecturas).toBe(2))
    expect((await abrirPestana(user, 'Listo para recojo')).getByText('MC-00155')).toBeInTheDocument()
  })

  it('mientras marca, el boton queda deshabilitado', async () => {
    let terminar
    servidor(comoAcopio({
      [PREPARACION]: () => new Promise((resolver) => { terminar = () => resolver([200, copia(DATOS.pedidoListo)]) }),
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    await user.click(await screen.findByRole('button', { name: 'Registrar la preparación del pedido MC-00155' }))
    expect(screen.getByRole('button', { name: 'Registrar la preparación del pedido MC-00155' })).toBeDisabled()
    terminar()
    expect(await screen.findByText('El pedido MC-00155 está en preparación')).toBeInTheDocument()
  })

  it('sin pedidos invita a esperar; si falla la carga lo dice y permite reintentar', async () => {
    servidor(comoAcopio({ [LISTAR]: [200, { ubicacion: 'Centro Nacional Lima', items: [] }] }))
    const { unmount } = abrirEn('/acopio/pedidos')
    expect(await screen.findByText('Aún no hay pedidos')).toBeInTheDocument()
    unmount()

    let intentos = 0
    servidor(comoAcopio({ [LISTAR]: () => (++intentos === 1 ? new Error('sin red') : [200, copia(DATOS.pedidos)]) }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    expect(await screen.findByText('No pudimos cargar los pedidos')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('MC-00155')).toBeInTheDocument()
  })

  it('desde el inicio y el menu del punto se llega a Pedidos y a Movimientos', async () => {
    servidor(comoAcopio({
      'GET /api/panel/acopio': [200, { titulo: 'Tu punto', descripcion: 'Panel del punto' }],
      'GET /api/acopio/movimientos?pagina=0': [200, copia(DATOS.movimientos)],
    }))
    const user = userEvent.setup()
    abrirEn('/inicio')
    await user.click(await screen.findByRole('link', { name: 'Ver pedidos del punto' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Pedidos' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/acopio/pedidos')
    expect(screen.getByRole('link', { name: 'Pedidos' })).toHaveAttribute('aria-current', 'page')

    await user.click(screen.getByRole('link', { name: 'Movimientos' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Movimientos' })).toBeInTheDocument()
  })

  it('/acopio lleva a Pedidos; solo entra el administrador de punto', async () => {
    servidor(comoAcopio())
    const { unmount } = abrirEn('/acopio')
    await waitFor(() => expect(window.location.pathname).toBe('/acopio/pedidos'))
    unmount()

    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION],
      'GET /api/panel/consumidor': [200, { titulo: 'Tu mercado', descripcion: 'Panel' }],
    })
    const otro = abrirEn('/acopio/pedidos')
    await waitFor(() => expect(window.location.pathname).toBe('/inicio'))
    expect(llamadas(fetchMock, LISTAR)).toHaveLength(0)
    otro.unmount()

    servidor({ ...PUBLICAS, 'GET /api/auth/me': SIN_SESION })
    abrirEn('/acopio/pedidos')
    await waitFor(() => expect(window.location.pathname).toBe('/ingresar'))
  })
})

describe('A-05 Registrar el recojo (CCPIP-71)', () => {
  const RECOJO = 'POST /api/acopio/pedidos/MC-00139/recojo'
  const TARJETA = 'Registrar el recojo del pedido MC-00139'
  // Los mensajes y codigos que devuelve el servidor
  const PERSONA = [422, { code: 'PERSONA_NO_COINCIDE', error: 'La persona no es quien compró ni la persona autorizada de este pedido.' }]
  const CELULAR = [422, { code: 'CELULAR_NO_COINCIDE', error: 'El celular no coincide con el registrado en el pedido.' }]

  async function abrirDialogo(user) {
    await user.click((await abrirPestana(user, 'Listo para recojo')).getByRole('button', { name: TARJETA }))
    return screen.findByRole('dialog', { name: 'Registrar recojo del pedido MC-00139' })
  }

  async function llenar(user, dialogo, persona, celular) {
    await user.type(within(dialogo).getByLabelText('Nombre de quien recoge'), persona)
    await user.type(within(dialogo).getByLabelText('Celular de contacto'), celular)
  }

  const registrar = (user, dialogo) => user.click(within(dialogo).getByRole('button', { name: 'Registrar recojo' }))

  it('pide quien recoge y su celular, entrega el pedido y lo pasa a "Entregado hoy" con quien se lo llevo', async () => {
    // El pedido entregado sale de "Listo para recojo"; la pestana abierta no cambia sola
    const fetchMock = servidor(comoAcopio({ [RECOJO]: () => [200, copia(DATOS.pedidoEntregado)] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    const dialogo = await abrirDialogo(user)
    await llenar(user, dialogo, '  rosa ancon  perez ', '+51 987 654 321')
    await registrar(user, dialogo)

    expect(await screen.findByText('El pedido MC-00139 se entregó a Rosa Ancón Pérez')).toBeInTheDocument()
    // Se envia lo que escribio el administrador: el servidor decide si coincide
    expect(JSON.parse(llamadas(fetchMock, RECOJO)[0][1].body)).toEqual({ persona: 'rosa ancon  perez', celular: '+51 987 654 321' })
    expect(screen.queryByRole('dialog', { name: /Registrar recojo/ })).not.toBeInTheDocument()

    expect((await panel('Listo para recojo')).getByText('Ningún pedido espera a su comprador.')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Listo para recojo (0)' })).toBeInTheDocument()
    const entregado = await abrirPestana(user, 'Entregado hoy')
    expect(screen.getByRole('tab', { name: 'Entregado hoy (2)' })).toBeInTheDocument()
    expect(entregado.getAllByText('Recogió Rosa Ancón Pérez')).toHaveLength(2)
    expect(entregado.queryByRole('button')).not.toBeInTheDocument()
    // Solo cambio ese pedido: la lista no se volvio a pedir
    expect(llamadas(fetchMock, LISTAR)).toHaveLength(1)
  })

  it('sin nombre o sin celular no llama al servidor y marca el campo que falta', async () => {
    const fetchMock = servidor(comoAcopio())
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    const dialogo = await abrirDialogo(user)
    await registrar(user, dialogo)

    expect(within(dialogo).getByText('Indica quién recoge')).toBeInTheDocument()
    expect(within(dialogo).getByText('Indica el celular de contacto')).toBeInTheDocument()
    expect(within(dialogo).getByLabelText('Nombre de quien recoge')).toBeInvalid()
    expect(llamadas(fetchMock, RECOJO)).toHaveLength(0)
  })

  it('si la persona o el celular no coinciden, lo dice junto a ese campo y deja corregir', async () => {
    let intentos = 0
    servidor(comoAcopio({ [RECOJO]: () => [PERSONA, CELULAR, [200, copia(DATOS.pedidoEntregado)]][intentos++] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    const dialogo = await abrirDialogo(user)
    await llenar(user, dialogo, 'Juan Pérez', '987654321')
    await registrar(user, dialogo)

    // El rechazo no dice cual era el dato correcto
    expect(await within(dialogo).findByText(PERSONA[1].error)).toBeInTheDocument()
    expect(within(dialogo).getByLabelText('Nombre de quien recoge')).toBeInvalid()
    expect(within(dialogo).getByLabelText('Celular de contacto')).toBeValid()

    // Corrige el nombre; ahora es el celular el que no coincide y el error anterior se va
    await user.clear(within(dialogo).getByLabelText('Nombre de quien recoge'))
    await user.type(within(dialogo).getByLabelText('Nombre de quien recoge'), 'Rosa Ancón Pérez')
    await registrar(user, dialogo)
    expect(await within(dialogo).findByText(CELULAR[1].error)).toBeInTheDocument()
    expect(within(dialogo).queryByText(PERSONA[1].error)).not.toBeInTheDocument()

    // Y a la tercera, entrega
    await user.clear(within(dialogo).getByLabelText('Celular de contacto'))
    await user.type(within(dialogo).getByLabelText('Celular de contacto'), '987654321')
    await registrar(user, dialogo)
    expect(await screen.findByText('El pedido MC-00139 se entregó a Rosa Ancón Pérez')).toBeInTheDocument()
    expect(intentos).toBe(3)
  })

  it('un celular con formato invalido o datos rechazados por el servidor se muestran junto al campo', async () => {
    servidor(comoAcopio({
      [RECOJO]: [422, { code: 'CELULAR_INVALIDO', error: 'Ingresa un celular de 9 dígitos que empiece con 9.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    const dialogo = await abrirDialogo(user)
    await llenar(user, dialogo, 'Rosa Ancón Pérez', '12345')
    await registrar(user, dialogo)

    expect(await within(dialogo).findByText('Ingresa un celular de 9 dígitos que empiece con 9.')).toBeInTheDocument()
    expect(within(dialogo).getByLabelText('Celular de contacto')).toBeInvalid()
  })

  it('Enter en un campo tambien registra el recojo', async () => {
    const fetchMock = servidor(comoAcopio({ [RECOJO]: () => [200, copia(DATOS.pedidoEntregado)] }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    const dialogo = await abrirDialogo(user)
    await user.type(within(dialogo).getByLabelText('Nombre de quien recoge'), 'Rosa Ancón Pérez')
    await user.type(within(dialogo).getByLabelText('Celular de contacto'), '987654321{Enter}')

    expect(await screen.findByText('El pedido MC-00139 se entregó a Rosa Ancón Pérez')).toBeInTheDocument()
    expect(llamadas(fetchMock, RECOJO)).toHaveLength(1)
  })

  it('si otra persona ya lo entrego, cierra el dialogo, explica el motivo y actualiza la lista', async () => {
    let lecturas = 0
    servidor(comoAcopio({
      [LISTAR]: () => {
        const datos = copia(DATOS.pedidos)
        if (++lecturas > 1) datos.items[1] = copia(DATOS.pedidoEntregado)
        return [200, datos]
      },
      [RECOJO]: [409, { code: 'ESTADO_INVALIDO', error: 'Este pedido ya fue entregado.' }],
    }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    const dialogo = await abrirDialogo(user)
    await llenar(user, dialogo, 'Rosa Ancón Pérez', '987654321')
    await registrar(user, dialogo)

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No pudimos registrar el recojo')
    expect(alerta).toHaveTextContent('Este pedido ya fue entregado.')
    expect(screen.queryByRole('dialog', { name: /Registrar recojo/ })).not.toBeInTheDocument()
    await waitFor(() => expect(lecturas).toBe(2))
    expect((await abrirPestana(user, 'Entregado hoy')).getAllByText('MC-00139')).toHaveLength(1)
  })

  it('si falla la red lo dice dentro del dialogo y no pierde lo escrito', async () => {
    servidor(comoAcopio({ [RECOJO]: new Error('sin red') }))
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    const dialogo = await abrirDialogo(user)
    await llenar(user, dialogo, 'Rosa Ancón Pérez', '987654321')
    await registrar(user, dialogo)

    expect(await within(dialogo).findByText('No pudimos registrar el recojo')).toBeInTheDocument()
    expect(within(dialogo).getByLabelText('Nombre de quien recoge')).toHaveValue('Rosa Ancón Pérez')
  })

  it('cancelar cierra sin llamar al servidor y el siguiente recojo empieza con los campos vacios', async () => {
    const fetchMock = servidor(comoAcopio())
    const user = userEvent.setup()
    abrirEn('/acopio/pedidos')
    const dialogo = await abrirDialogo(user)
    await llenar(user, dialogo, 'Rosa Ancón Pérez', '987654321')
    await user.click(within(dialogo).getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog', { name: /Registrar recojo/ })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, RECOJO)).toHaveLength(0)
    const otra = await abrirDialogo(user)
    expect(within(otra).getByLabelText('Nombre de quien recoge')).toHaveValue('')
    expect(within(otra).getByLabelText('Celular de contacto')).toHaveValue('')
  })
})
