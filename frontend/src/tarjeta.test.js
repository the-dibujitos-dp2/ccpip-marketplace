import { describe, expect, it } from 'vitest'
import { formatear, largoCvv, marca, numeroValido, vencimientoValido } from './tarjeta.js'

describe('Reglas de la tarjeta (CCPIP-62)', () => {
  it('reconoce la marca por sus primeros dígitos', () => {
    expect(marca('4111')).toBe('VISA')
    expect(marca('5555 5555')).toBe('MASTERCARD')
    expect(marca('2221 0000')).toBe('MASTERCARD')
    expect(marca('3782')).toBe('AMEX')
    expect(marca('3622')).toBe('DINERS')
    expect(marca('9999')).toBeNull()
  })

  it('agrupa el número como se lee en la tarjeta y no deja escribir de más', () => {
    expect(formatear('4111111111111111')).toBe('4111 1111 1111 1111')
    expect(formatear('378282246310005')).toBe('3782 822463 10005')
    expect(formatear('3782822463100059999')).toBe('3782 822463 10005')
    expect(formatear('5555-5555 5555 4444 12')).toBe('5555 5555 5555 4444')
  })

  it('el CVV tiene 4 dígitos solo en American Express', () => {
    expect(largoCvv('AMEX')).toBe(4)
    expect(largoCvv('VISA')).toBe(3)
    expect(largoCvv(null)).toBe(3)
  })

  it('valida largo y Luhn después de normalizar espacios y guiones', () => {
    expect(numeroValido('4111 1111 1111 1111')).toBe(true)
    expect(numeroValido('4111-1111 1111-1111')).toBe(true)
    expect(numeroValido('4111 1111 1111 1112')).toBe(false)
    expect(numeroValido('4111 1111 1111')).toBe(false)
    expect(numeroValido('378282246310005')).toBe(true)
  })

  it('no acepta un vencimiento ya pasado', () => {
    const hoy = new Date(2026, 9, 14)
    expect(vencimientoValido(10, 2026, hoy)).toBe(true)
    expect(vencimientoValido(9, 2026, hoy)).toBe(false)
    expect(vencimientoValido(1, 2027, hoy)).toBe(true)
  })
})
