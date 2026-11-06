import { describe, expect, it } from 'vitest'
import { capital, claseSigno, coincide, esNegativo, diaMes, diaMesAnio, diaMesAnioHora, diaMesAnioLargo, diaMesHora, diaMesLargoHora, fecha, fechaCorta, fechaHoraLarga, hora, mesAnio, mesAnterior, precio, primeraOracion, unidades } from './formato'
import i18n from './i18n/index.js'

// Intl separa la moneda con un espacio no separable (U+00A0): se normaliza para comparar
const normal = (texto) => texto.replace(/ /g, ' ')

describe('formato', () => {
  describe('coincide (CCPIP-417)', () => {
    it('sin nada buscado, todo coincide', () => {
      expect(coincide('', 'Cacao')).toBe(true)
      expect(coincide('   ', undefined)).toBe(true)
    })

    it('ignora mayúsculas, tildes y espacios de más, en cualquiera de los textos', () => {
      expect(coincide('  CAFÉ ', 'MC-00131', 'Cafe tostado en grano 500 g')).toBe(true)
      expect(coincide('rio ene', 'Coop. Río Ene')).toBe(true)
      expect(coincide('ñandu', 'Ñandú')).toBe(true)
    })

    it('no coincide si ningún texto lo contiene, y tolera textos vacíos', () => {
      expect(coincide('aguaje', 'Cacao', null, undefined)).toBe(false)
    })
  })

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

    it('fechaHoraLarga escribe dia, mes y hora de Lima (CCPIP-142)', () => {
      expect(fechaHoraLarga('2026-10-17T19:30:00Z')).toBe('17 de octubre, 14:30')
      expect(fechaHoraLarga('2026-11-17T03:30:00Z')).toBe('16 de noviembre, 22:30')
    })

    it('diaMesAnio, hora y diaMesHora usan el dia y la hora de Lima (mejoras de laboratorio)', () => {
      expect(diaMesAnio('2026-11-17T03:30:00Z')).toBe('16 nov 2026')
      expect(hora('2026-11-17T03:30:00Z')).toBe('22:30')
      expect(diaMesHora('2026-11-16T14:12:00Z')).toBe('16 nov 09:12')
      expect(diaMesAnioHora('2026-11-16T14:12:00Z')).toBe('16 nov 2026 09:12')
    })

    it('con el mes completo y "de": historial (CCPIP-445) y pago del pedido (CCPIP-440)', () => {
      expect(diaMesLargoHora('2026-10-30T01:35:00Z')).toBe('29 de octubre 20:35')
      expect(diaMesAnioLargo('2026-12-03T15:00:00Z')).toBe('3 de dic. 2026')
    })

    it.each([fecha, fechaCorta, mesAnio, mesAnterior, diaMes, diaMesAnio, hora, diaMesHora, diaMesAnioHora, diaMesLargoHora, diaMesAnioLargo, fechaHoraLarga])('%o devuelve cadena vacía sin valor', (fn) => {
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
      expect(fechaHoraLarga('2026-10-17T19:30:00Z')).toBe('17 October, 14:30')
      expect(diaMesAnio('2026-11-17T03:30:00Z')).toBe('16 Nov 2026')
      expect(diaMesHora('2026-11-16T14:12:00Z')).toBe('16 Nov 09:12')
      expect(diaMesLargoHora('2026-10-30T01:35:00Z')).toBe('29 October 20:35')
      expect(diaMesAnioLargo('2026-12-03T15:00:00Z')).toBe('3 December 2026')
    })
  })

  describe('unidades', () => {
    it('muestra hasta tres decimales', () => {
      expect(unidades('12.5000')).toBe('12.5')
      expect(unidades(3)).toBe('3')
    })
  })

  describe('primeraOracion', () => {
    it('corta en el primer punto', () => {
      expect(primeraOracion('Crece bajo sombra. Se fermenta seis dias.')).toBe('Crece bajo sombra.')
    })

    it('sin una oracion corta, recorta y agrega puntos suspensivos', () => {
      expect(primeraOracion('a'.repeat(200))).toBe(`${'a'.repeat(140)}…`)
    })
  })
})

describe('valores negativos en rojo (CCPIP-454)', () => {
  it('reconoce numeros y textos formateados con signo menos', () => {
    expect(esNegativo(-0.5)).toBe(true)
    expect(esNegativo(0)).toBe(false)
    expect(esNegativo(precio(-12))).toBe(true)
    expect(esNegativo(unidades(-3.5))).toBe(true)
    expect(esNegativo('−2')).toBe(true)
    expect(esNegativo('- 4 und')).toBe(true)
    expect(esNegativo(precio(12))).toBe(false)
    expect(esNegativo('+100')).toBe(false)
  })

  it('no confunde fechas, guiones de texto ni valores vacios', () => {
    expect(esNegativo('2026-10-04')).toBe(false)
    expect(esNegativo('—')).toBe(false)
    expect(esNegativo('Café - tostado')).toBe(false)
    expect(esNegativo(null)).toBe(false)
    expect(esNegativo(undefined)).toBe(false)
  })

  it('da la clase solo a los negativos', () => {
    expect(claseSigno(precio(-1))).toBe(' valor-negativo')
    expect(claseSigno(precio(1))).toBe('')
  })
})
