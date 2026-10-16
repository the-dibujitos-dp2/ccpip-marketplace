import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const RUTA = '/api/productor/cuenta-bancaria'
const BANCOS = ['Banco de Crédito del Perú (BCP)', 'Interbank', 'Banco de la Nación']

// Como la devuelve el servidor (CuentaBancariaModelos.Cuenta)
const CUENTA = {
  id: 1,
  estado: 'ACTIVA',
  titular: 'Asoc. Nii Joi',
  documento: '20601234561',
  banco: 'Banco de la Nación',
  tipoCuenta: 'AHORROS',
  numeroCuenta: '04-110-004821',
  cci: '01811000041100482117',
  moneda: 'PEN',
  correoAvisos: null,
  yapeCelular: null,
  motivoDesactivacion: null,
  desactivadaEn: null,
  actualizadaEn: '2026-10-16T15:00:00Z',
}
const DESACTIVADA = { ...CUENTA, estado: 'DESACTIVADA', motivoDesactivacion: 'BLOQUEO_BANCO', desactivadaEn: '2026-10-16T17:30:00Z' }

const productor = (rutas = {}) =>
  servidor({
    ...PUBLICAS,
    'GET /api/auth/me': [200, SESION_PRODUCTOR],
    'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
    [`GET ${RUTA}/bancos`]: [200, BANCOS],
    [`GET ${RUTA}`]: [200, CUENTA],
    ...rutas,
  })

const cuerpo = (fetchMock, clave) => JSON.parse(llamadas(fetchMock, clave).at(-1)[1].body)

describe('PW-19 Cuenta bancaria del productor (CCPIP-32, CCPIP-274)', () => {
  it('sin cuenta explica la venta pausada y registra una con Yape opcional', async () => {
    const fetchMock = productor({
      [`GET ${RUTA}`]: [404, { code: 'CUENTA_NO_REGISTRADA', error: 'Aún no registras tu cuenta bancaria.' }],
      [`POST ${RUTA}`]: [201, { ...CUENTA, yapeCelular: '987111222' }],
    })
    const user = userEvent.setup()
    abrirEn('/productor/cuenta-bancaria')

    expect(await screen.findByRole('heading', { level: 2, name: 'Registra tu cuenta bancaria' })).toBeInTheDocument()
    expect(screen.getByText('Aún no tienes una cuenta para cobrar')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Cuenta bancaria' })).toHaveAttribute('aria-current', 'page')

    // Sin datos, el formulario no se envia
    await user.click(screen.getByRole('button', { name: 'Registrar cuenta' }))
    expect(screen.getByLabelText('Titular de la cuenta')).toHaveAccessibleDescription('Indica el titular de la cuenta.')
    expect(llamadas(fetchMock, `POST ${RUTA}`)).toHaveLength(0)

    await user.type(screen.getByLabelText('Titular de la cuenta'), 'Asoc. Nii Joi')
    await user.type(screen.getByLabelText('Documento del titular'), '20601234561')
    await user.selectOptions(await screen.findByLabelText('Banco'), 'Banco de la Nación')
    await user.type(screen.getByLabelText('Número de cuenta'), '04-110-004821')
    await user.type(screen.getByLabelText('Código de cuenta interbancario (CCI)'), '018-110-000411004821-17')
    await user.click(screen.getByRole('checkbox', { name: 'Recibir también pagos por Yape' }))
    await user.type(screen.getByLabelText('Celular Yape'), '987 111 222')
    await user.click(screen.getByRole('button', { name: 'Registrar cuenta' }))

    expect(await screen.findByText('Cuenta registrada')).toBeInTheDocument()
    expect(cuerpo(fetchMock, `POST ${RUTA}`)).toEqual({
      titular: 'Asoc. Nii Joi',
      documento: '20601234561',
      banco: 'Banco de la Nación',
      tipoCuenta: 'AHORROS',
      numeroCuenta: '04-110-004821',
      cci: '018-110-000411004821-17',
      moneda: 'PEN',
      correoAvisos: null,
      yapeCelular: '987 111 222',
    })
    expect(screen.getByRole('heading', { level: 2, name: 'Cuenta de ahorros ···· 4821' })).toBeInTheDocument()
  })

  it('muestra la cuenta activa, sin datos de tarjeta, y guarda los cambios', async () => {
    const fetchMock = productor({ [`PUT ${RUTA}`]: (o) => [200, { ...CUENTA, ...JSON.parse(o.body) }] })
    const user = userEvent.setup()
    abrirEn('/productor/cuenta-bancaria')

    expect(await screen.findByRole('heading', { level: 2, name: 'Cuenta de ahorros ···· 4821' })).toBeInTheDocument()
    expect(screen.getByText('Activa')).toBeInTheDocument()
    expect(screen.getByText('Una cuenta bancaria no vence: no pedimos fecha de vencimiento, CVV ni datos de tarjeta.')).toBeInTheDocument()
    expect(screen.queryByLabelText(/CVV|vencimiento/i)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Titular de la cuenta')).toHaveValue('Asoc. Nii Joi')

    await user.selectOptions(screen.getByLabelText('Tipo de cuenta'), 'CORRIENTE')
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByText('Cambios guardados')).toBeInTheDocument()
    expect(cuerpo(fetchMock, `PUT ${RUTA}`).tipoCuenta).toBe('CORRIENTE')
    expect(screen.getByRole('heading', { level: 2, name: 'Cuenta corriente ···· 4821' })).toBeInTheDocument()
  })

  it('muestra en su campo lo que el servidor rechaza', async () => {
    productor({ [`PUT ${RUTA}`]: [422, { code: 'BANCO_INVALIDO', error: 'Elige un banco de la lista.' }] })
    const user = userEvent.setup()
    abrirEn('/productor/cuenta-bancaria')
    await user.click(await screen.findByRole('button', { name: 'Guardar cambios' }))
    expect(await screen.findByLabelText('Banco')).toHaveAccessibleDescription('Elige un banco de la lista.')
  })
})

describe('PW-20 Desactivar y reactivar la cuenta (CCPIP-275, CCPIP-276)', () => {
  it('desactivar pide motivo y confirmación expresa, y avisa que la venta se pausa', async () => {
    const fetchMock = productor({ [`POST ${RUTA}/desactivar`]: [200, DESACTIVADA] })
    const user = userEvent.setup()
    abrirEn('/productor/cuenta-bancaria')

    await user.click(await screen.findByRole('button', { name: 'Desactivar temporalmente' }))
    const dialogo = within(screen.getByRole('dialog', { name: 'Desactivar temporalmente la cuenta bancaria' }))
    expect(dialogo.getByText('Los pedidos ya pagados siguen su curso.')).toBeInTheDocument()
    // Sin motivo no se envia
    await user.click(dialogo.getByRole('button', { name: 'Desactivar temporalmente' }))
    expect(dialogo.getByLabelText('Motivo')).toHaveAccessibleDescription('Elige el motivo.')
    expect(llamadas(fetchMock, `POST ${RUTA}/desactivar`)).toHaveLength(0)

    await user.selectOptions(dialogo.getByLabelText('Motivo'), 'BLOQUEO_BANCO')
    await user.click(dialogo.getByRole('button', { name: 'Desactivar temporalmente' }))
    expect(await screen.findByText('Cuenta desactivada')).toBeInTheDocument()
    expect(cuerpo(fetchMock, `POST ${RUTA}/desactivar`)).toEqual({ motivo: 'BLOQUEO_BANCO', confirmo: true })
    expect(screen.getByText('Desactivada temporalmente')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reactivar cuenta' })).toBeInTheDocument()
  })

  it('reactivar vuelve a enviar los datos con la contraseña', async () => {
    let intentos = 0
    const fetchMock = productor({
      [`GET ${RUTA}`]: [200, DESACTIVADA],
      [`POST ${RUTA}/reactivar`]: () =>
        ++intentos === 1 ? [422, { code: 'CONTRASENA_INCORRECTA', error: 'La contraseña no es correcta.' }] : [200, CUENTA],
    })
    const user = userEvent.setup()
    abrirEn('/productor/cuenta-bancaria')

    expect(await screen.findByText('Desactivada desde el 16/10 12:30 · Bloqueo del banco')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Desactivar temporalmente' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Reactivar cuenta' }))
    expect(screen.getByLabelText('Tu contraseña')).toHaveAccessibleDescription('Escribe tu contraseña.')

    await user.type(screen.getByLabelText('Tu contraseña'), 'Equivocada1')
    await user.click(screen.getByRole('button', { name: 'Reactivar cuenta' }))
    expect(await screen.findByLabelText('Tu contraseña')).toHaveAccessibleDescription('La contraseña no es correcta.')

    await user.clear(screen.getByLabelText('Tu contraseña'))
    await user.type(screen.getByLabelText('Tu contraseña'), 'Demo1234!')
    await user.click(screen.getByRole('button', { name: 'Reactivar cuenta' }))
    expect(await screen.findByText('Cuenta reactivada')).toBeInTheDocument()
    const enviado = cuerpo(fetchMock, `POST ${RUTA}/reactivar`)
    expect(enviado.contrasena).toBe('Demo1234!')
    expect(enviado.datos.cci).toBe('01811000041100482117')
    expect(screen.getByText('Activa')).toBeInTheDocument()
  })
})

describe('Cuenta bancaria en inglés (CCPIP-32)', () => {
  it('formulario y estados en inglés', async () => {
    await i18n.changeLanguage('en')
    productor({ [`GET ${RUTA}`]: [200, DESACTIVADA] })
    abrirEn('/productor/cuenta-bancaria')
    expect(await screen.findByRole('heading', { level: 2, name: 'Savings account ···· 4821' })).toBeInTheDocument()
    expect(screen.getByText('Temporarily deactivated')).toBeInTheDocument()
    expect(screen.getByLabelText('Interbank account code (CCI)')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reactivate account' })).toBeInTheDocument()
    expect(screen.queryByText(/Cuenta|Titular|Desactivada/)).not.toBeInTheDocument()
  })
})
