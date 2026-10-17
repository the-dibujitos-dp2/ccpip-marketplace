import { afterEach, describe, expect, it, vi } from 'vitest'
import { MOTIVOS_ALMACEN, MOTIVOS_PUNTO, nuevaClave } from './movimientos.js'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

describe('CCPIP-121, CCPIP-287 y CCPIP-283 claves y motivos de un movimiento', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('cada apertura del formulario tiene su propia clave UUID', () => {
    const a = nuevaClave()
    const b = nuevaClave()
    expect(a).toMatch(UUID)
    expect(b).toMatch(UUID)
    expect(a).not.toBe(b)
  })

  it('sin randomUUID arma un UUID v4 con getRandomValues', () => {
    vi.stubGlobal('crypto', { getRandomValues: (b) => b.fill(0xff) })
    expect(nuevaClave()).toBe('ffffffff-ffff-4fff-bfff-ffffffffffff')
  })

  it('el punto y el almacen tienen su propio catalogo de motivos, sin ajuste ni merma', () => {
    expect(MOTIVOS_PUNTO).toEqual({ ENTRADA: ['RECEPCION'], SALIDA: ['DEVOLUCION_PRODUCTOR'] })
    expect(MOTIVOS_ALMACEN).toEqual({ ENTRADA: ['PRODUCCION'], SALIDA: ['RETIRO_PRODUCTOR'] })
  })
})
