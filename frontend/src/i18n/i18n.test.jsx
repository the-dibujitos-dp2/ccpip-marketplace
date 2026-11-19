import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import PieDePagina from '../components/layout/PieDePagina.jsx'
import SelloOrigen from '../components/SelloOrigen.jsx'
import imagenReferencial from '../components/imagenProductoI18n.js'
import accessEditorial from './accessEditorial.js'
import editorialEs from './editorial/es.json'
import editorialPt from './editorial/pt.json'
import en from './en.json'
import es from './es.json'
import pt from './pt.json'
import i18n, { guardarIdioma, idiomaContenido, idiomaGuardado, localeFechas } from './index.js'

/** Todas las claves de un diccionario, como rutas "a.b.c". */
function claves(objeto, prefijo = '') {
  return Object.entries(objeto).flatMap(([k, v]) =>
    v && typeof v === 'object' ? claves(v, `${prefijo}${k}.`) : [`${prefijo}${k}`],
  )
}

/** Variables {{x}} de un texto, para comparar que ambos idiomas usen las mismas. */
const variables = (texto) => [...texto.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort()

/** Las etiquetas <strong> de un texto con Trans: la traduccion debe conservarlas. */
const etiquetas = (texto) => [...texto.matchAll(/<\/?\w+>/g)].map((m) => m[0]).sort()
const plano = (o) => Object.fromEntries(claves(o).map((c) => [c, c.split('.').reduce((a, k) => a[k], o)]))

// CCPIP-98 (ingles) y CCPIP-512 (portugues): cada traduccion frente al espanol original
describe.each([['inglés', en], ['portugués', pt]])('i18n en %s', (idioma, traduccion) => {
  it(`español e ${idioma} tienen exactamente las mismas claves`, () => {
    expect(claves(traduccion).sort()).toEqual(claves(es).sort())
  })

  it('ningún texto queda vacío y usa las mismas variables y etiquetas que el español', () => {
    const textosEs = plano(es)
    const textos = plano(traduccion)
    for (const clave of Object.keys(textosEs)) {
      expect(textosEs[clave].trim(), clave).not.toBe('')
      expect(textos[clave].trim(), clave).not.toBe('')
      expect(variables(textos[clave]), clave).toEqual(variables(textosEs[clave]))
      expect(etiquetas(textos[clave]), clave).toEqual(etiquetas(textosEs[clave]))
    }
  })
})

describe('i18n (CCPIP-98)', () => {
  it('los textos del rediseño editorial también están en portugués (CCPIP-512)', () => {
    expect(claves(editorialPt).sort()).toEqual(claves(editorialEs).sort())
    expect(Object.keys(accessEditorial.pt).sort()).toEqual(Object.keys(accessEditorial.es).sort())
    expect(Object.keys(imagenReferencial.pt).sort()).toEqual(Object.keys(imagenReferencial.es).sort())
  })

  it('arranca en español y marca el idioma del documento', () => {
    expect(i18n.resolvedLanguage).toBe('es')
    expect(document.documentElement.lang).toBe('es')
    expect(localeFechas()).toBe('es-PE')
  })

  it('al cambiar a inglés traduce la interfaz y el atributo lang', async () => {
    await i18n.changeLanguage('en')
    const sellos = [{ tipo: 'ORIGEN_VERIFICADO', titulo: 'Comunidad Shipibo Konibo', emisor: 'Ucayali', desde: null, hasta: null }]
    render(<><PieDePagina /><SelloOrigen variante="completo" sellos={sellos} /></>)
    expect(screen.getByText('Explore products')).toBeInTheDocument()
    expect(screen.getByText('Origin verified by CCPIP')).toBeInTheDocument()
    expect(document.documentElement.lang).toBe('en')
    expect(localeFechas()).toBe('en-GB')
  })

  it('una clave que falte en inglés se ve en español, nunca como la clave', async () => {
    await i18n.changeLanguage('en')
    i18n.addResource('es', 'translation', 'prueba.soloEspanol', 'Solo en español')
    expect(i18n.t('prueba.soloEspanol')).toBe('Solo en español')
  })

  it('CCPIP-512: en portugués traduce la interfaz, marca el idioma y usa fechas de Brasil', async () => {
    await i18n.changeLanguage('pt')
    const sellos = [{ tipo: 'ORIGEN_VERIFICADO', titulo: 'Comunidad Shipibo Konibo', emisor: 'Ucayali', desde: null, hasta: null }]
    render(<><PieDePagina /><SelloOrigen variante="completo" sellos={sellos} /></>)
    expect(screen.getByText('Explorar produtos')).toBeInTheDocument()
    expect(screen.getByText('Origem verificada pela CCPIP')).toBeInTheDocument()
    expect(document.documentElement.lang).toBe('pt')
    expect(localeFechas()).toBe('pt-BR')
    expect(idiomaContenido()).toBe('es')
  })

  it('CCPIP-512: sin traducción al portugués se ve en español, nunca como la clave', async () => {
    await i18n.changeLanguage('pt')
    i18n.addResource('es', 'translation', 'prueba.soloEspanolPt', 'Solo en español')
    expect(i18n.t('prueba.soloEspanolPt')).toBe('Solo en español')
  })

  it('el contenido del servidor se pide en inglés solo con la interfaz en inglés', async () => {
    await i18n.changeLanguage('en')
    expect(idiomaContenido()).toBe('en')
    await i18n.changeLanguage('es')
    expect(idiomaContenido()).toBe('es')
  })

  describe('preferencia en el navegador', () => {
    it('guarda y lee el idioma elegido', () => {
      expect(idiomaGuardado()).toBeNull()
      guardarIdioma('en')
      expect(idiomaGuardado()).toBe('en')
    })

    it('recuerda el portugués (CCPIP-512)', () => {
      guardarIdioma('pt')
      expect(idiomaGuardado()).toBe('pt')
    })

    it('ignora un valor que no es un idioma de la plataforma', () => {
      window.localStorage.setItem('ccpip.idioma', 'fr')
      expect(idiomaGuardado()).toBeNull()
    })

    it('si el almacenamiento está bloqueado no rompe la página', () => {
      const bloqueado = () => {
        throw new Error('SecurityError')
      }
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(bloqueado)
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(bloqueado)
      expect(() => guardarIdioma('en')).not.toThrow()
      expect(idiomaGuardado()).toBeNull()
      vi.restoreAllMocks()
    })
  })
})
