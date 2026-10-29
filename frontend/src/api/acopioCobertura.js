import { apiFetch } from './http.js'

/** CCPIP-401: zonas de cobertura declaradas por los puntos asignados a la sesión. */
export const consultarCoberturasAcopio = () => apiFetch('/api/acopio/coberturas')

export const desactivarCoberturaAcopio = (zonaId) => apiFetch(`/api/acopio/coberturas/${zonaId}/desactivar`, { method: 'PATCH' })

/** CCPIP-149: declara que el punto entrega en esos distritos (ubigeo) del catalogo territorial. */
export const declararCoberturaAcopio = (puntoId, ubigeos) =>
  apiFetch('/api/acopio/coberturas', { method: 'POST', body: { puntoId, ubigeos } })
