import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const EXPEDIENTE = '/api/admin/denuncias/productores/2'
const comoSub = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION_SUB],
  'GET /api/admin/denuncias': () => [200, copia(DATOS.denunciasAdmin)],
  [`GET ${EXPEDIENTE}`]: () => [200, copia(DATOS.expedienteDenuncias)],
  ...rutas,
})

describe('Denuncias contra productores (CCPIP-84)', () => {
  it('la bandeja lista productores por cantidad de denuncias, separada del tablero', async () => {
    servidor(comoSub())
    const user = userEvent.setup()
    abrirEn('/admin/denuncias')
    expect(await screen.findByRole('heading', { level: 1, name: 'Denuncias contra productores' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Recibidas' })).toHaveTextContent('2')
    const filas = within(screen.getByRole('table', { name: 'Productores con denuncias' })).getAllByRole('row').slice(1)
    expect(filas.map((f) => within(f).getAllByRole('cell')[0].querySelector('.label-m').textContent)).toEqual(['C.N. Yarina', 'Asoc. Nii Joi'])
    expect(within(filas[0]).getAllByRole('cell')[1]).toHaveTextContent('3')
    await user.click(within(filas[0]).getByRole('link', { name: 'Ver historial de denuncias de C.N. Yarina' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'C.N. Yarina' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/admin/denuncias/productores/2')
  })

  it('el expediente muestra el historial con quién dio cada paso, sin nombrar al denunciante', async () => {
    servidor(comoSub())
    abrirEn('/admin/denuncias/productores/2')
    const cerrada = within(await screen.findByRole('article', { name: 'Denuncia 17: Contra la comunidad' }))
    const pasos = within(cerrada.getByRole('list', { name: 'Historial de la denuncia 17' })).getAllByRole('listitem')
    expect(pasos).toHaveLength(3)
    expect(pasos[0]).toHaveTextContent('Registrada · Comprador (identidad protegida)')
    expect(pasos[1]).toHaveTextContent('Respuesta del productor · C.N. Yarina')
    expect(pasos[2]).toHaveTextContent('Pasa a Resuelta · Lucía Paredes — El productor corrigió la oferta.')
    expect(cerrada.queryByRole('form')).not.toBeInTheDocument()
    const abierta = within(screen.getByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' }))
    expect(abierta.getByText('Compra verificada')).toBeInTheDocument()
    expect(abierta.getByText('No parece cacao de la comunidad. Llámame al [teléfono oculto].')).toBeInTheDocument()
  })

  it('el sub administrador cambia el estado con una nota', async () => {
    const enRevision = copia(DATOS.expedienteDenuncias.denuncias[0])
    enRevision.estado = 'EN_REVISION'
    enRevision.siguientes = ['RESUELTA', 'DESESTIMADA']
    enRevision.historial.push({ tipo: 'CAMBIO_ESTADO', estado: 'EN_REVISION', rol: 'SUB_ADMIN', responsable: 'Lucía Paredes', nota: 'Pedimos fotos del lote.', registradoEn: '2026-10-16T14:00:00Z' })
    const fetchMock = servidor(comoSub({ 'POST /api/admin/denuncias/21/estado': [200, enRevision] }))
    const user = userEvent.setup()
    abrirEn('/admin/denuncias/productores/2')
    const form = within(await screen.findByRole('form', { name: 'Gestionar la denuncia 21' }))
    await user.click(form.getByRole('button', { name: 'Guardar cambio' }))
    expect(form.getByText('Elige el nuevo estado y escribe una nota (al menos 5 caracteres).')).toBeInTheDocument()
    await user.selectOptions(form.getByLabelText('Nuevo estado'), 'En revisión')
    await user.type(form.getByLabelText('Nota'), 'Pedimos fotos del lote.')
    await user.click(form.getByRole('button', { name: 'Guardar cambio' }))
    const abierta = within(await screen.findByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' }))
    expect(await abierta.findByText('En revisión', { selector: '.badge-estado' })).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, 'POST /api/admin/denuncias/21/estado')[0][1].body))
      .toEqual({ estado: 'EN_REVISION', nota: 'Pedimos fotos del lote.' })
    expect(screen.getByText('2 denuncias · 1 abiertas')).toBeInTheDocument()
  })

  it('el superadministrador consulta sin gestionar', async () => {
    servidor(comoSub({ 'GET /api/auth/me': [200, SESION_SUPER], 'GET /api/admin/tablero': [200, copia(DATOS.tableroAdmin)] }))
    abrirEn('/admin/denuncias/productores/2')
    expect(await screen.findByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' })).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Gestionar la denuncia 21' })).not.toBeInTheDocument()
  })

  it('el superadministrador no bloquea ventas', async () => {
    servidor(comoSub({ 'GET /api/auth/me': [200, SESION_SUPER], 'GET /api/admin/tablero': [200, copia(DATOS.tableroAdmin)] }))
    abrirEn('/admin/denuncias/productores/2')
    expect(await screen.findByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Bloquear productor' })).not.toBeInTheDocument()
  })

  it('un comprador no entra a la bandeja', async () => {
    const fetchMock = servidor({ ...comoSub(), 'GET /api/auth/me': [200, SESION], 'GET /api/carrito': [200, copia(DATOS.carrito)] })
    abrirEn('/admin/denuncias')
    await screen.findByRole('heading', { level: 1 })
    expect(window.location.pathname).not.toBe('/admin/denuncias')
    expect(llamadas(fetchMock, 'GET /api/admin/denuncias')).toHaveLength(0)
  })
})

describe('Bloquear las ventas desde las denuncias (CCPIP-85, CCPIP-322, Figma S-03 y S-04)', () => {
  const BLOQUEAR = 'POST /api/admin/productores/2/bloquear'
  const ficha = (ventaBloqueada) => ({ ...copia(DATOS.fichaProductorAdmin), id: 2, organizacion: 'C.N. Yarina', ventaBloqueada })

  it('pide el motivo en la confirmación, bloquea y ofrece desbloquear', async () => {
    const fetchMock = servidor(comoSub({ [BLOQUEAR]: () => [200, ficha(true)] }))
    const user = userEvent.setup()
    abrirEn('/admin/denuncias/productores/2')
    const bloqueo = within(await screen.findByRole('region', { name: 'Bloqueo de ventas' }))
    await user.click(bloqueo.getByRole('button', { name: 'Bloquear productor' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Bloquear a C.N. Yarina' })).toBeInTheDocument()
    expect(dialogo.getByText(/Mientras esté bloqueado no podrá vender/)).toBeInTheDocument()
    await user.click(dialogo.getByRole('button', { name: 'Bloquear productor' }))
    expect(dialogo.getByText('Escribe el motivo de la decisión')).toBeInTheDocument()
    expect(llamadas(fetchMock, BLOQUEAR)).toHaveLength(0)

    await user.type(dialogo.getByLabelText('Motivo de la decisión'), 'Denuncias reiteradas sobre el origen')
    await user.click(dialogo.getByRole('button', { name: 'Bloquear productor' }))
    expect(await bloqueo.findByText('Bloqueaste al productor. Sus productos quedaron en venta pausada.')).toBeInTheDocument()
    expect(JSON.parse(llamadas(fetchMock, BLOQUEAR)[0][1].body)).toEqual({ motivo: 'Denuncias reiteradas sobre el origen' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByText('Venta bloqueada')).toBeInTheDocument()
    expect(bloqueo.getByRole('button', { name: 'Desbloquear productor' })).toBeInTheDocument()
  })

  it('si el servidor lo rechaza, lo dice dentro de la confirmación', async () => {
    servidor(comoSub({ [BLOQUEAR]: [409, { code: 'VENTA_YA_BLOQUEADA', error: 'La venta de este productor ya está bloqueada.' }] }))
    const user = userEvent.setup()
    abrirEn('/admin/denuncias/productores/2')
    await user.click(await screen.findByRole('button', { name: 'Bloquear productor' }))
    const dialogo = within(screen.getByRole('dialog'))
    await user.type(dialogo.getByLabelText('Motivo de la decisión'), 'Motivo')
    await user.click(dialogo.getByRole('button', { name: 'Bloquear productor' }))
    expect(await dialogo.findByText('La venta de este productor ya está bloqueada.')).toBeInTheDocument()
    await user.click(dialogo.getByRole('button', { name: 'Cancelar' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('en inglés, el rechazo del servidor se traduce por su código', async () => {
    await i18n.changeLanguage('en')
    servidor(comoSub({ [BLOQUEAR]: [409, { code: 'VENTA_YA_BLOQUEADA', error: 'La venta de este productor ya está bloqueada.' }] }))
    const user = userEvent.setup()
    abrirEn('/admin/denuncias/productores/2')
    await user.click(await screen.findByRole('button', { name: 'Block producer' }))
    const dialogo = within(screen.getByRole('dialog'))
    expect(dialogo.getByRole('heading', { name: 'Block C.N. Yarina' })).toBeInTheDocument()
    await user.type(dialogo.getByLabelText('Reason for the decision'), 'Reason')
    await user.click(dialogo.getByRole('button', { name: 'Block producer' }))
    expect(await dialogo.findByText("This producer's sales are already blocked.")).toBeInTheDocument()
    await i18n.changeLanguage('es')
  })

  it('la bandeja y el expediente marcan al productor bloqueado', async () => {
    const bandeja = copia(DATOS.denunciasAdmin)
    bandeja.productores[0].ventaBloqueada = true
    const expediente = copia(DATOS.expedienteDenuncias)
    expediente.productor.ventaBloqueada = true
    servidor(comoSub({ 'GET /api/admin/denuncias': () => [200, bandeja], [`GET ${EXPEDIENTE}`]: () => [200, expediente] }))
    const user = userEvent.setup()
    abrirEn('/admin/denuncias')
    const filas = within(await screen.findByRole('table', { name: 'Productores con denuncias' })).getAllByRole('row').slice(1)
    expect(within(filas[0]).getByText('Venta bloqueada')).toBeInTheDocument()
    expect(within(filas[1]).queryByText('Venta bloqueada')).not.toBeInTheDocument()
    await user.click(within(filas[0]).getByRole('link', { name: 'Ver historial de denuncias de C.N. Yarina' }))
    const bloqueo = within(await screen.findByRole('region', { name: 'Bloqueo de ventas' }))
    expect(bloqueo.getByText(/Sus ventas están bloqueadas/)).toBeInTheDocument()
    expect(bloqueo.getByRole('button', { name: 'Desbloquear productor' })).toBeInTheDocument()
  })
})
