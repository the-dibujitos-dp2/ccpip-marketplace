import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { abrirPestana, copia, DATOS, llamadas, panel, PUBLICAS, servidor, SESION, SESION_SUB, SESION_SUPER } from '../../test/servidor.js'

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

  it('el expediente muestra el historial con quién dio cada paso', async () => {
    servidor(comoSub())
    const user = userEvent.setup()
    abrirEn('/admin/denuncias/productores/2')
    const resueltas = await abrirPestana(user, 'Resueltas')
    const cerrada = within(resueltas.getByRole('article', { name: 'Denuncia 17: Contra la comunidad' }))
    const pasos = within(cerrada.getByRole('list', { name: 'Historial de la denuncia 17' })).getAllByRole('listitem')
    expect(pasos).toHaveLength(3)
    expect(pasos[0]).toHaveTextContent('Registrada · Carlos Mendoza')
    expect(pasos[1]).toHaveTextContent('Respuesta del productor · C.N. Yarina')
    expect(pasos[2]).toHaveTextContent('Pasa a Resuelta · Lucía Paredes — El productor corrigió la oferta.')
    expect(cerrada.queryByRole('form')).not.toBeInTheDocument()
    const recibidas = await abrirPestana(user, 'Recibidas')
    const abierta = within(recibidas.getByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' }))
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
    // Pasa a la pestana de su nuevo estado y el aviso dice cual
    expect(await screen.findByText('La denuncia 21 pasó a En revisión')).toBeInTheDocument()
    expect(screen.getByText('Ahora está en la pestaña En revisión.')).toBeInTheDocument()
    expect((await panel('Recibidas')).getByText('No hay denuncias en estado Recibida')).toBeInTheDocument()
    const pestanaEnRevision = await abrirPestana(user, 'En revisión')
    const abierta = within(pestanaEnRevision.getByRole('article',{ name: 'Denuncia 21: Cacao en grano fermentado 1 kg' }))
    expect(abierta.getByText('En revisión', { selector: '.badge-estado' })).toBeInTheDocument()
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

describe('Detalle de una denuncia (CCPIP-432)', () => {
  /** Los pares termino → valor del detalle de la denuncia. */
  const detalle = (id, etiqueta = `Detalle de la denuncia ${id}`) => {
    const dl = screen.getByLabelText(etiqueta)
    return Object.fromEntries([...dl.querySelectorAll('dt')].map((dt) => [dt.textContent, dt.nextElementSibling.textContent]))
  }
  /** El expediente con denuncias previas: una sobre el mismo cacao y otra sobre otro producto. */
  const conAnteriores = () => {
    const expediente = copia(DATOS.expedienteDenuncias)
    const previa = (id, producto, motivo, estado, creadaEn) => ({
      id, producto, motivo, descripcion: 'Una denuncia previa.', estado, creadaEn, compraVerificada: false,
      denunciante: 'Ana Torres', siguientes: [], historial: [{ tipo: 'REGISTRADA', estado: 'RECIBIDA', rol: 'CONSUMIDOR', responsable: 'Ana Torres', registradoEn: creadaEn }],
    })
    expediente.denuncias.push(
      previa(12, { id: 8, nombre: 'Miel de abeja 500 g' }, 'CALIDAD', 'RESUELTA', '2026-09-25T14:00:00Z'),
      previa(9, { id: 5, nombre: 'Cacao en grano fermentado 1 kg' }, 'NO_CORRESPONDE', 'DESESTIMADA', '2026-09-20T14:00:00Z'),
    )
    expediente.productor.total = 4
    return expediente
  }

  it('muestra a quién se denunció, quién denunció, la fecha y el motivo', async () => {
    servidor(comoSub())
    const user = userEvent.setup()
    abrirEn('/admin/denuncias/productores/2')
    await screen.findByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' })
    expect(detalle(21)).toEqual({
      Denunciado: 'Cacao en grano fermentado 1 kg, de C.N. Yarina',
      Denunció: 'Rosa Quispe',
      Fecha: '15/10 09:00',
      Motivo: 'Origen o certificación dudosa',
    })
    expect(screen.getByText('Es la primera denuncia sobre este producto.')).toBeInTheDocument()
    await abrirPestana(user, 'Resueltas')
    expect(detalle(17)).toMatchObject({ Denunciado: 'La comunidad C.N. Yarina', Denunció: 'Carlos Mendoza', Motivo: 'Práctica indebida del productor' })
    expect(screen.getByText('Es la primera denuncia sobre esta comunidad.')).toBeInTheDocument()
  })

  it('lista las denuncias anteriores sobre el mismo producto o, si es contra la comunidad, todas las suyas', async () => {
    servidor(comoSub({ [`GET ${EXPEDIENTE}`]: () => [200, conAnteriores()] }))
    const user = userEvent.setup()
    abrirEn('/admin/denuncias/productores/2')
    const cacao = within(await screen.findByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' }))
    expect(cacao.getByText('Denuncias anteriores sobre este producto (1)')).toBeInTheDocument()
    const previasCacao = within(cacao.getByRole('list', { name: 'Denuncias anteriores a la denuncia 21' })).getAllByRole('listitem')
    expect(previasCacao).toHaveLength(1)
    expect(previasCacao[0]).toHaveTextContent('Denuncia 9 · Cacao en grano fermentado 1 kg · No corresponde a lo descrito Desestimada')
    expect(within(previasCacao[0]).getByText('20/9 09:00')).toHaveAttribute('datetime', '2026-09-20T14:00:00Z')
    // Las anteriores salen aunque esten en otra pestana
    const resueltas = await abrirPestana(user, 'Resueltas')
    const comunidad = within(resueltas.getByRole('article', { name: 'Denuncia 17: Contra la comunidad' }))
    expect(comunidad.getByText('Denuncias anteriores sobre esta comunidad (2)')).toBeInTheDocument()
    const previasComunidad = within(comunidad.getByRole('list', { name: 'Denuncias anteriores a la denuncia 17' })).getAllByRole('listitem')
    expect(previasComunidad.map((li) => li.textContent.split(' · ')[0])).toEqual(['Denuncia 12', 'Denuncia 9'])
    const desestimadas = await abrirPestana(user, 'Desestimadas')
    const primera = within(desestimadas.getByRole('article', { name: 'Denuncia 9: Cacao en grano fermentado 1 kg' }))
    expect(primera.getByText('Es la primera denuncia sobre este producto.')).toBeInTheDocument()
  })

  it('sin el nombre del denunciante, dice que su identidad está protegida', async () => {
    const expediente = copia(DATOS.expedienteDenuncias)
    delete expediente.denuncias[0].denunciante
    delete expediente.denuncias[0].historial[0].responsable
    servidor(comoSub({ [`GET ${EXPEDIENTE}`]: () => [200, expediente] }))
    abrirEn('/admin/denuncias/productores/2')
    const abierta = within(await screen.findByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' }))
    expect(detalle(21).Denunció).toBe('Comprador (identidad protegida)')
    expect(within(abierta.getByRole('list', { name: 'Historial de la denuncia 21' })).getByRole('listitem'))
      .toHaveTextContent('Registrada · Comprador (identidad protegida)')
  })

  it('el superadministrador ve el mismo detalle, solo para consultar', async () => {
    servidor(comoSub({ 'GET /api/auth/me': [200, SESION_SUPER], 'GET /api/admin/tablero': [200, copia(DATOS.tableroAdmin)] }))
    abrirEn('/admin/denuncias/productores/2')
    await screen.findByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' })
    expect(detalle(21).Denunció).toBe('Rosa Quispe')
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    servidor(comoSub({ [`GET ${EXPEDIENTE}`]: () => [200, conAnteriores()] }))
    const user = userEvent.setup()
    abrirEn('/admin/denuncias/productores/2')
    await screen.findByRole('article', { name: 'Report 21: Cacao en grano fermentado 1 kg' })
    expect(detalle(21, 'Details of report 21')).toMatchObject({ Reported: 'Cacao en grano fermentado 1 kg, by C.N. Yarina', 'Reported by': 'Rosa Quispe', Reason: 'Questionable origin or certification' })
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Received (1)', 'Under review (0)', 'Resolved (2)', 'Dismissed (1)'])
    await abrirPestana(user, 'Resolved')
    expect(screen.getByText('Earlier reports about this community (2)')).toBeInTheDocument()
    expect(screen.getByText('This is the first report about this product.')).toBeInTheDocument()
    expect(screen.getByRole('searchbox', { name: 'Search by description, product, reason or who reported' })).toBeInTheDocument()
    await i18n.changeLanguage('es')
  })
})

describe('Pestañas y filtros del expediente (CCPIP-432)', () => {
  /** El expediente con una denuncia por estado y una mas resuelta, de distintos productos, motivos y fechas. */
  const variado = () => {
    const expediente = copia(DATOS.expedienteDenuncias)
    const otra = (id, producto, motivo, estado, creadaEn, denunciante) => ({
      id, producto, motivo, descripcion: `Detalle de la denuncia ${id}.`, estado, creadaEn, compraVerificada: false,
      denunciante, siguientes: [], historial: [{ tipo: 'REGISTRADA', estado: 'RECIBIDA', rol: 'CONSUMIDOR', responsable: denunciante, registradoEn: creadaEn }],
    })
    expediente.denuncias.push(
      otra(12, { id: 8, nombre: 'Miel de abeja 500 g' }, 'CALIDAD', 'RESUELTA', '2026-09-25T14:00:00Z', 'Ana Torres'),
      otra(9, { id: 5, nombre: 'Cacao en grano fermentado 1 kg' }, 'NO_CORRESPONDE', 'DESESTIMADA', '2026-09-20T14:00:00Z', 'Luis Rojas'),
    )
    return expediente
  }
  const denunciasVisibles = () => screen.queryAllByRole('article').map((a) => a.getAttribute('aria-label').split(':')[0])

  it('una pestaña por estado con su conteo; abre la primera que tiene denuncias', async () => {
    servidor(comoSub())
    const { unmount } = abrirEn('/admin/denuncias/productores/2')
    await screen.findByRole('article', { name: 'Denuncia 21: Cacao en grano fermentado 1 kg' })
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Recibidas (1)', 'En revisión (0)', 'Resueltas (1)', 'Desestimadas (0)'])
    expect(screen.getByRole('tab', { selected: true })).toHaveTextContent('Recibidas (1)')
    unmount()

    const soloResueltas = copia(DATOS.expedienteDenuncias)
    soloResueltas.denuncias = soloResueltas.denuncias.filter((d) => d.estado === 'RESUELTA')
    servidor(comoSub({ [`GET ${EXPEDIENTE}`]: () => [200, soloResueltas] }))
    abrirEn('/admin/denuncias/productores/2')
    expect(await (await panel('Resueltas')).findByRole('article', { name: 'Denuncia 17: Contra la comunidad' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { selected: true })).toHaveTextContent('Resueltas (1)')
  })

  it('busca por texto (también motivo y quién denunció) y filtra por producto y fechas dentro de la pestaña, y los filtros se mantienen al cambiarla', async () => {
    servidor(comoSub({ [`GET ${EXPEDIENTE}`]: () => [200, variado()] }))
    const user = userEvent.setup()
    abrirEn('/admin/denuncias/productores/2')
    await abrirPestana(user, 'Resueltas')
    expect(denunciasVisibles()).toEqual(['Denuncia 17', 'Denuncia 12'])
    // El producto ofrece solo los denunciados y la comunidad; el motivo no tiene selector, se busca
    const producto = screen.getByLabelText('Producto')
    expect([...producto.options].map((o) => o.textContent)).toEqual(['Todos', 'Cacao en grano fermentado 1 kg', 'Miel de abeja 500 g', 'Contra la comunidad'])
    expect(screen.queryByRole('combobox', { name: 'Motivo' })).not.toBeInTheDocument()
    await user.selectOptions(producto, 'Contra la comunidad')
    expect(denunciasVisibles()).toEqual(['Denuncia 17'])
    await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }))
    const buscador = screen.getByRole('searchbox', { name: 'Buscar por descripción, producto, motivo o quién denunció' })
    await user.type(buscador, 'ana torres')
    expect(denunciasVisibles()).toEqual(['Denuncia 12'])
    await user.clear(buscador)
    await user.type(buscador, 'practica indebida')
    expect(denunciasVisibles()).toEqual(['Denuncia 17'])
    await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }))
    await user.type(screen.getByLabelText('Desde'), '2026-09-30')
    expect(denunciasVisibles()).toEqual(['Denuncia 17'])
    await user.type(buscador, 'mala calidad')
    expect(screen.getByText('Nada coincide con la búsqueda y los filtros')).toBeInTheDocument()
    // Al cambiar de pestana siguen puestos
    await abrirPestana(user, 'Desestimadas')
    expect(denunciasVisibles()).toEqual([])
    expect(screen.getByText('Nada coincide con la búsqueda y los filtros')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }))
    expect(denunciasVisibles()).toEqual(['Denuncia 9'])
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
