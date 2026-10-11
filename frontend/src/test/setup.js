import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { olvidarUnidadesCarrito } from '../api/carrito.js'
import { disarmIdleTimer } from '../api/http.js'
import { invalidarTiendaActual } from '../api/tiendaActual.js'

afterEach(() => {
  cleanup()
  disarmIdleTimer()
  invalidarTiendaActual()
  olvidarUnidadesCarrito()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  window.history.replaceState(null, '', '/')
})
