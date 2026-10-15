// API del superadministrador. Solo informacion agregada: el servidor valida el rol (CCPIP-111, RNF-04).
import { apiFetch } from './http.js'

/** CCPIP-111 (SA-01): ventas, comision, productores, lineas y puntos de acopio del mes en curso. */
export const obtenerTablero = () => apiFetch('/api/admin/tablero')
