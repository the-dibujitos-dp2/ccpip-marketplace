import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { olvidarUnidadesCarrito } from '../api/carrito.js'
import { disarmIdleTimer } from '../api/http.js'
import i18n from '../i18n/index.js'
import { reiniciarPreferencia } from '../i18n/preferencia.js'
import { invalidarTiendaActual } from '../api/tiendaActual.js'

afterEach(async () => {
  cleanup()
  // Cada prueba empieza en espanol, el idioma por defecto (CCPIP-98)
  window.localStorage.clear()
  reiniciarPreferencia()
  await i18n.changeLanguage('es')
  disarmIdleTimer()
  invalidarTiendaActual()
  olvidarUnidadesCarrito()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  window.history.replaceState(null, '', '/')
})
