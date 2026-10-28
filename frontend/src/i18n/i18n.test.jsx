import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import PieDePagina from '../components/layout/PieDePagina.jsx'
import SelloOrigen from '../components/SelloOrigen.jsx'
import en from './en.json'
import es from './es.json'
import i18n, { guardarIdioma, idiomaGuardado, localeFechas } from './index.js'

/** Todas las claves de un diccionario, como rutas "a.b.c". */
function claves(objeto, prefijo = '') {
  return Object.entries(objeto).flatMap(([k, v]) =>
    v && typeof v === 'object' ? claves(v, `${prefijo}${k}.`) : [`${prefijo}${k}`],
  )
}

/** Variables {{x}} de un texto, para comparar que ambos idiomas usen las mismas. */
const variables = (texto) => [...texto.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort()

describe('i18n (CCPIP-98)', () => {
  it('español e inglés tienen exactamente las mismas claves', () => {
    expect(claves(en).sort()).toEqual(claves(es).sort())
  })

  it('ningún texto queda vacío y ambos idiomas usan las mismas variables', () => {
    const plano = (o) => Object.fromEntries(claves(o).map((c) => [c, c.split('.').reduce((a, k) => a[k], o)]))
    const textosEs = plano(es)
    const textosEn = plano(en)
    for (const clave of Object.keys(textosEs)) {
      expect(textosEs[clave].trim(), clave).not.toBe('')
      expect(textosEn[clave].trim(), clave).not.toBe('')
      expect(variables(textosEn[clave]), clave).toEqual(variables(textosEs[clave]))
    }
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

  describe('preferencia en el navegador', () => {
    it('guarda y lee el idioma elegido', () => {
      expect(idiomaGuardado()).toBeNull()
      guardarIdioma('en')
      expect(idiomaGuardado()).toBe('en')
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
