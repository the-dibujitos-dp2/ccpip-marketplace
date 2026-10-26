import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useConsulta } from './useConsulta.js'

function pestana(estado) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => estado })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('useConsulta', () => {
  afterEach(() => {
    vi.useRealTimers()
    pestana('visible')
  })

  it('carga al montar y un 401 no cuenta como error de la pantalla', async () => {
    const consultar = vi.fn().mockRejectedValue(Object.assign(new Error('sesion'), { status: 401 }))
    const { result } = renderHook(() => useConsulta(consultar))
    await waitFor(() => expect(consultar).toHaveBeenCalledTimes(1))
    expect(result.current.error).toBeNull()
  })

  it('sin cadaMs no vuelve a consultar sola', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const consultar = vi.fn().mockResolvedValue(1)
    renderHook(() => useConsulta(consultar))
    await act(() => vi.advanceTimersByTimeAsync(60_000))
    expect(consultar).toHaveBeenCalledTimes(1)
  })

  // CCPIP-425: la pantalla se mantiene al dia sin recargar la pagina
  it('con cadaMs repite la consulta, no con la pestana oculta, al volver a ella y nunca tras desmontar', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let n = 0
    const consultar = vi.fn(async () => ++n)
    const { result, unmount } = renderHook(() => useConsulta(consultar, { cadaMs: 15_000 }))
    await waitFor(() => expect(result.current.datos).toBe(1))

    await act(() => vi.advanceTimersByTimeAsync(15_000))
    expect(result.current.datos).toBe(2)

    act(() => pestana('hidden'))
    await act(() => vi.advanceTimersByTimeAsync(45_000))
    expect(consultar).toHaveBeenCalledTimes(2)

    act(() => pestana('visible'))
    await waitFor(() => expect(result.current.datos).toBe(3))

    unmount()
    await act(() => vi.advanceTimersByTimeAsync(60_000))
    expect(consultar).toHaveBeenCalledTimes(3)
  })
})
