import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import App from '../../App.jsx'
import i18n from '../../i18n/index.js'
import { copia, DATOS, PUBLICAS, servidor, SIN_SESION } from '../../test/servidor.js'

// CCPIP-171 (RF-163) y CCPIP-373: el comprador distingue la traduccion del original, puede leer el
// original y ve un aviso cuando la traduccion quedo desactualizada. Respuestas segun
// CatalogoModelos.CampoTraducido del backend.
function abrirEn(ruta) {
  window.history.replaceState(null, '', ruta)
  return render(<App />)
}

const sinSesion = { ...PUBLICAS, 'GET /api/auth/me': SIN_SESION }

function fichaEnIngles(traducciones) {
  return {
    ...copia(DATOS.detalle),
    nombre: 'Fermented cacao beans 1 kg',
    descripcion: 'Fermented six days in wooden boxes.',
    historia: { texto: 'It grows in the shade of primary forest, without deforestation.', asistida: false },
    traducciones,
  }
}

describe('Ficha traducida (CCPIP-171)', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('etiqueta la traducción de la comunidad y alterna al original con "Ver original"', async () => {
    servidor({
      ...sinSesion,
      'GET /api/productos/1': [200, fichaEnIngles({
        nombre: { origen: 'COMUNIDAD', vigente: true, original: 'Cacao en grano fermentado 1 kg' },
        descripcion: { origen: 'COMUNIDAD', vigente: true, original: 'Fermentado seis dias en cajones de madera.' },
        'historia.texto': { origen: 'AUTOMATICA', vigente: true, original: 'Crece bajo sombra de bosque primario, sin deforestar.' },
      })],
    })
    const user = userEvent.setup()
    abrirEn('/productos/1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Fermented cacao beans 1 kg' }, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.getByText('Fermented six days in wooden boxes.')).toBeInTheDocument()
    expect(screen.getByText('Translated by the community')).toBeInTheDocument()
    // La historia es de otro origen y tiene su propia etiqueta
    const historia = screen.getByRole('region', { name: 'From the forest to your table' })
    expect(within(historia).getByText('Machine translation')).toBeInTheDocument()

    const [verFicha] = screen.getAllByRole('button', { name: 'View original' })
    await user.click(verFicha)
    expect(screen.getByRole('heading', { level: 1, name: 'Cacao en grano fermentado 1 kg' })).toHaveAttribute('lang', 'es')
    expect(screen.getByText('Fermentado seis dias en cajones de madera.')).toBeInTheDocument()
    expect(screen.getByText('Original text in Spanish')).toBeInTheDocument()
    // La historia sigue traducida hasta que se pida su original
    expect(within(historia).getByText('It grows in the shade of primary forest, without deforestation.')).toBeInTheDocument()
    expect(screen.queryByText('Fermented six days in wooden boxes.')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'View translation' }))
    expect(screen.getByRole('heading', { level: 1, name: 'Fermented cacao beans 1 kg' })).not.toHaveAttribute('lang')

    await user.click(within(historia).getByRole('button', { name: 'View original' }))
    expect(within(historia).getByText('Crece bajo sombra de bosque primario, sin deforestar.')).toHaveAttribute('lang', 'es')
  })

  it('avisa que la traducción quedó desactualizada y muestra el original', async () => {
    servidor({
      ...sinSesion,
      'GET /api/productos/1': [200, {
        ...fichaEnIngles({
          nombre: { origen: 'COMUNIDAD', vigente: true, original: 'Cacao en grano fermentado 1 kg' },
          descripcion: { origen: 'COMUNIDAD', vigente: false },
        }),
        descripcion: 'Fermentado siete dias en cajones de madera.',
      }],
    })
    abrirEn('/productos/1')
    expect(await screen.findByText('Fermentado siete dias en cajones de madera.')).toHaveAttribute('lang', 'es')
    expect(screen.getByText(/its translation is out of date/)).toBeInTheDocument()
    // El nombre sigue traducido y se puede ver su original
    expect(screen.getByText('Translated by the community')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'View original' })).toBeInTheDocument()
  })

  it('sin traducciones (o en español) no muestra etiquetas', async () => {
    await i18n.changeLanguage('es')
    servidor({ ...sinSesion, 'GET /api/productos/1': [200, copia(DATOS.detalle)] })
    abrirEn('/productos/1')
    expect(await screen.findByRole('heading', { level: 1, name: DATOS.detalle.nombre })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ver original' })).not.toBeInTheDocument()
    expect(screen.queryByText('Traducción automática')).not.toBeInTheDocument()
  })
})

describe('Tienda traducida (CCPIP-171)', () => {
  it('etiqueta la historia de la comunidad y permite leer el original', async () => {
    await i18n.changeLanguage('en')
    const tienda = copia(DATOS.tienda)
    servidor({
      ...sinSesion,
      'GET /api/tiendas/2': [200, {
        ...tienda,
        cita: 'We plant cacao where there was once logging.',
        historia: 'Our cacao grows in the shade of the forest.',
        traducciones: {
          cita: { origen: 'AUTOMATICA', vigente: true, original: tienda.cita },
          historia: { origen: 'AUTOMATICA', vigente: true, original: tienda.historia },
        },
      }],
    })
    const user = userEvent.setup()
    abrirEn('/tiendas/2')
    expect(await screen.findByText('Our cacao grows in the shade of the forest.')).toBeInTheDocument()
    expect(screen.getByText('Machine translation')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'View original' }))
    expect(screen.getByText(tienda.historia)).toHaveAttribute('lang', 'es')
    expect(screen.getByText(`“${tienda.cita}”`)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'View translation' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('en español se ve igual que antes', async () => {
    servidor({ ...sinSesion, 'GET /api/tiendas/2': [200, copia(DATOS.tienda)] })
    abrirEn('/tiendas/2')
    expect(await screen.findByText(`“${DATOS.tienda.cita}”`)).not.toHaveAttribute('lang')
    expect(screen.queryByRole('note', { name: 'Contenido traducido' })).not.toBeInTheDocument()
  })
})
