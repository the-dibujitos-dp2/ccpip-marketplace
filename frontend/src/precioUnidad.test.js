import { describe, expect, it } from 'vitest'
import i18n from './i18n/index.js'
import { porUnidad, precioPorUnidad, unidadDeVenta } from './precioUnidad.js'

const t = i18n.getFixedT('es')
const en = i18n.getFixedT('en')

describe('CCPIP-494 Precio y unidad de venta en un formato único', () => {
  it('normaliza el contenido que es solo una cantidad de unidades', () => {
    for (const contenido of ['5', '5 und', '5 unidades', '5u', '5 u.', '5 Unid']) {
      expect(unidadDeVenta(t, { contenido })).toBe('5 und')
    }
    expect(unidadDeVenta(t, { contenido: '1,5' })).toBe('1.5 und')
  })

  it('respeta otro contenido y, sin contenido, usa la unidad de medida', () => {
    expect(unidadDeVenta(t, { contenido: '250 ml' })).toBe('250 ml')
    expect(unidadDeVenta(t, { contenido: '  ', unidad: 'Bolsa' })).toBe('bolsa')
    expect(unidadDeVenta(t, { unidad: { codigo: 'SACO', nombre: 'Saco' } })).toBe('saco')
    expect(unidadDeVenta(t, {})).toBe('unidad')
  })

  it('arma "S/ 20.00 por 5 und" igual para "20 por 5" y "16 por 5 und"', () => {
    expect(precioPorUnidad(t, 20, { contenido: '5' }).replace(/\s/g, ' ')).toBe('S/ 20.00 por 5 und')
    expect(precioPorUnidad(t, 16, { contenido: '5 und' }).replace(/\s/g, ' ')).toBe('S/ 16.00 por 5 und')
    expect(porUnidad(t, { contenido: '250 ml' })).toBe('por 250 ml')
    expect(porUnidad(en, { contenido: '5' })).toBe('per 5 units')
  })
})
