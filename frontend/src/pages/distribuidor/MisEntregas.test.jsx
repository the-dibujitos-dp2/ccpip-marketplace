import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { llamadas, PUBLICAS, servidor } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const SESION_DISTRIBUIDOR = {
  user: { id: 8, email: 'distribuidor@ccpip.test', fullName: 'Julio Paredes', role: 'DISTRIBUIDOR', roleName: 'Distribuidor' },
  expiresInSeconds: 120,
}

const LIMA = { id: 1, nombre: 'Centro Nacional Lima', distrito: 'Lima', direccion: 'Sede central CCPIP' }
const destino = (distrito, ubigeo) => ({ ubigeo, departamento: 'Lima', provincia: 'Lima', distrito })
const entrega = (numero, distrito, ubigeo, estado, extra = {}) => ({
  numero, origen: LIMA, destino: destino(distrito, ubigeo), estado, asignadaEn: '2026-10-25T15:00:00Z', retiradaEn: null, ...extra,
})

const MIS_ENTREGAS = 'GET /api/distribuidor/pedidos'
const retirar = (numero) => `POST /api/distribuidor/pedidos/${numero}/retirar`
const enCamino = (numero) => `POST /api/distribuidor/pedidos/${numero}/en-camino`

/**
 * El servidor del reparto con estado: lista las entregas del distribuidor y retirar una la deja RETIRADA.
 * {@code respuestas} fija lo que responde retirar una entrega en particular (p. ej. que ya no es suya).
 */
function reparto(entregas, respuestas = {}) {
  const mias = entregas.map((e) => ({ ...e }))
  const rutas = { [MIS_ENTREGAS]: () => [200, mias.map((e) => ({ ...e }))] }
  mias.forEach((e) => {
    rutas[enCamino(e.numero)] = () => {
      Object.assign(e, { estado: 'EN_CAMINO' })
      return [200, { ...e }]
    }
    rutas[retirar(e.numero)] = () => {
      const respuesta = respuestas[e.numero]
      if (respuesta) return typeof respuesta === 'function' ? respuesta() : respuesta
      Object.assign(e, { estado: 'RETIRADA', retiradaEn: '2026-10-25T16:30:00Z' })
      return [200, { ...e }]
    }
  })
  return rutas
}

const comoDistribuidor = (rutas) => ({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_DISTRIBUIDOR], ...rutas })
const tarjeta = (numero) => screen.getByRole('article', { name: new RegExp(`^${numero} ·`) })

describe('CCPIP-110 ubicaciones', () => {
  it('consulta y muestra ubicaciones registradas, referencia opcional, errores reintentables y limpia al cambiar pedido', async () => {
    const rutas = reparto([entrega('MC-11001', 'Lince', '150116', 'ASIGNADA'), entrega('MC-11002', 'Surco', '150140', 'ASIGNADA')])
    let intento = 0
    rutas['GET /api/distribuidor/pedidos/MC-11001/ubicaciones'] = () => (++intento === 1 ? [503, { error: 'fallo' }] : [200, { numero: 'MC-11001', origen: { id: 1, nombre: 'Punto Uno', distrito: 'Lima', direccion: 'Calle Uno' }, destino: { territorio: { ubigeo: '150116', departamento: 'Lima', provincia: 'Lima', distrito: 'Lince' }, direccion: 'Jr. Dos', referencia: null } }])
    rutas['GET /api/distribuidor/pedidos/MC-11002/ubicaciones'] = () => [200, { numero: 'MC-11002', origen: { id: 1, nombre: 'Punto Dos', distrito: 'Lima', direccion: 'Calle Tres' }, destino: { territorio: { ubigeo: '150140', departamento: 'Lima', provincia: 'Lima', distrito: 'Surco' }, direccion: 'Av. Cuatro', referencia: 'Puerta azul' } }]
    const fetchMock = servidor(comoDistribuidor(rutas)); const user = userEvent.setup(); abrirEn('/distribuidor/entregas')
    const card = await screen.findByRole('article', { name: /^MC-11001/ }); await user.click(within(card).getByRole('button', { name: 'Ver ubicaciones' }))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent('No pudimos cargar las ubicaciones')
    await user.click(screen.getByRole('button', { name: 'Reintentar' }))
    const dlg = await screen.findByRole('dialog', { name: 'Ubicaciones del pedido MC-11001' }); expect(dlg).toHaveTextContent('Calle Uno'); expect(dlg).toHaveTextContent('Jr. Dos'); expect(dlg).not.toHaveTextContent('Puerta azul')
    await user.click(within(dlg).getByRole('button', { name: 'Cerrar' })); await user.click(within(await screen.findByRole('article', { name: /^MC-11002/ })).getByRole('button', { name: 'Ver ubicaciones' }))
    expect(await screen.findByRole('dialog', { name: 'Ubicaciones del pedido MC-11002' })).toHaveTextContent('Puerta azul')
    expect(llamadas(fetchMock, 'GET /api/distribuidor/pedidos/MC-11001/ubicaciones')).toHaveLength(2)
  })
})

describe('DW-03 Mis entregas (CCPIP-317)', () => {
  it('muestra cada entrega en curso con el punto donde recogerla, su destino, su estado y sus pasos', async () => {
    servidor(comoDistribuidor(reparto([
      entrega('MC-00139', 'San Isidro', '150131', 'ASIGNADA'),
      entrega('MC-00144', 'Miraflores', '150122', 'RETIRADA', { retiradaEn: '2026-10-25T16:00:00Z' }),
    ])))
    abrirEn('/distribuidor/entregas')

    expect(await screen.findByText('2 entregas en curso')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Mis entregas', level: 1 })).toBeInTheDocument()

    const porRecoger = tarjeta('MC-00139')
    expect(within(porRecoger).getByRole('heading', { name: 'MC-00139 · San Isidro' })).toBeInTheDocument()
    expect(within(porRecoger).getByText('Por recoger')).toBeInTheDocument()
    expect(within(porRecoger).getByText(/Recoger en Centro Nacional Lima/)).toHaveTextContent('Sede central CCPIP, Lima')
    expect(within(porRecoger).getByText('Destino en Lima, Lima')).toBeInTheDocument()
    expect(within(porRecoger).getByText(/^Lo tomaste el/)).toBeInTheDocument()
    expect(within(porRecoger).getByRole('list', { name: 'Avance de la entrega MC-00139' })).toBeInTheDocument()
    expect(within(porRecoger).getByRole('button', { name: 'Retiré el pedido' })).toBeEnabled()

    // Una entrega ya recogida tiene su primer paso hecho y no se vuelve a retirar
    const recogida = tarjeta('MC-00144')
    expect(within(recogida).getAllByText('Recogido')[0]).toBeInTheDocument()
    expect(within(recogida).getByText(/^Lo retiraste del punto el/)).toBeInTheDocument()
    expect(recogida.querySelector('[aria-current="step"]')).toHaveTextContent('En camino')
    expect(porRecoger.querySelector('[aria-current="step"]')).toHaveTextContent('Recogido')
    expect(within(recogida).queryByRole('button', { name: 'Retiré el pedido' })).not.toBeInTheDocument()

    // La navegacion marca Mis entregas
    expect(screen.getByRole('link', { name: 'Mis entregas' })).toHaveAttribute('aria-current', 'page')
  })

  it('las entregadas hoy van en su tabla, aparte de las que están en curso', async () => {
    servidor(comoDistribuidor(reparto([
      entrega('MC-00139', 'San Isidro', '150131', 'ASIGNADA'),
      entrega('MC-00131', 'Lince', '150116', 'ENTREGADA', { retiradaEn: '2026-10-25T15:30:00Z' }),
    ])))
    abrirEn('/distribuidor/entregas')

    const hoy = await screen.findByRole('table', { name: 'Entregados hoy' })
    expect(within(hoy).getByRole('rowheader', { name: 'MC-00131' })).toBeInTheDocument()
    expect(within(hoy).getByText('Lince')).toBeInTheDocument()
    expect(within(hoy).getByText('Entregado')).toBeInTheDocument()
    expect(await screen.findByText('1 entrega en curso')).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: /MC-00131/ })).not.toBeInTheDocument()
  })

  it('sin entregas lleva a los pedidos disponibles', async () => {
    servidor(comoDistribuidor({ ...reparto([]), 'GET /api/distribuidor/pedidos/disponibles': [200, { items: [], siguiente: null, total: 0, puntos: [] }] }))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')

    expect(await screen.findByText('No tienes entregas en curso')).toBeInTheDocument()
    expect(screen.getByText('Todavía no entregas pedidos hoy.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    await usuario.click(screen.getByRole('button', { name: 'Ver pedidos disponibles' }))
    expect(await screen.findByRole('heading', { name: 'Pedidos disponibles', level: 1 })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/distribuidor/pedidos')
  })

  it('si no carga, lo dice y deja reintentar', async () => {
    let intentos = 0
    const rutas = reparto([entrega('MC-00139', 'San Isidro', '150131', 'ASIGNADA')])
    const listar = rutas[MIS_ENTREGAS]
    rutas[MIS_ENTREGAS] = () => (++intentos === 1 ? new Error('sin red') : listar())
    servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')

    expect(await screen.findByText('No pudimos cargar tus entregas')).toBeInTheDocument()
    await usuario.click(screen.getByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByRole('heading', { name: 'MC-00139 · San Isidro' })).toBeInTheDocument()
  })
})

describe('CCPIP-318 registrar en camino', () => {
  it('registra el avance desde RETIRADA, refresca badge y pasos; durante la petición bloquea acciones', async () => {
    let soltar
    const rutas = reparto([entrega('MC-00318', 'Lince', '150116', 'RETIRADA', { retiradaEn: '2026-10-25T16:00:00Z' })])
    const enCaminoBien = rutas[enCamino('MC-00318')]
    rutas[enCamino('MC-00318')] = () => new Promise((resolver) => { soltar = () => resolver(enCaminoBien()) })
    const fetchMock = servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')
    const card = await screen.findByRole('article', { name: /^MC-00318 ·/ })
    const boton = within(card).getByRole('button', { name: 'Registrar en camino' })
    await usuario.click(boton)
    const registrando = within(card).getByRole('button', { name: 'Registrando…' })
    expect(registrando).toBeDisabled()
    expect(registrando).toHaveAttribute('aria-busy', 'true')
    expect(registrando.querySelector('.boton__giro')).toBeInTheDocument()
    expect(within(card).queryByRole('button', { name: 'Retiré el pedido' })).not.toBeInTheDocument()
    expect(llamadas(fetchMock, enCamino('MC-00318'))).toHaveLength(1)
    soltar()
    expect(await screen.findByText('Registraste que el pedido MC-00318 está en camino.')).toBeInTheDocument()
  })
})

  it.each([409, 503])('si registrar en camino responde %s, conserva estado y permite reintentar', async (status) => {
    let intentos = 0
    const rutas = reparto([entrega('MC-00319', 'Lince', '150116', 'RETIRADA', { retiradaEn: '2026-10-25T16:00:00Z' })])
    const bien = rutas[enCamino('MC-00319')]
    rutas[enCamino('MC-00319')] = () => (++intentos === 1 ? [status, { error: 'falló actualización' }] : bien())
    const fetchMock = servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')
    const card = await screen.findByRole('article', { name: /^MC-00319 ·/ })
    await usuario.click(within(card).getByRole('button', { name: 'Registrar en camino' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos registrar que el pedido MC-00319 está en camino')
    expect(within(card).getByText('Recogido', { selector: '.badge-estado' })).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Registrar en camino' })).toBeEnabled()
    expect(llamadas(fetchMock, enCamino('MC-00319'))).toHaveLength(1)
    await usuario.click(within(card).getByRole('button', { name: 'Registrar en camino' }))
    expect(await screen.findByText('Registraste que el pedido MC-00319 está en camino.')).toBeInTheDocument()
    expect(llamadas(fetchMock, enCamino('MC-00319'))).toHaveLength(2)
  })

  it.each(['ASIGNADA', 'EN_CAMINO', 'ENTREGADA'])('no ofrece registrar en camino para estado %s', async (estado) => {
    servidor(comoDistribuidor(reparto([entrega('MC-00320', 'Lince', '150116', estado)])))
    abrirEn('/distribuidor/entregas')
    if (estado === 'ENTREGADA') {
      const tabla = await screen.findByRole('table', { name: 'Entregados hoy' })
      expect(within(tabla).getByRole('rowheader', { name: 'MC-00320' })).toBeInTheDocument()
      expect(within(tabla).queryByRole('button', { name: /Registrar en camino/ })).not.toBeInTheDocument()
    } else {
      const card = await screen.findByRole('article', { name: /^MC-00320 ·/ })
      expect(within(card).queryByRole('button', { name: /Registrar en camino/ })).not.toBeInTheDocument()
    }
  })

describe('DW-03 Retirar el pedido del punto de acopio (CCPIP-109)', () => {
  it('"Retiré el pedido" registra el retiro: la entrega queda recogida, con su fecha', async () => {
    const fetchMock = servidor(comoDistribuidor(reparto([entrega('MC-00139', 'San Isidro', '150131', 'ASIGNADA')])))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')
    await screen.findByText('MC-00139 · San Isidro')

    await usuario.click(within(tarjeta('MC-00139')).getByRole('button', { name: 'Retiré el pedido' }))

    expect(await screen.findByText('Registraste el retiro del pedido MC-00139.')).toBeInTheDocument()
    expect(llamadas(fetchMock, retirar('MC-00139'))).toHaveLength(1)
    await waitFor(() => expect(within(tarjeta('MC-00139')).getByText(/^Lo retiraste del punto el/)).toBeInTheDocument())
    expect(within(tarjeta('MC-00139')).getByText('Recogido', { selector: '.badge-estado' })).toBeInTheDocument()
    expect(within(tarjeta('MC-00139')).queryByRole('button', { name: 'Retiré el pedido' })).not.toBeInTheDocument()
  })

  it('mientras registra, el botón lo dice y los demás retiros quedan bloqueados', async () => {
    let soltar
    const rutas = reparto([
      entrega('MC-00139', 'San Isidro', '150131', 'ASIGNADA'),
      entrega('MC-00144', 'Miraflores', '150122', 'ASIGNADA'),
    ])
    const retirarBien = rutas[retirar('MC-00139')]
    rutas[retirar('MC-00139')] = () => new Promise((resolver) => { soltar = () => resolver(retirarBien()) })
    servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')
    await screen.findByText('MC-00139 · San Isidro')

    await usuario.click(within(tarjeta('MC-00139')).getByRole('button', { name: 'Retiré el pedido' }))

    const registrando = within(tarjeta('MC-00139')).getByRole('button', { name: 'Registrando…' })
    expect(registrando).toBeDisabled()
    expect(within(tarjeta('MC-00144')).getByRole('button', { name: 'Retiré el pedido' })).toBeDisabled()
    soltar()
    expect(await screen.findByText('Registraste el retiro del pedido MC-00139.')).toBeInTheDocument()
    expect(within(tarjeta('MC-00144')).getByRole('button', { name: 'Retiré el pedido' })).toBeEnabled()
  })

  it('si la entrega ya no es suya, avisa con el motivo y la lista se pone al día', async () => {
    const entregas = [entrega('MC-00139', 'San Isidro', '150131', 'ASIGNADA')]
    const rutas = reparto(entregas, {
      'MC-00139': () => {
        // Se la reasignaron: ya no esta en su lista
        entregas.length = 0
        return [409, { code: 'PEDIDO_NO_ASIGNADO', error: 'Este pedido no está asignado a ti.' }]
      },
    })
    rutas[MIS_ENTREGAS] = () => [200, entregas]
    servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')
    await screen.findByText('MC-00139 · San Isidro')

    await usuario.click(screen.getByRole('button', { name: 'Retiré el pedido' }))

    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('No pudimos registrar el retiro del pedido MC-00139')
    expect(alerta).toHaveTextContent('Este pedido no está asignado a ti.')
    expect(await screen.findByText('No tienes entregas en curso')).toBeInTheDocument()
  })

  it('si falla la red, lo dice y se puede volver a intentar', async () => {
    let intentos = 0
    const rutas = reparto([entrega('MC-00139', 'San Isidro', '150131', 'ASIGNADA')])
    const retirarBien = rutas[retirar('MC-00139')]
    rutas[retirar('MC-00139')] = () => (++intentos === 1 ? new Error('sin red') : retirarBien())
    servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')
    await screen.findByText('MC-00139 · San Isidro')

    await usuario.click(screen.getByRole('button', { name: 'Retiré el pedido' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos registrar el retiro del pedido MC-00139')
    await usuario.click(await screen.findByRole('button', { name: 'Retiré el pedido' }))
    expect(await screen.findByText('Registraste el retiro del pedido MC-00139.')).toBeInTheDocument()
    expect(intentos).toBe(2)
  })
})


describe('CCPIP-319 registrar entrega', () => {
  it('registra la entrega en camino y la muestra entre las entregadas hoy tras refrescar', async () => {
    let soltar
    const datos = [entrega('MC-00319', 'Lince', '150116', 'EN_CAMINO')]
    const rutas = reparto(datos)
    rutas[MIS_ENTREGAS] = () => [200, datos.map((e) => ({ ...e }))]
    const endpoint = 'POST /api/distribuidor/pedidos/MC-00319/entregar'
    const entregarBien = () => { Object.assign(datos[0], { estado: 'ENTREGADA' }); return [200, { ...datos[0] }] }
    rutas[endpoint] = () => new Promise((resolver) => { soltar = () => resolver(entregarBien()) })
    const fetchMock = servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')
    const card = await screen.findByRole('article', { name: /^MC-00319 ·/ })
    await usuario.click(within(card).getByRole('button', { name: 'Registrar entrega' }))
    const registrando = within(card).getByRole('button', { name: 'Registrando…' })
    expect(registrando).toBeDisabled()
    expect(registrando).toHaveAttribute('aria-busy', 'true')
    expect(registrando.querySelector('.boton__giro')).toBeInTheDocument()
    expect(llamadas(fetchMock, endpoint)).toHaveLength(1)
    soltar()
    expect(await screen.findByText('Registraste la entrega del pedido MC-00319.')).toBeInTheDocument()
    const tabla = await screen.findByRole('table', { name: 'Entregados hoy' })
    expect(within(tabla).getByRole('rowheader', { name: 'MC-00319' })).toBeInTheDocument()
  })

  it.each([409, 503])('si registrar entrega responde %s, conserva el estado y permite reintentar', async (status) => {
    let intentos = 0
    const rutas = reparto([entrega('MC-00319', 'Lince', '150116', 'EN_CAMINO')])
    const endpoint = 'POST /api/distribuidor/pedidos/MC-00319/entregar'
    rutas[endpoint] = () => (++intentos === 1 ? [status, { error: 'No se pudo completar' }] : [200, {}])
    const fetchMock = servidor(comoDistribuidor(rutas))
    const usuario = userEvent.setup()
    abrirEn('/distribuidor/entregas')
    const card = await screen.findByRole('article', { name: /^MC-00319 ·/ })
    await usuario.click(within(card).getByRole('button', { name: 'Registrar entrega' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos registrar la entrega del pedido MC-00319')
    expect(within(card).getByText('En camino', { selector: '.badge-estado' })).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Registrar entrega' })).toBeEnabled()
    expect(llamadas(fetchMock, endpoint)).toHaveLength(1)
  })
})
