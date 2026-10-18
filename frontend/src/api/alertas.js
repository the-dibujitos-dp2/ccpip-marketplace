// Alertas del productor (PW-21) y del punto de acopio (A-08): CCPIP-79, 145, 293, 337, 366 y 367.
// `rol` es 'productor' o 'acopio'. El servidor filtra por el productor de la sesion o por los puntos
// asignados al administrador (RNF-05).
import { apiFetch, idRuta } from './http.js'

const base = (rol) => (rol === 'acopio' ? '/api/acopio/alertas' : '/api/productor/alertas')

/** { alertas: [...], sinLeer } con las alertas abiertas y las resueltas de los ultimos 60 dias. */
export const listarAlertas = (rol) => apiFetch(base(rol))

export const marcarLeida = (rol, id) => apiFetch(`${base(rol)}/${idRuta(id)}/leida`, { method: 'POST' })

export const marcarTodasLeidas = (rol) => apiFetch(`${base(rol)}/leidas`, { method: 'POST' })

/** Productor: sus preferencias. Punto: [{ ubicacionId, punto, preferencias }] de cada punto suyo. */
export const consultarPreferencias = (rol) => apiFetch(`${base(rol)}/preferencias`)

/** `datos`: { diasVencimiento, diasEstancado, tipos, correo }. El punto indica cual configura. */
export const guardarPreferencias = (rol, datos, ubicacionId) =>
  apiFetch(rol === 'acopio' ? `${base(rol)}/preferencias/${idRuta(ubicacionId)}` : `${base(rol)}/preferencias`, {
    method: 'PUT',
    body: datos,
  })
