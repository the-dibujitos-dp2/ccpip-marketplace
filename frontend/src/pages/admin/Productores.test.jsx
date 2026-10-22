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

const LISTA = '/api/admin/productores'
const FICHA = `${LISTA}/1`

const comoSub = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_SUB],
    [`GET ${LISTA}`]: () => [200, copia(DATOS.productoresAdmin)],
    [`GET ${FICHA}`]: () => [200, copia(DATOS.fichaProductorAdmin)],
    ...rutas,
  })

/** La ficha que devolveria el servidor tras una decision sobre el productor. */
function tras(decision, motivo, cambios) {
  const f = { ...copia(DATOS.fichaProductorAdmin), ...cambios }
  f.decisiones = [{ id: 5, decision, motivo, decididoPor: 'Lucía Paredes', decididoEn: '2026-10-17T17:00:00Z', alcance: 2 }]
  return f
}

const decision = () => within(screen.getByRole('complementary', { name: 'Decisión' }))
const decisionCargada = async (nombre = 'Decisión') => within(await screen.findByRole('complementary', { name: nombre }))
const producto = (nombre) => within(screen.getByText(nombre).closest('li'))

afterEach(async () => {
  await i18n.changeLanguage('es')
})

describe('Productores y productos autorizados (CCPIP-292)', () => {
  it('lista los productores con su tienda, su venta y sus autorizaciones', async () => {
    comoSub()
    abrirEn('/admin/productores')
    expect(await screen.findByRole('heading', { level: 1, name: 'Productores y productos autorizados' })).toBeInTheDocument()
    const filas = within(await screen.findByRole('table', { name: 'Productores y productos autorizados' })).getAllByRole('row')
    expect(filas).toHaveLength(3)
    const nii = within(filas[1])
    expect(nii.getByText('Asoc. Nii Joi')).toBeInTheDocument()
    expect(nii.getByText('3 de 4 autorizados')).toBeInTheDocument()
    expect(nii.getByText('1 en revisión')).toBeInTheDocument()
    expect(nii.getByText('Tienda habilitada')).toBeInTheDocument()
    expect(within(filas[2]).getByText('Tienda sin habilitar')).toBeInTheDocument()

    const nav = within(screen.getByRole('navigation', { name: 'Sub administrador' }))
    expect(nav.getByRole('link', { name: 'Productores' })).toHaveAttribute('aria-current', 'page')

    await userEvent.click(nii.getByRole('link', { name: 'Ver Asoc. Nii Joi' }))
    expect(window.location.pathname).toBe('/admin/productores/1')
    expect(await screen.findByRole('heading', { level: 1, name: 'Asoc. Nii Joi' })).toBeInTheDocument()
  })

  it('la ficha muestra cada producto con su autorización, sus líneas y si se vende', async () => {
    comoSub()
    abrirEn('/admin/productores/1')
    expect(await screen.findByRole('heading', { level: 2, name: 'Productos que puede vender (4)' })).toBeInTheDocument()
    expect(producto('Aceite de ungurahui 250 ml').getByText('Autorizado')).toBeInTheDocument()
    expect(producto('Aceite de ungurahui 250 ml').getByText('Se vende')).toBeInTheDocument()

    // Una linea nueva no extiende el permiso
    const camu = producto('Camu camu en polvo 150 g')
    expect(camu.getByText('Líneas: Superfrutos, Café · autorizadas: Superfrutos')).toBeInTheDocument()
    expect(camu.getByText('Línea sin autorizar')).toBeInTheDocument()
    expect(camu.getByText('hasta 31/03/2027')).toBeInTheDocument()
    expect(camu.getByText('Venta pausada')).toBeInTheDocument()
    expect(camu.getByRole('link', { name: 'Ver expediente de Camu camu en polvo 150 g' })).toHaveAttribute('href', '/admin/autorizaciones/41')

    expect(producto('Textil bordado shipibo').getByText('Revocado')).toBeInTheDocument()
    expect(producto('Miel de abeja nativa').getByText('Sin solicitud')).toBeInTheDocument()
    expect(producto('Miel de abeja nativa').queryByRole('link')).not.toBeInTheDocument()
  })

  it('el superadministrador no gestiona productores: vuelve a su tablero', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_SUPER],
      'GET /api/admin/tablero': () => [200, copia(DATOS.tableroAdmin)],
    })
    abrirEn('/admin/productores/1')
    expect(await screen.findByRole('heading', { level: 1, name: 'Tablero de operación' })).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => url.startsWith(LISTA))).toBe(false)
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    comoSub()
    abrirEn('/admin/productores/1')
    expect(await screen.findByRole('heading', { level: 2, name: 'Products they can sell (4)' })).toBeInTheDocument()
    expect(screen.getByText('Unauthorized line')).toBeInTheDocument()
    expect((await decisionCargada('Decision')).getByRole('button', { name: "Revoke producer's sale" })).toBeInTheDocument()
  })
})

describe('Revocar la venta de un productor (CCPIP-330)', () => {
  it('pide motivo, confirma y deja todos sus productos en venta pausada', async () => {
    const motivo = 'Incumplimiento grave del reglamento'
    const fetchMock = comoSub({
      [`POST ${FICHA}/revocar`]: () => [200, tras('REVOCAR_VENTA', motivo, { ventaRevocada: true })],
    })
    abrirEn('/admin/productores/1')
    await userEvent.click(await (await decisionCargada()).findByRole('button', { name: 'Revocar venta del productor' }))
    expect(decision().getByText('Escribe el motivo de la decisión')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await userEvent.type(decision().getByLabelText('Motivo de la decisión'), motivo)
    await userEvent.click(decision().getByRole('button', { name: 'Revocar venta del productor' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Revocar la venta de Asoc. Nii Joi' })).toBeInTheDocument()
    expect(dialogo.getByText(/Los pedidos confirmados se atienden igual/)).toBeInTheDocument()
    await userEvent.click(dialogo.getByRole('button', { name: 'Revocar venta' }))

    expect(await screen.findByText('Revocaste la venta del productor. Sus productos quedaron en venta pausada.')).toBeInTheDocument()
    const [[, opciones]] = llamadas(fetchMock, `POST ${FICHA}/revocar`)
    expect(JSON.parse(opciones.body)).toEqual({ motivo })
    expect(screen.getByText('Venta revocada', { selector: '.productores__estado *' })).toBeInTheDocument()
    const historial = within(screen.getByRole('region', { name: 'Historial de decisiones' }))
    expect(historial.getByText(`«${motivo}»`)).toBeInTheDocument()
    expect(historial.getByText(/Lucía Paredes · .* · 2 productos autorizados alcanzados/)).toBeInTheDocument()
    // Ya revocada, solo se puede restituir
    expect(decision().queryByRole('button', { name: 'Revocar venta del productor' })).not.toBeInTheDocument()
    expect(decision().getByRole('button', { name: 'Restituir venta' })).toBeInTheDocument()
  })

  it('restituye con motivo y muestra el error del servidor si ya no estaba revocada', async () => {
    const revocada = { ...copia(DATOS.fichaProductorAdmin), ventaRevocada: true }
    comoSub({
      [`GET ${FICHA}`]: () => [200, copia(revocada)],
      [`POST ${FICHA}/restituir`]: [409, { code: 'VENTA_NO_REVOCADA', error: 'La venta de este productor no está revocada.' }],
    })
    abrirEn('/admin/productores/1')
    await userEvent.type(await (await decisionCargada()).findByLabelText('Motivo de la decisión'), 'Subsanó las observaciones')
    await userEvent.click(decision().getByRole('button', { name: 'Restituir venta' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Restituir venta' }))
    expect(await screen.findByText('La venta de este productor no está revocada.')).toBeInTheDocument()
  })
})

describe('Bloquear las ventas de un productor (CCPIP-85, CCPIP-322)', () => {
  it('bloquea con motivo desde la ficha, sin tocar la revocación', async () => {
    const motivo = 'Denuncias reiteradas sin respuesta'
    const fetchMock = comoSub({
      [`POST ${FICHA}/bloquear`]: () => [200, tras('BLOQUEAR_VENTA', motivo, { ventaBloqueada: true })],
    })
    abrirEn('/admin/productores/1')
    await userEvent.type(await (await decisionCargada()).findByLabelText('Motivo de la decisión'), motivo)
    await userEvent.click(decision().getByRole('button', { name: 'Bloquear productor' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Bloquear a Asoc. Nii Joi' })).toBeInTheDocument()
    expect(dialogo.getByText(/los pedidos en curso se mantienen/)).toBeInTheDocument()
    await userEvent.click(dialogo.getByRole('button', { name: 'Bloquear productor' }))

    expect(await screen.findByText('Bloqueaste al productor. Sus productos quedaron en venta pausada.')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, `POST ${FICHA}/bloquear`)[0][1].body)).toEqual({ motivo })
    expect(screen.getByText('Venta bloqueada', { selector: '.productores__estado *' })).toBeInTheDocument()
    expect(screen.queryByText('Venta vigente', { selector: '.productores__estado *' })).not.toBeInTheDocument()
    const historial = within(screen.getByRole('region', { name: 'Historial de decisiones' }))
    expect(historial.getByText('Venta bloqueada')).toBeInTheDocument()
    expect(historial.getByText(`«${motivo}»`)).toBeInTheDocument()
    expect(decision().getByText(/Sus ventas están bloqueadas/)).toBeInTheDocument()
    // Ya bloqueado se desbloquea; la revocacion sigue disponible aparte
    expect(decision().queryByRole('button', { name: 'Bloquear productor' })).not.toBeInTheDocument()
    expect(decision().getByRole('button', { name: 'Desbloquear productor' })).toBeInTheDocument()
    expect(decision().getByRole('button', { name: 'Revocar venta del productor' })).toBeInTheDocument()
  })

  it('desbloquea con motivo y la lista ya no lo marca', async () => {
    const bloqueada = { ...copia(DATOS.fichaProductorAdmin), ventaBloqueada: true, ventaRevocada: true }
    const fetchMock = comoSub({
      [`GET ${FICHA}`]: () => [200, copia(bloqueada)],
      [`POST ${FICHA}/desbloquear`]: () => [200, tras('DESBLOQUEAR_VENTA', 'Descargo aceptado', { ventaBloqueada: false, ventaRevocada: true })],
    })
    abrirEn('/admin/productores/1')
    const panel = await decisionCargada()
    // Revocada y bloqueada a la vez: se explican las dos
    expect(panel.getByText(/Su venta está revocada/)).toBeInTheDocument()
    expect(panel.getByText(/Sus ventas están bloqueadas/)).toBeInTheDocument()
    await userEvent.type(panel.getByLabelText('Motivo de la decisión'), 'Descargo aceptado')
    await userEvent.click(panel.getByRole('button', { name: 'Desbloquear productor' }))
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Desbloquear productor' }))
    expect(await screen.findByText('Desbloqueaste al productor.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${FICHA}/desbloquear`)).toHaveLength(1)
    expect(within(screen.getByRole('region', { name: 'Historial de decisiones' })).getByText('Venta desbloqueada')).toBeInTheDocument()
    // Desbloquear no restituye la venta revocada
    expect(decision().getByRole('button', { name: 'Restituir venta' })).toBeInTheDocument()
  })

  it('la lista marca a los productores con ventas bloqueadas', async () => {
    const lista = copia(DATOS.productoresAdmin)
    lista[0].ventaBloqueada = true
    comoSub({ [`GET ${LISTA}`]: () => [200, lista] })
    abrirEn('/admin/productores')
    const filas = within(await screen.findByRole('table', { name: 'Productores y productos autorizados' })).getAllByRole('row')
    expect(within(filas[1]).getByText('Venta bloqueada')).toBeInTheDocument()
    expect(within(filas[2]).queryByText('Venta bloqueada')).not.toBeInTheDocument()
  })
})

describe('Habilitar la tienda (CCPIP-291)', () => {
  it('la Cámara habilita la tienda de un productor con motivo', async () => {
    const sinHabilitar = { ...copia(DATOS.fichaProductorAdmin), tiendaHabilitada: false, puedeHabilitarTienda: true }
    const fetchMock = comoSub({
      [`GET ${FICHA}`]: () => [200, copia(sinHabilitar)],
      [`POST ${FICHA}/habilitar-tienda`]: () => [200, tras('HABILITAR_TIENDA', 'Comunidad incorporada', { tiendaHabilitada: true })],
    })
    abrirEn('/admin/productores/1')
    expect(await screen.findByText('Tienda sin habilitar')).toBeInTheDocument()
    await userEvent.type(decision().getByLabelText('Motivo de la decisión'), 'Comunidad incorporada')
    await userEvent.click(decision().getByRole('button', { name: 'Habilitar tienda' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByText(/Cada producto sigue necesitando su propia autorización/)).toBeInTheDocument()
    await userEvent.click(dialogo.getByRole('button', { name: 'Habilitar tienda' }))

    expect(await screen.findByText('Habilitaste la tienda. Avisamos al productor.')).toBeInTheDocument()
    expect(llamadas(fetchMock, `POST ${FICHA}/habilitar-tienda`)).toHaveLength(1)
    expect(screen.getByText('Tienda habilitada', { selector: '.productores__estado *' })).toBeInTheDocument()
    expect(decision().queryByRole('button', { name: 'Habilitar tienda' })).not.toBeInTheDocument()
  })

  it('si el productor no existe lo dice y ofrece volver', async () => {
    comoSub({ [`GET ${FICHA}`]: [404, { code: 'PRODUCTOR_NO_ENCONTRADO', error: 'El productor no existe.' }] })
    abrirEn('/admin/productores/1')
    expect(await screen.findByText('El productor no existe.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Volver a productores' })).toHaveAttribute('href', '/admin/productores')
  })
})
