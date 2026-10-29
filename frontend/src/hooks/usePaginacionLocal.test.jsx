import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import usePaginacionLocal from './usePaginacionLocal.js'

describe('usePaginacionLocal', () => {
  it('slices 21 items and reports exact count across pages', () => {
    const items = Array.from({ length: 21 }, (_, id) => id)
    const { result } = renderHook(() => usePaginacionLocal(items, 'all'))
    expect(result.current.filas).toHaveLength(20)
    expect(result.current.total).toBe(21)
    act(() => result.current.onCambiar(1))
    expect(result.current.filas).toEqual([20])
    expect(result.current.pagina).toBe(1)
  })

  it('resets the current page when the filter key changes', () => {
    const items = Array.from({ length: 21 }, (_, id) => id)
    const { result, rerender } = renderHook(({ filtro }) => usePaginacionLocal(items, filtro), { initialProps: { filtro: 'all' } })
    act(() => result.current.onCambiar(1))
    rerender({ filtro: 'filtered' })
    expect(result.current.pagina).toBe(0)
    expect(result.current.filas[0]).toBe(0)
  })

  it('clamps the current page when rerendered with fewer items', () => {
    const { result, rerender } = renderHook(({ items }) => usePaginacionLocal(items, 'fixed'), {
      initialProps: { items: Array.from({ length: 21 }, (_, id) => id) },
    })
    act(() => result.current.onCambiar(1))
    rerender({ items: [99] })
    expect(result.current).toMatchObject({ filas: [99], pagina: 0, total: 1, totalPaginas: 1 })
  })

  it('keeps empty results at page zero with an exact total', () => {
    const { result } = renderHook(() => usePaginacionLocal([], 'empty'))
    expect(result.current).toMatchObject({ filas: [], pagina: 0, total: 0, totalPaginas: 1 })
  })
})
