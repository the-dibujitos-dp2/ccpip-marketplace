import { describe, expect, it } from 'vitest'
import { capital, diaMes, fecha, fechaCorta, mesAnio, mesAnterior, precio, unidades } from './formato'
import i18n from './i18n/index.js'

// Intl separa la moneda con un espacio no separable (U+00A0): se normaliza para comparar
const normal = (texto) => texto.replace(/ /g, ' ')

describe('formato', () => {
  describe('precio', () => {
    it('da formato de soles con dos decimales', () => {
      expect(normal(precio(46))).toBe('S/ 46.00')
    })

    it('acepta números que llegan como texto desde la API', () => {
      expect(normal(precio('12.5'))).toBe('S/ 12.50')
    })

    it('separa los miles', () => {
      expect(normal(precio(1234.5))).toBe('S/ 1,234.50')
    })
  })

  describe('fechas ISO', () => {
    it('fecha abrevia el mes sin punto', () => {
      expect(fecha('2026-10-04')).toBe('4 oct 2026')
    })

    it('fechaCorta usa dd/mm/aaaa', () => {
      expect(fechaCorta('2026-11-20')).toBe('20/11/2026')
    })

    it('mesAnio escribe el mes completo', () => {
      expect(mesAnio('2026-10-04')).toBe('octubre de 2026')
    })

    it('no se corre un día por la zona horaria', () => {
      expect(fechaCorta('2026-01-01')).toBe('01/01/2026')
      expect(fecha('2026-01-01')).toBe('1 ene 2026')
    })

    it('mesAnterior nombra el mes previo al periodo, también al cruzar el año', () => {
      expect(mesAnterior('2026-10')).toBe('setiembre')
      expect(mesAnterior('2027-01')).toBe('diciembre')
    })

    it('diaMes usa el dia de Lima aunque en UTC ya sea el siguiente', () => {
      expect(diaMes('2026-11-17T03:30:00Z')).toBe('16 nov')
    })

    it.each([fecha, fechaCorta, mesAnio, mesAnterior, diaMes])('%o devuelve cadena vacía sin valor', (fn) => {
      expect(fn(null)).toBe('')
      expect(fn(undefined)).toBe('')
      expect(fn('')).toBe('')
    })
  })

  describe('capital', () => {
    it('pone mayúscula inicial y respeta el resto', () => {
      expect(capital('octubre de 2026')).toBe('Octubre de 2026')
    })

    it('devuelve cadena vacía sin texto', () => {
      expect(capital('')).toBe('')
      expect(capital(undefined)).toBe('')
    })
  })

  describe('en inglés (CCPIP-98)', () => {
    it('mantiene los soles y escribe las fechas en inglés', async () => {
      await i18n.changeLanguage('en')
      expect(normal(precio(1234.5))).toBe('S/ 1,234.50')
      expect(mesAnio('2026-10-04')).toBe('October 2026')
      expect(fecha('2026-10-04')).toBe('4 Oct 2026')
      expect(fechaCorta('2026-11-20')).toBe('20/11/2026')
    })
  })

  describe('unidades', () => {
    it('muestra hasta tres decimales', () => {
      expect(unidades('12.5000')).toBe('12.5')
      expect(unidades(3)).toBe('3')
    })
  })
})
