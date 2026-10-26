// CW-13 "Mi cuenta" del consumidor: sus datos (CCPIP-400, CCPIP-113) y sus direcciones (CCPIP-65, CCPIP-393). El servidor toma la cuenta de la sesion.
import { apiFetch, idRuta } from './http.js'

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

/**
 * CCPIP-393: las direcciones activas del comprador, la ultima que guardo primero: [{ id, alias, ubigeo, departamento,
 * provincia, distrito, direccion, referencia }]. Solo las propias; las retiradas no llegan.
 */
export const listarDirecciones = () => apiFetch('/api/cuenta/direcciones')

/** CCPIP-395: retira una dirección guardada del consumidor actual; no afecta pedidos históricos. */
export const retirarDireccion = (id) => apiFetch(`/api/cuenta/direcciones/${idRuta(id)}`, { method: 'DELETE' })
