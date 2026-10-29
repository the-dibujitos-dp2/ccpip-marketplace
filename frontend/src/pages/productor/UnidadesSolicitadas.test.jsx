import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const PROPIAS = 'GET /api/productor/unidades/solicitudes'
const PEDIR = 'POST /api/productor/unidades/solicitudes'
const ADMIN = '/api/admin/catalogo/unidades/solicitudes'

// Como las arma UnidadesSolicitadasService del backend
const solicitud = (extra = {}) => ({
  id: 4, productorId: 1, productor: 'Comunidad Shipibo Konibo', nombre: 'Atado', justificacion: 'Vendemos por atados de 10 vainas',
  estado: 'PENDIENTE', motivo: null, decididaPor: null, decididaEn: null, unidad: null, creadaEn: '2026-10-29T15:00:00Z', ...extra,
})

const productor = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    'GET /api/productor/panel': () => [200, copia(DATOS.panelProductor)],
    'GET /api/productor/intereses': () => [200, []],
    'GET /api/productor/productos': () => [200, copia(DATOS.productosProductor)],
    [PROPIAS]: [200, []],
    ...rutas,
  })

describe('CCPIP-119 El productor pide una unidad de medida nueva', () => {
  it('la pide desde el editor y la ve en revisión, sin poder usarla todavía', async () => {
    let propias = []
    const fetchMock = productor({
      [PROPIAS]: () => [200, copia(propias)],
      [PEDIR]: () => {
        propias = [solicitud()]
        return [201, solicitud()]
      },
    })
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')

    await user.click(await screen.findByRole('button', { name: '¿No encuentras tu unidad? Pídela a la Cámara' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Pedir una unidad de medida' })
    await user.type(within(dialogo).getByLabelText('Nombre de la unidad'), ' Atado ')
    await user.type(within(dialogo).getByLabelText('¿Para qué la necesitas?'), 'Vendemos por atados de 10 vainas')
    await user.click(within(dialogo).getByRole('button', { name: 'Enviar a la Cámara' }))

    expect(await screen.findByText('Pediste la unidad «Atado».')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, PEDIR)[0][1].body)).toEqual({ nombre: 'Atado', justificacion: 'Vendemos por atados de 10 vainas' })
    const lista = await screen.findByRole('list', { name: 'Tus pedidos de unidad' })
    expect(within(lista).getByText('En revisión')).toBeInTheDocument()
    // Pendiente no aparece entre las unidades que se pueden elegir
    expect(within(screen.getByLabelText('Unidad de medida')).queryByRole('option', { name: 'Atado' })).not.toBeInTheDocument()
  })

  it('pide nombre y para qué la necesita antes de enviar', async () => {
    const fetchMock = productor()
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')

    await user.click(await screen.findByRole('button', { name: '¿No encuentras tu unidad? Pídela a la Cámara' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Pedir una unidad de medida' })
    await user.click(within(dialogo).getByRole('button', { name: 'Enviar a la Cámara' }))
    expect(within(dialogo).getByText('Escribe el nombre de la unidad')).toBeInTheDocument()
    expect(within(dialogo).getByText('Cuéntale a la Cámara para qué la necesitas')).toBeInTheDocument()
    expect(llamadas(fetchMock, PEDIR)).toHaveLength(0)
  })

  it('si la unidad ya existe, lo dice en el diálogo', async () => {
    productor({ [PEDIR]: [409, { code: 'UNIDAD_YA_EXISTE', error: 'La unidad «Kilogramo» ya está en el catálogo: elígela al publicar.' }] })
    const user = userEvent.setup()
    abrirEn('/productor/productos/nuevo')

    await user.click(await screen.findByRole('button', { name: '¿No encuentras tu unidad? Pídela a la Cámara' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Pedir una unidad de medida' })
    await user.type(within(dialogo).getByLabelText('Nombre de la unidad'), 'kilogramo')
    await user.type(within(dialogo).getByLabelText('¿Para qué la necesitas?'), 'Granos')
    await user.click(within(dialogo).getByRole('button', { name: 'Enviar a la Cámara' }))
    expect(await within(dialogo).findByText('La unidad «Kilogramo» ya está en el catálogo: elígela al publicar.')).toBeInTheDocument()
  })

  it('ve lo que decidió la Cámara, con el motivo si la rechazó', async () => {
    productor({
      [PROPIAS]: [200, [
        solicitud({ id: 5, nombre: 'Mazo', estado: 'RECHAZADA', motivo: 'Usen Unidad y escriban «mazo» en la presentación' }),
        solicitud({ estado: 'APROBADA', motivo: 'Sí', unidad: { codigo: 'ATADO', nombre: 'Atado' } }),
      ]],
    })
    abrirEn('/productor/productos/nuevo')

    const lista = await screen.findByRole('list', { name: 'Tus pedidos de unidad' })
    const items = within(lista).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('Rechazada Mazo · motivo: Usen Unidad y escriban «mazo» en la presentación')
    expect(items[1]).toHaveTextContent('Aprobada Atado · ya puedes elegirla')
  })
})

const superadmin = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUPER],
    'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
    'GET /api/admin/autorizaciones': () => [200, copia(DATOS.autorizacionesAdmin)],
    'GET /api/admin/catalogo/lineas': () => [200, copia(DATOS.lineasMaestras)],
    ...rutas,
  })

describe('CCPIP-137 y CCPIP-353 La Cámara decide las unidades que piden los productores', () => {
  it('aprueba una unidad ajustando el nombre y el nombre en inglés', async () => {
    let pendientes = [solicitud()]
    const fetchMock = superadmin({
      [`GET ${ADMIN}?estado=PENDIENTE`]: () => [200, copia(pendientes)],
      [`POST ${ADMIN}/4/aprobar`]: () => {
        pendientes = []
        return [200, solicitud({ estado: 'APROBADA', motivo: 'Se usa en varias comunidades', unidad: { codigo: 'ATADO_DE_10', nombre: 'Atado de 10' } })]
      },
    })
    const user = userEvent.setup()
    abrirEn('/admin/catalogo?vista=unidades')

    const tarjetas = await screen.findByRole('list', { name: 'Pendientes' })
    expect(within(tarjetas).getByText('“Vendemos por atados de 10 vainas”')).toBeInTheDocument()
    expect(within(tarjetas).getByText(/De Comunidad Shipibo Konibo/)).toBeInTheDocument()
    await user.click(within(tarjetas).getByRole('button', { name: 'Aprobar Atado' }))

    const dialogo = await screen.findByRole('dialog', { name: 'Aprobar «Atado»' })
    const nombre = within(dialogo).getByLabelText('Nombre en el catálogo')
    await user.clear(nombre)
    await user.type(nombre, 'Atado de 10')
    await user.type(within(dialogo).getByLabelText('Nombre en inglés (opcional)'), 'Bundle of 10')
    await user.type(within(dialogo).getByLabelText('Motivo'), 'Se usa en varias comunidades')
    await user.click(within(dialogo).getByRole('button', { name: 'Aprobar' }))

    expect(await screen.findByText('«Atado de 10» (ATADO_DE_10) ya está en el catálogo.')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, `POST ${ADMIN}/4/aprobar`)[0][1].body))
      .toEqual({ nombre: 'Atado de 10', nombreEn: 'Bundle of 10', codigo: null, motivo: 'Se usa en varias comunidades' })
    expect(await screen.findByText('No hay pedidos pendientes')).toBeInTheDocument()
  })

  it('rechaza con motivo obligatorio', async () => {
    const fetchMock = superadmin({
      [`GET ${ADMIN}?estado=PENDIENTE`]: () => [200, [solicitud()]],
      [`POST ${ADMIN}/4/rechazar`]: [200, solicitud({ estado: 'RECHAZADA', motivo: 'Usen Unidad' })],
    })
    const user = userEvent.setup()
    abrirEn('/admin/catalogo?vista=unidades')

    await user.click(await screen.findByRole('button', { name: 'Rechazar Atado' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Rechazar «Atado»' })
    await user.click(within(dialogo).getByRole('button', { name: 'Rechazar' }))
    expect(within(dialogo).getByText('Indica el motivo de la decisión')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${ADMIN}/4/rechazar`)).toHaveLength(0)

    await user.type(within(dialogo).getByLabelText('Motivo'), 'Usen Unidad')
    await user.click(within(dialogo).getByRole('button', { name: 'Rechazar' }))
    expect(await screen.findByText('Rechazaste «Atado». El productor verá el motivo.')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, `POST ${ADMIN}/4/rechazar`)[0][1].body)).toEqual({ motivo: 'Usen Unidad' })
  })

  it('muestra las ya decididas con su motivo y quién decidió', async () => {
    superadmin({
      [`GET ${ADMIN}?estado=PENDIENTE`]: [200, []],
      [`GET ${ADMIN}?estado=RECHAZADA`]: [200, [solicitud({ estado: 'RECHAZADA', motivo: 'Usen Unidad', decididaPor: 'Ana Shuñaqui', decididaEn: '2026-10-29T16:00:00Z' })]],
    })
    const user = userEvent.setup()
    abrirEn('/admin/catalogo?vista=unidades')

    await screen.findByText('No hay pedidos pendientes')
    await user.selectOptions(screen.getByLabelText('Mostrar'), 'RECHAZADA')
    const lista = await screen.findByRole('list', { name: 'Rechazadas' })
    expect(within(lista).getByText(/Motivo: Usen Unidad · Ana Shuñaqui/)).toBeInTheDocument()
    expect(within(lista).queryByRole('button', { name: /Aprobar/ })).not.toBeInTheDocument()
  })

  it('si la decisión falla, lo dice en el diálogo', async () => {
    superadmin({
      [`GET ${ADMIN}?estado=PENDIENTE`]: [200, [solicitud()]],
      [`POST ${ADMIN}/4/aprobar`]: [409, { code: 'CODIGO_DUPLICADO', error: 'Ya existe una unidad con el código KG.' }],
    })
    const user = userEvent.setup()
    abrirEn('/admin/catalogo?vista=unidades')

    await user.click(await screen.findByRole('button', { name: 'Aprobar Atado' }))
    const dialogo = await screen.findByRole('dialog', { name: 'Aprobar «Atado»' })
    await user.type(within(dialogo).getByLabelText('Código (opcional)'), 'KG')
    await user.type(within(dialogo).getByLabelText('Motivo'), 'x')
    await user.click(within(dialogo).getByRole('button', { name: 'Aprobar' }))
    await waitFor(() => expect(within(dialogo).getByText('No pudimos guardar la decisión')).toBeInTheDocument())
  })
})
