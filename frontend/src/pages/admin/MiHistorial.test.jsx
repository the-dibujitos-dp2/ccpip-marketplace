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

const RUTA = '/api/admin/mi-historial'

const HISTORIAL = {
  desde: '2026-10-01',
  hasta: '2026-10-30',
  tipo: null,
  acciones: [
    { clave: 'CH-3', fecha: '2026-10-22T20:00:00Z', tipo: 'CUENTAS', accion: 'CUENTA_SUSPENDER', sobre: 'Ana Quispe',
      detalle: null, objeto: 'CUENTA', objetoId: 12, motivo: 'Denuncias falsas reiteradas' },
    { clave: 'PD-9', fecha: '2026-10-21T17:30:00Z', tipo: 'PRODUCTORES', accion: 'BLOQUEAR_VENTA', sobre: 'Asoc. Pukllay',
      detalle: null, objeto: 'PRODUCTOR', objetoId: 4, motivo: 'Denuncias reiteradas sin descargo' },
    { clave: 'DE-5', fecha: '2026-10-20T14:00:00Z', tipo: 'DENUNCIAS', accion: 'DENUNCIA_RESUELTA', sobre: 'C.N. Yarina',
      detalle: '21', objeto: 'DENUNCIAS', objetoId: 2, motivo: 'El productor corrigió la oferta.' },
    { clave: 'AD-7', fecha: '2026-10-16T15:12:00Z', tipo: 'AUTORIZACIONES', accion: 'AUTORIZACION_APROBADA', sobre: 'Asoc. Nii Joi',
      detalle: 'Miel de abeja nativa 250 g', objeto: 'AUTORIZACION', objetoId: 31, motivo: 'Evidencia completa' },
  ],
}

const comoSub = (rutas = {}) =>
  servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_SUB], [`GET ${RUTA}`]: () => [200, copia(HISTORIAL)], ...rutas })

const filas = async () =>
  within(await screen.findByRole('table', { name: 'Mi historial' })).getAllByRole('row').slice(1)

afterEach(async () => {
  await i18n.changeLanguage('es')
})

describe('S-05 Mi historial del sub administrador (CCPIP-126)', () => {
  it('lista sus acciones con fecha, acción, sobre quién y motivo, sin poder editarlas', async () => {
    comoSub()
    abrirEn('/admin/mi-historial')
    expect(await screen.findByRole('heading', { level: 1, name: 'Mi historial' })).toBeInTheDocument()
    const nav = within(screen.getByRole('navigation', { name: 'Sub administrador' }))
    expect(nav.getByRole('link', { name: 'Mi historial' })).toHaveAttribute('aria-current', 'page')

    const [cuenta, bloqueo, denuncia, autorizacion] = await filas()
    expect(within(bloqueo).getByText('Bloqueó productor')).toBeInTheDocument()
    expect(within(bloqueo).getByText('Denuncias reiteradas sin descargo')).toBeInTheDocument()
    expect(within(bloqueo).getByRole('link', { name: 'Asoc. Pukllay' })).toHaveAttribute('href', '/admin/productores/4')
    expect(within(denuncia).getByText('Resolvió denuncia')).toBeInTheDocument()
    expect(within(denuncia).getByText('Denuncia 21')).toBeInTheDocument()
    expect(within(denuncia).getByRole('link', { name: 'C.N. Yarina' })).toHaveAttribute('href', '/admin/denuncias/productores/2')
    expect(within(autorizacion).getByText('Miel de abeja nativa 250 g')).toBeInTheDocument()
    expect(within(autorizacion).getByRole('link', { name: 'Asoc. Nii Joi' })).toHaveAttribute('href', '/admin/autorizaciones/31')
    expect(within(cuenta).getByRole('link', { name: 'Ana Quispe' })).toHaveAttribute('href', '/admin/cuentas/12')
    expect(within(cuenta).getByText('Suspendió cuenta')).toBeInTheDocument()
    // Solo lectura: ninguna fila ofrece editar ni borrar
    expect(screen.queryByRole('button', { name: /editar|eliminar|borrar/i })).not.toBeInTheDocument()
    expect(screen.getByText('Registro inalterable. Las correcciones se registran como nuevas acciones.')).toBeInTheDocument()
    expect(screen.getByLabelText('Desde')).toHaveValue('2026-10-01')
    expect(screen.getByLabelText('Hasta')).toHaveValue('2026-10-30')
  })

  it('filtra por tipo de acción y por periodo', async () => {
    const soloProductores = { ...copia(HISTORIAL), tipo: 'PRODUCTORES', acciones: [copia(HISTORIAL.acciones[1])] }
    const fetchMock = comoSub({
      [`GET ${RUTA}?desde=2026-10-01&hasta=2026-10-30&tipo=PRODUCTORES`]: [200, soloProductores],
    })
    const user = userEvent.setup()
    abrirEn('/admin/mi-historial')
    await filas()
    await user.selectOptions(screen.getByLabelText('Tipo de acción'), 'Productores')
    expect(await filas()).toHaveLength(1)
    expect(llamadas(fetchMock, `GET ${RUTA}?desde=2026-10-01&hasta=2026-10-30&tipo=PRODUCTORES`)).toHaveLength(1)

    await user.clear(screen.getByLabelText('Desde'))
    await user.type(screen.getByLabelText('Desde'), '2026-09-01')
    expect(llamadas(fetchMock, `GET ${RUTA}?desde=2026-09-01&hasta=2026-10-30&tipo=PRODUCTORES`)).toHaveLength(1)
  })

  it('sin acciones en el periodo lo dice', async () => {
    comoSub({ [`GET ${RUTA}`]: [200, { ...copia(HISTORIAL), acciones: [] }] })
    abrirEn('/admin/mi-historial')
    expect(await screen.findByText('No registraste acciones en este periodo.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('muestra el error del servidor', async () => {
    comoSub({ [`GET ${RUTA}`]: [422, { code: 'RANGO_MUY_LARGO', error: 'Consulta como máximo un año a la vez.' }] })
    abrirEn('/admin/mi-historial')
    expect(await screen.findByText('Consulta como máximo un año a la vez.')).toBeInTheDocument()
  })

  it('el superadministrador no tiene este historial', async () => {
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_SUPER],
      'GET /api/admin/tablero': [200, copia(DATOS.tableroAdmin)],
      [`GET ${RUTA}`]: [200, copia(HISTORIAL)],
    })
    abrirEn('/admin/mi-historial')
    await screen.findByRole('heading', { level: 1 })
    expect(window.location.pathname).not.toBe('/admin/mi-historial')
    expect(llamadas(fetchMock, `GET ${RUTA}`)).toHaveLength(0)
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    comoSub()
    abrirEn('/admin/mi-historial')
    expect(await screen.findByRole('heading', { level: 1, name: 'My history' })).toBeInTheDocument()
    const [, bloqueo, denuncia] = within(await screen.findByRole('table', { name: 'My history' })).getAllByRole('row').slice(1)
    expect(within(bloqueo).getByText('Blocked producer')).toBeInTheDocument()
    expect(within(denuncia).getByText('Report 21')).toBeInTheDocument()
    expect(screen.getByText('Unalterable record. Corrections are recorded as new actions.')).toBeInTheDocument()
  })
})
