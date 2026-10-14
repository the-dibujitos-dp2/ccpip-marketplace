import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App.jsx'
import i18n, { idiomaGuardado } from '../i18n/index.js'
import { copia, DATOS, llamadas, PUBLICAS, servidor, SESION, SESION_PRODUCTOR, SIN_SESION } from '../test/servidor.js'

// CCPIP-265 (RF-135): elegir espanol o ingles y conservar la preferencia
function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const IDIOMA = 'PUT /api/auth/me/language'
const sinSesion = { ...PUBLICAS, 'GET /api/auth/me': SIN_SESION }

async function elegir(user, opcion) {
  await user.click(await screen.findByRole('button', { name: /^(Idioma|Language):/ }))
  await user.click(screen.getByRole('menuitemradio', { name: opcion }))
}

describe('Selector de idioma (CCPIP-265)', () => {
  it('el visitante cambia a inglés: toda la cabecera cambia y se guarda en su navegador', async () => {
    const fetchMock = servidor(sinSesion)
    const user = userEvent.setup()
    abrirEn('/')
    expect(await screen.findByRole('button', { name: 'Idioma: Español. Cambiar idioma' })).toHaveTextContent('ES · Español')

    await elegir(user, 'English')
    expect(await screen.findByRole('link', { name: 'Explore' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Language: English. Change language' })).toHaveTextContent('EN · English')
    expect(document.documentElement.lang).toBe('en')
    expect(idiomaGuardado()).toBe('en')
    // Sin sesion no hay cuenta donde guardarlo
    expect(llamadas(fetchMock, IDIOMA)).toHaveLength(0)
  })

  it('el menú marca el idioma actual y se cierra con Escape', async () => {
    servidor(sinSesion)
    const user = userEvent.setup()
    abrirEn('/')
    await user.click(await screen.findByRole('button', { name: /^Idioma:/ }))
    expect(screen.getByRole('menuitemradio', { name: 'Español' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('menuitemradio', { name: 'English' })).toHaveAttribute('aria-checked', 'false')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('con sesión, el idioma elegido también se guarda en la cuenta', async () => {
    const fetchMock = servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, SESION], [IDIOMA]: [204, null] })
    const user = userEvent.setup()
    abrirEn('/')
    await elegir(user, 'English')
    await waitFor(() => expect(llamadas(fetchMock, IDIOMA)).toHaveLength(1))
    expect(JSON.parse(llamadas(fetchMock, IDIOMA)[0][1].body)).toEqual({ language: 'en' })
  })

  it('al abrir la tienda con una cuenta en inglés, la interfaz sale en inglés', async () => {
    servidor({ ...PUBLICAS, 'GET /api/auth/me': [200, { ...SESION, user: { ...SESION.user, language: 'en' } }] })
    abrirEn('/')
    expect(await screen.findByRole('link', { name: 'Explore' })).toBeInTheDocument()
    expect(idiomaGuardado()).toBe('en')
  })

  it('si el visitante eligió inglés antes de ingresar, se conserva y pasa a su cuenta', async () => {
    let conSesion = false
    const fetchMock = servidor({
      ...PUBLICAS,
      'GET /api/auth/me': () => (conSesion ? [200, SESION] : SIN_SESION),
      'POST /api/auth/login': () => {
        conSesion = true
        return [200, { ...SESION, user: { ...SESION.user, language: 'es' } }]
      },
      [IDIOMA]: [204, null],
    })
    const user = userEvent.setup()
    abrirEn('/ingresar')
    await elegir(user, 'English')
    await user.type(await screen.findByLabelText('Email'), 'consumidor@ccpip.test')
    await user.type(screen.getByLabelText('Password'), 'Demo1234!')
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    await waitFor(() => expect(llamadas(fetchMock, IDIOMA)).toHaveLength(1))
    expect(JSON.parse(llamadas(fetchMock, IDIOMA)[0][1].body)).toEqual({ language: 'en' })
    expect(i18n.resolvedLanguage).toBe('en')
  })

  it('el panel del productor también ofrece el selector', async () => {
    servidor({
      ...PUBLICAS,
      'GET /api/auth/me': [200, SESION_PRODUCTOR],
      'GET /api/productor/tienda': () => [200, copia(DATOS.tiendaProductor)],
      'GET /api/productor/panel': () => [200, copia(DATOS.panelProductor)],
      'PUT /api/auth/me/language': [204, null],
    })
    const user = userEvent.setup()
    abrirEn('/productor')
    await elegir(user, 'English')
    expect(await screen.findByRole('link', { name: 'My store' })).toBeInTheDocument()
  })

  it('el contenido del catálogo se vuelve a pedir en el idioma elegido (CCPIP-166)', async () => {
    const fetchMock = servidor(sinSesion)
    const user = userEvent.setup()
    abrirEn('/')
    await screen.findByRole('heading', { name: /Productos con historia/ })
    const idiomaDe = (llamada) => llamada[1].headers['Accept-Language']
    expect(llamadas(fetchMock, 'GET /api/inicio').map(idiomaDe)).toEqual(['es'])

    await elegir(user, 'English')
    await screen.findByRole('heading', { name: 'Products with a story, straight from the Amazon' })
    await waitFor(() => expect(llamadas(fetchMock, 'GET /api/inicio').map(idiomaDe)).toEqual(['es', 'en']))
  })
})
