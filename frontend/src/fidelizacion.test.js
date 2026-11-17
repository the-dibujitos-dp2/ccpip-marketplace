import { describe, expect, it } from 'vitest'
import { multiplicador, puntosDe, puntosEnCampana } from './fidelizacion.js'

const BASE = { puntos: 1, cadaSoles: 10 }

describe('CCPIP-86 calculo de puntos', () => {
  it('la regla base da 1 punto por cada S/ 10, redondeando hacia abajo', () => {
    expect(puntosDe(85.5, BASE)).toBe(8)
    expect(puntosDe(9.9, BASE)).toBe(0)
    expect(puntosDe(120, BASE)).toBe(12)
    expect(puntosDe('', BASE)).toBe(0)
  })

  it('una campana cuenta cada tramo completo y respeta su tope', () => {
    expect(puntosDe(85.5, { puntos: 10, cadaSoles: 1 })).toBe(850)
    expect(puntosDe(85.5, { puntos: 10, cadaSoles: 1, tope: 500 })).toBe(500)
    expect(puntosDe(0.3 + 0.7, { puntos: 1, cadaSoles: 1 })).toBe(1)
  })

  it('debajo del minimo de la campana rige la base', () => {
    const campana = { puntos: 10, cadaSoles: '1', montoMinimo: '100', topePuntos: null }
    expect(puntosEnCampana(85.5, campana, BASE)).toBe(8)
    expect(puntosEnCampana(150, campana, BASE)).toBe(1500)
  })

  it('compara la campana con la base por puntos por sol', () => {
    expect(multiplicador({ puntos: 2, cadaSoles: 10 }, BASE)).toBe(2)
    expect(multiplicador({ puntos: 10, cadaSoles: 1 }, BASE)).toBe(100)
  })
})
