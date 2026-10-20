import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR } from '../../test/servidor.js'

function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

// MisDatosService.MisDatos
const DATOS_CUENTA = { nombres: 'Lucía', apellidos: 'Ramírez Torres', correo: 'consumidor@ccpip.test', correoConfirmado: true, celular: '987 654 321' }

const comoComprador = (rutas = {}) => ({
  ...PUBLICAS,
  'GET /api/auth/me': [200, SESION],
  'GET /api/carrito': [200, copia(DATOS.carrito)],
  'GET /api/pedidos': () => [200, copia(DATOS.misPedidos)],
  'GET /api/cuenta/datos': () => [200, copia(DATOS_CUENTA)],
  ...rutas,
})

const cuerpo = (fetchMock, clave) => JSON.parse(llamadas(fetchMock, clave)[0][1].body)

describe('CW-13 Mis datos (CCPIP-400, CCPIP-113)', () => {
  afterEach(() => i18n.changeLanguage('es'))

  it('se llega desde Mi cuenta y muestra sus datos, con el correo de solo lectura', async () => {
    servidor(comoComprador())
    const user = userEvent.setup()
    abrirEn('/cuenta/pedidos')

    await user.click(await screen.findByRole('link', { name: 'Datos personales' }))
    expect(window.location.pathname).toBe('/cuenta/datos')
    expect(screen.getByRole('link', { name: 'Datos personales' })).toHaveAttribute('aria-current', 'page')

    const form = await screen.findByRole('form', { name: 'Datos personales' })
    expect(within(form).getByRole('textbox', { name: 'Nombres' })).toHaveValue('Lucía')
    expect(within(form).getByRole('textbox', { name: 'Apellidos' })).toHaveValue('Ramírez Torres')
    expect(within(form).getByRole('textbox', { name: 'Celular' })).toHaveValue('987 654 321')
    const correo = within(form).getByRole('textbox', { name: 'Correo electrónico' })
    expect(correo).toHaveValue('consumidor@ccpip.test')
    expect(correo).toHaveAttribute('readonly')
    expect(within(form).getByText('Es tu usuario para ingresar; no se cambia aquí.')).toBeInTheDocument()
    // Sin genero ni fecha de nacimiento
    expect(within(form).queryByText(/género|nacimiento/i)).not.toBeInTheDocument()
  })

  it('guarda nombres, apellidos y celular, y el nombre de la cuenta cambia sin volver a ingresar', async () => {
    const fetchMock = servidor(comoComprador({
      'PUT /api/cuenta/datos': (o) => [200, { ...DATOS_CUENTA, ...JSON.parse(o.body) }],
    }))
    const user = userEvent.setup()
    abrirEn('/cuenta/datos')

    const form = await screen.findByRole('form', { name: 'Datos personales' })
    const nombres = within(form).getByRole('textbox', { name: 'Nombres' })
    await user.clear(nombres)
    await user.type(nombres, '  Ana ')
    const celular = within(form).getByRole('textbox', { name: 'Celular' })
    await user.clear(celular)
    await user.click(within(form).getByRole('button', { name: 'Guardar cambios' }))

    expect(await within(form).findByText('Tus datos se guardaron')).toBeInTheDocument()
    expect(cuerpo(fetchMock, 'PUT /api/cuenta/datos')).toEqual({ nombres: 'Ana', apellidos: 'Ramírez Torres', celular: '' })
    // El menu de la cuenta ya muestra el nuevo nombre
    await waitFor(() => expect(screen.getAllByText('Ana Ramírez Torres').length).toBeGreaterThan(0))
  })

  it('valida antes de enviar y muestra el error del servidor', async () => {
    const fetchMock = servidor(comoComprador({
      'PUT /api/cuenta/datos': [400, { code: 'DATOS_INVALIDOS', error: 'Revisa los datos.', campos: { celular: 'Escribe un celular válido' } }],
    }))
    const user = userEvent.setup()
    abrirEn('/cuenta/datos')

    const form = await screen.findByRole('form', { name: 'Datos personales' })
    await user.clear(within(form).getByRole('textbox', { name: 'Apellidos' }))
    await user.type(within(form).getByRole('textbox', { name: 'Celular' }), 'x')
    await user.click(within(form).getByRole('button', { name: 'Guardar cambios' }))
    expect(within(form).getByText('Escribe tus apellidos')).toBeInTheDocument()
    expect(within(form).getByText('Escribe un celular válido')).toBeInTheDocument()
    expect(llamadas(fetchMock, 'PUT /api/cuenta/datos')).toHaveLength(0)

    await user.type(within(form).getByRole('textbox', { name: 'Apellidos' }), 'Quispe')
    const celular = within(form).getByRole('textbox', { name: 'Celular' })
    await user.clear(celular)
    await user.type(celular, '987654')
    await user.click(within(form).getByRole('button', { name: 'Guardar cambios' }))
    expect(await within(form).findByText('No pudimos guardar tus datos')).toBeInTheDocument()
    expect(within(form).getByText('Escribe un celular válido')).toBeInTheDocument()
  })

  it('un productor no entra a Mis datos', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION_PRODUCTOR], 'GET /api/productor/panel': [200, copia(DATOS.panelProductor)],
      'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)], 'GET /api/productor/intereses': [200, []] })
    abrirEn('/cuenta/datos')
    await waitFor(() => expect(window.location.pathname).not.toBe('/cuenta/datos'))
  })

  it('en inglés', async () => {
    await i18n.changeLanguage('en')
    servidor(comoComprador())
    abrirEn('/cuenta/datos')
    const form = await screen.findByRole('form', { name: 'Personal details' })
    expect(within(form).getByRole('textbox', { name: 'First names' })).toHaveValue('Lucía')
    expect(within(form).getByRole('button', { name: 'Save changes' })).toBeInTheDocument()
  })
})
