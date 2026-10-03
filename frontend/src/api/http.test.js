import { describe, expect, it } from 'vitest'
import { apiFetch, ApiError } from './http.js'
import { servidor } from '../test/servidor.js'

describe('apiFetch', () => {
  it('204 devuelve null y los errores traen codigo y estado', async () => {
    servidor({ 'POST /api/x': [204], 'GET /api/y': [409, { code: 'CONFLICTO', error: 'Ya existe' }] })
    await expect(apiFetch('/api/x', { method: 'POST' })).resolves.toBeNull()

    const error = await apiFetch('/api/y').catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, code: 'CONFLICTO', message: 'Ya existe' })
  })

  it('un error sin cuerpo usa un mensaje generico', async () => {
    servidor({ 'GET /api/z': [500] })
    await expect(apiFetch('/api/z')).rejects.toThrow('Error 500')
  })
})
