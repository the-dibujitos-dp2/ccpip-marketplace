import { describe, expect, it } from 'vitest'
import { apiFetch, ApiError, idRuta } from './http.js'
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

describe('idRuta', () => {
  it('acepta numeros y UUID, y rechaza todo lo demas como inexistente', () => {
    expect(idRuta(42)).toBe('42')
    expect(idRuta('0000000a-0000-4000-8000-000000000000')).toBe('0000000a-0000-4000-8000-000000000000')
    for (const malo of ['0', '-1', '1.5', '../auth/logout', '9%2F..', '', null, undefined, '1 OR 1=1']) {
      expect(() => idRuta(malo)).toThrow(expect.objectContaining({ status: 404 }))
    }
  })
})
