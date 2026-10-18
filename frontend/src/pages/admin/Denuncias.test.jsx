import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
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

  it('un comprador no entra a la bandeja', async () => {
    const fetchMock = servidor({ ...comoSub(), 'GET /api/auth/me': [200, SESION], 'GET /api/carrito': [200, copia(DATOS.carrito)] })
    abrirEn('/admin/denuncias')
    await screen.findByRole('heading', { level: 1 })
    expect(window.location.pathname).not.toBe('/admin/denuncias')
    expect(llamadas(fetchMock, 'GET /api/admin/denuncias')).toHaveLength(0)
  })
})
