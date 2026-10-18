// Calificaciones (CCPIP-81, CCPIP-82) y denuncias (CCPIP-83, CCPIP-84, CCPIP-116, CCPIP-323).
// El servidor nunca devuelve la identidad del comprador que califica o denuncia (RNF-41).
import { apiFetch, idRuta } from './http.js'

/** CCPIP-81: lineas del pedido propio con su calificacion; { calificable, pendientes, lineas }. */
export const calificacionesDelPedido = (pedidoId) => apiFetch(`/api/pedidos/${idRuta(pedidoId)}/calificaciones`)

/** CCPIP-81: califica una linea de un pedido recibido, una sola vez. */
export const calificarLinea = (pedidoId, { itemId, estrellas, comentario }) =>
  apiFetch(`/api/pedidos/${idRuta(pedidoId)}/calificaciones`, {
    method: 'POST',
    body: { itemId, estrellas, comentario: comentario?.trim() || undefined },
  })

/** CCPIP-82: { total, minimo, suficiente, promedio?, distribucion?, resenas? }. Publico. */
export const resumenCalificaciones = (productoId) =>
  apiFetch(`/api/productos/${idRuta(productoId)}/calificaciones`, { sessionRequired: false })

/** CCPIP-83: denuncia sobre un producto ({ productoId }) o sobre una comunidad ({ productorId }). */
export const presentarDenuncia = ({ productoId, productorId, motivo, descripcion }) =>
  apiFetch('/api/denuncias', { method: 'POST', body: { productoId, productorId, motivo, descripcion: descripcion.trim() } })

export const misDenuncias = () => apiFetch('/api/denuncias')

/** CCPIP-116: denuncias recibidas por el productor de la sesion, ya anonimizadas. */
export const denunciasRecibidas = () => apiFetch('/api/productor/denuncias')

/** CCPIP-323: el descargo del productor, una vez por denuncia. */
export const responderDenuncia = (id, texto) =>
  apiFetch(`/api/productor/denuncias/${idRuta(id)}/respuesta`, { method: 'POST', body: { texto: texto.trim() } })

/** CCPIP-84: productores con denuncias, del mas denunciado al menos. */
export const bandejaDenuncias = () => apiFetch('/api/admin/denuncias')

/** CCPIP-84: historial de denuncias de un productor. */
export const expedienteDenuncias = (productorId) => apiFetch(`/api/admin/denuncias/productores/${idRuta(productorId)}`)

/** CCPIP-84: el subadministrador pasa la denuncia a revision o la cierra, siempre con una nota. */
export const cambiarEstadoDenuncia = (id, estado, nota) =>
  apiFetch(`/api/admin/denuncias/${idRuta(id)}/estado`, { method: 'POST', body: { estado, nota: nota.trim() } })

export const MOTIVOS_DENUNCIA = ['NO_CORRESPONDE', 'CALIDAD', 'ORIGEN_DUDOSO', 'PRACTICA_INDEBIDA', 'CONTENIDO_INAPROPIADO', 'OTRO']
