// CW-13 "Mis datos" del consumidor (CCPIP-400, CCPIP-113). El servidor toma la cuenta de la sesion.
import { apiFetch } from './http.js'

/** { nombres, apellidos, correo, correoConfirmado, celular }. Sin genero ni fecha de nacimiento. */
export const obtenerMisDatos = () => apiFetch('/api/cuenta/datos')

/** `datos`: { nombres, apellidos, celular }. El correo no se cambia aqui. */
export const guardarMisDatos = (datos) => apiFetch('/api/cuenta/datos', { method: 'PUT', body: datos })

/**
 * CCPIP-65: guarda una direccion de entrega habitual del comprador. `direccion`: { alias, ubigeo, direccion,
 * referencia }; el comprador lo pone el servidor desde la sesion. Devuelve la direccion con su distrito,
 * provincia y departamento.
 */
export const guardarDireccionHabitual = (direccion) => apiFetch('/api/cuenta/direcciones', { method: 'POST', body: direccion })
