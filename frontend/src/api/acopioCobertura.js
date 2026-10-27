import { apiFetch } from './http.js'

/** CCPIP-401: zonas de cobertura declaradas por los puntos asignados a la sesión. */
export const consultarCoberturasAcopio = () => apiFetch('/api/acopio/coberturas')
