import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook } from '@testing-library/react'

import { invalidarTiendaActual, useTiendaActual } from './tiendaActual.js'
import { olvidarUnidadesCarrito, recargarUnidadesCarrito, useUnidadesCarrito } from './carrito.js'
import { servidor } from '../test/servidor.js'

afterEach(() => { cleanup(); vi.clearAllMocks(); invalidarTiendaActual(); olvidarUnidadesCarrito() })

describe('cachés privados frente a invalidación durante GET', () => {
  it('no permite que un GET tienda pendiente repueble cache después de invalidar', async () => {
    invalidarTiendaActual()
    let resolver
    servidor({ 'GET /api/productor/tienda': () => new Promise((resolve) => { resolver = resolve }) })
    const first = renderHook(() => useTiendaActual())
    await vi.waitFor(() => expect(resolver).toBeDefined())
    invalidarTiendaActual()
    await act(async () => resolver([200, { id: 1, nombreTienda: 'Tienda A' }]))
    expect(first.result.current).toBeNull()
    first.unmount()
    const fetch = servidor({ 'GET /api/productor/tienda': [200, { id: 2, nombreTienda: 'Tienda B' }] })
    const second = renderHook(() => useTiendaActual())
    await vi.waitFor(() => expect(second.result.current).toEqual({ id: 2, nombreTienda: 'Tienda B' }))
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('no publica unidades carrito pendientes al widget tras olvidar identidad', async () => {
    let resolver
    servidor({ 'GET /api/carrito': () => new Promise((resolve) => { resolver = resolve }) })
    const widget = renderHook(() => useUnidadesCarrito(true))
    await vi.waitFor(() => expect(resolver).toBeDefined())
    olvidarUnidadesCarrito()
    await act(async () => resolver([200, { unidades: 17 }]))
    expect(widget.result.current).toBeNull()
    const fetch = servidor({ 'GET /api/carrito': [200, { unidades: 3 }] })
    await act(async () => { await recargarUnidadesCarrito() })
    expect(widget.result.current).toBe(3)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
