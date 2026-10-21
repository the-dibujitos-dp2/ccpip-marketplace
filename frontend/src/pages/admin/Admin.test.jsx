import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR, SESION_SUPER, SIN_SESION } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

// Intl separa la moneda con un espacio no separable (U+00A0)
const normal = (texto) => texto.replace(/ /g, ' ')
const RUTA = '/api/admin/tablero'

const comoSuper = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUPER],
  [`GET ${RUTA}`]: () => [200, copia(DATOS.tableroAdmin)],
  ...rutas,
})

function kpi(nombre) {
  return within(screen.getByRole('region', { name: nombre }))
}

describe('SA-01 Tablero de operación del superadministrador (CCPIP-111)', () => {
  it('muestra ventas, comisión y productores del mes con su comparación', async () => {
    servidor(comoSuper())
    abrirEn('/admin')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tablero de operación' })).toBeInTheDocument()
    expect(screen.getByText('Plataforma / Tablero')).toBeInTheDocument()
    await screen.findByRole('region', { name: 'Ventas brutas del mes' })

    expect(normal(kpi('Ventas brutas del mes').getByText(/^S\//).textContent)).toBe('S/ 4,892.00')
    expect(kpi('Ventas brutas del mes').getByText('▲ 22 % vs. setiembre')).toBeInTheDocument()
    expect(normal(kpi('Comisión estimada del mes').getByText(/^S\//).textContent)).toBe('S/ 489.20')
    expect(kpi('Comisión estimada del mes').getByText('10 % vigente')).toBeInTheDocument()
    expect(kpi('Productores activos').getByText('42')).toBeInTheDocument()
    expect(kpi('Productores activos').getByText('+ 6 nuevos este mes')).toBeInTheDocument()
    // Sin calificaciones registradas no se inventa un promedio
    expect(kpi('Calificación promedio').getByText('—')).toBeInTheDocument()
    expect(kpi('Calificación promedio').getByText('Disponible pronto')).toBeInTheDocument()
  })

  it('una caída de ventas se marca en rojo y sin mes anterior no hay porcentaje', async () => {
    const baja = copia(DATOS.tableroAdmin)
    baja.ventas.variacion = -12.5
    servidor(comoSuper({ [`GET ${RUTA}`]: [200, baja] }))
    const { unmount } = abrirEn('/admin')
    const detalle = await screen.findByText('▼ 12.5 % vs. setiembre')
    expect(detalle).toHaveClass('tarjeta-kpi__detalle--error')
    unmount()

    const nuevo = copia(DATOS.tableroAdmin)
    nuevo.ventas.variacion = null
    servidor(comoSuper({ [`GET ${RUTA}`]: [200, nuevo] }))
    abrirEn('/admin')
    expect(await screen.findByText('Sin ventas en setiembre para comparar')).toBeInTheDocument()
  })

  it('ordena ventas por línea y productores, y resume cada punto de acopio', async () => {
    servidor(comoSuper())
    abrirEn('/admin')

    const lineas = within(await screen.findByRole('region', { name: 'Ventas por línea de producto' }))
    const filas = lineas.getAllByRole('listitem')
    expect(filas).toHaveLength(7)
    expect(within(filas[0]).getByText('Cacao y chocolates')).toBeInTheDocument()
    // La barra más alta ocupa todo el ancho; una línea sin ventas queda vacía
    expect(filas[0].querySelector('.admin__relleno')).toHaveStyle({ width: '100%' })
    expect(filas[6].querySelector('.admin__relleno')).toHaveStyle({ width: '0%' })

    const top = within(screen.getByRole('region', { name: 'Productores que más venden' }))
    const puestos = top.getAllByRole('listitem')
    expect(puestos).toHaveLength(4)
    expect(within(puestos[0]).getByText('C.N. Yarina')).toBeInTheDocument()
    expect(within(puestos[0]).getByText('Awajún')).toBeInTheDocument()

    const tabla = within(screen.getByRole('table', { name: 'Estado de los puntos de acopio' }))
    const fila = tabla.getAllByRole('row')[2]
    expect(within(fila).getByText('Centro Regional Ucayali · Pucallpa')).toBeInTheDocument()
    expect(within(fila).getByText('842.5 und')).toBeInTheDocument()
    expect(within(fila).getByText('12')).toBeInTheDocument()
    expect(within(fila).getAllByRole('cell', { name: 'Disponible pronto' })).toHaveLength(2)
  })

  it('sin ventas en el mes lo dice en vez de dejar la lista vacía', async () => {
    const vacio = copia(DATOS.tableroAdmin)
    vacio.productoresTop = []
    servidor(comoSuper({ [`GET ${RUTA}`]: [200, vacio] }))
    abrirEn('/admin')
    expect(await screen.findByText('Aún no hay ventas este mes.')).toBeInTheDocument()
  })

  it('avisa si el tablero no carga', async () => {
    servidor(comoSuper({ [`GET ${RUTA}`]: new Error('red') }))
    abrirEn('/admin')
    expect(await screen.findByText('No pudimos cargar el tablero')).toBeInTheDocument()
  })

  it('el menú ofrece solo los módulos que ya existen', async () => {
    servidor(comoSuper())
    abrirEn('/admin')
    const nav = within(await screen.findByRole('navigation', { name: 'Super administrador' }))
    expect(nav.getAllByRole('link').map((a) => a.textContent)).toEqual(['Tablero', 'Denuncias', 'Comisión', 'Comisión por pedido', 'Parámetros', 'Catálogo maestro', 'Existencias', 'Cuentas', 'Causas de merma'])
    expect(nav.getByRole('link', { name: 'Tablero' })).toHaveAttribute('aria-current', 'page')
  })

  it('sin comisión configurada no estima una con un porcentaje inventado (CCPIP-90)', async () => {
    const sin = copia(DATOS.tableroAdmin)
    sin.comision = { porcentaje: null, estimada: null }
    servidor(comoSuper({ [`GET ${RUTA}`]: [200, sin] }))
    abrirEn('/admin')
    await screen.findByRole('region', { name: 'Comisión estimada del mes' })
    expect(kpi('Comisión estimada del mes').getByText('—')).toBeInTheDocument()
    expect(kpi('Comisión estimada del mes').getByText('Sin comisión configurada')).toBeInTheDocument()
  })

  it('al ingresar, el superadministrador llega a su tablero', async () => {
    let conSesion = false
    servidor(
      comoSuper({
        'GET /api/auth/me': () => (conSesion ? [200, SESION_SUPER] : SIN_SESION),
        'POST /api/auth/login': () => {
          conSesion = true
          return [200, SESION_SUPER]
        },
      }),
    )
    abrirEn('/ingresar')
    await userEvent.type(await screen.findByLabelText('Correo electrónico'), 'sudo@ccpip.test')
    await userEvent.type(screen.getByLabelText('Contraseña'), 'Demo1234!')
    await userEvent.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Tablero de operación' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/admin')
  })

  it('otros roles no ven el tablero ni lo piden al servidor', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_PRODUCTOR], 'GET /api/panel/productor': [200, {}] })
    abrirEn('/admin')
    await screen.findByRole('heading', { level: 1 })
    expect(window.location.pathname).toBe('/inicio')
    expect(llamadas(fetchMock, `GET ${RUTA}`)).toHaveLength(0)
  })
})

describe('SA-08 Acceso restringido (CCPIP-111)', () => {
  it.each([
    ['/productor/productos', 'Tiendas y productos'],
    ['/acopio/pedidos', 'Stock de acopios'],
    ['/carrito', 'Pedidos de consumidores'],
    ['/pedidos/12', 'Pedidos de consumidores'],
  ])('en %s explica que no es de su rol, sin pedir esos datos', async (ruta, seccion) => {
    const fetchMock = servidor(comoSuper())
    abrirEn(ruta)

    expect(await screen.findByRole('heading', { level: 1, name: 'Acceso restringido' })).toBeInTheDocument()
    expect(screen.getByText(`Plataforma / ${seccion}`)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Esta información no está disponible para tu rol' })).toBeInTheDocument()
    const chips = within(screen.getByRole('list', { name: 'Fuera de tu rol' })).getAllByRole('listitem')
    expect(chips.map((c) => c.textContent)).toEqual(['Tiendas y productos', 'Pedidos de consumidores', 'Stock de acopios'])
    // Se queda en la ruta que intentó abrir y no consulta datos que no le corresponden
    expect(window.location.pathname).toBe(ruta)
    const pedidas = fetchMock.mock.calls.map(([url]) => url)
    expect(pedidas.some((u) => /\/api\/(productor|acopio|carrito|pedidos|compra)/.test(u))).toBe(false)
    // Tampoco promete un registro en Auditoría que todavía no existe
    expect(screen.queryByText(/Auditoría/)).not.toBeInTheDocument()
  })

  it('vuelve al tablero', async () => {
    servidor(comoSuper())
    abrirEn('/acopio/movimientos')
    await userEvent.click(await screen.findByRole('link', { name: 'Volver al tablero' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Tablero de operación' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/admin')
  })

  it('los demás roles siguen yendo a su inicio, sin esta pantalla', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION], 'GET /api/panel/consumidor': [200, {}] })
    abrirEn('/productor/productos')
    await screen.findByRole('heading', { level: 1 })
    expect(window.location.pathname).toBe('/inicio')
    expect(screen.queryByText('Acceso restringido')).not.toBeInTheDocument()
  })
})

describe('Superadministrador en inglés (CCPIP-111)', () => {
  it('el tablero y el acceso restringido salen en inglés', async () => {
    await i18n.changeLanguage('en')
    servidor(comoSuper())
    const { unmount } = abrirEn('/admin')
    expect(await screen.findByRole('heading', { level: 1, name: 'Operations dashboard' })).toBeInTheDocument()
    expect(await screen.findByText('▲ 22 % vs. September')).toBeInTheDocument()
    expect(screen.getByText('+ 6 new this month')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Collection points status' })).toBeInTheDocument()
    expect(screen.queryByText(/Ventas|Productores|Disponible/)).not.toBeInTheDocument()
    unmount()

    abrirEn('/carrito')
    expect(await screen.findByRole('heading', { level: 2, name: "This information isn't available for your role" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to dashboard' })).toBeInTheDocument()
  })
})
