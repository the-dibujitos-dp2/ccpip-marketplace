import { describe, expect, it } from 'vitest'
import { resolverTarifa } from './tarifa.js'

// CCPIP-134 (RF-108): mismo criterio que el servidor (pe.ccpip.carrito.Tarifas)
const oferta = (modalidad) => ({
  modalidad,
  precio: modalidad === 'MAYORISTA' ? null : 18,
  precioMayorista: modalidad === 'MINORISTA' ? null : 14.5,
  cantidadMinimaMayorista: modalidad === 'MINORISTA' ? null : 24,
})

describe('resolverTarifa (CCPIP-134)', () => {
  it('una oferta minorista siempre usa el precio minorista, sin faltante', () => {
    expect(resolverTarifa(oferta('MINORISTA'), '30')).toEqual({ tarifa: 'MINORISTA', precio: 18, comprable: true, faltante: null })
  })

  it('con ambas, bajo el minimo el minorista y cuanto falta; desde el minimo el mayorista', () => {
    expect(resolverTarifa(oferta('AMBAS'), '12')).toEqual({ tarifa: 'MINORISTA', precio: 18, comprable: true, faltante: 12 })
    expect(resolverTarifa(oferta('AMBAS'), '24')).toEqual({ tarifa: 'MAYORISTA', precio: 14.5, comprable: true, faltante: 0 })
    expect(resolverTarifa(oferta('AMBAS'), 30).faltante).toBe(0)
  })

  it('solo mayorista bajo el minimo no se puede comprar', () => {
    expect(resolverTarifa(oferta('MAYORISTA'), '12')).toEqual({ tarifa: 'MAYORISTA', precio: 14.5, comprable: false, faltante: 12 })
    expect(resolverTarifa(oferta('MAYORISTA'), '24').comprable).toBe(true)
  })

  it('una cantidad invalida cuenta como cero', () => {
    expect(resolverTarifa(oferta('AMBAS'), '').faltante).toBe(24)
    expect(resolverTarifa(oferta('AMBAS'), '1.5').faltante).toBe(24)
  })

  it('sin precio aplicable no hay tarifa', () => {
    expect(resolverTarifa({ modalidad: 'MAYORISTA', precio: null, precioMayorista: 10, cantidadMinimaMayorista: null }, '5'))
      .toEqual({ tarifa: null, precio: null, comprable: false, faltante: null })
  })
})
