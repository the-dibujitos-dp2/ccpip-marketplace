import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { disarmIdleTimer } from '../api/http.js'

afterEach(() => {
  cleanup()
  disarmIdleTimer()
  vi.unstubAllGlobals()
  vi.useRealTimers()
  window.history.replaceState(null, '', '/')
})
