// CCPIP-43 y CCPIP-169: premios de un producto y su lote. El servidor filtra por el productor de la
// sesion (RNF-05); el archivo de respaldo solo se sirve con sesion (RNF-08).
import { apiFetch, idRuta } from './http.js'
import { enviarArchivo } from './productor.js'

const base = (productoId) => `/api/productor/productos/${idRuta(productoId)}/premios`

/** Los premios sin retirar del producto propio y sus lotes para registrar otro. */
export const listarPremios = (productoId) => apiFetch(base(productoId))

/** Registra UN premio con su lote, emisor, fecha, vigencia (opcional) y archivo (PDF, JPG o PNG). */
export function registrarPremio(productoId, archivo, { titulo, emisor, otorgadoEn, vigenteHasta, loteId }) {
  const datos = new FormData()
  datos.append('archivo', archivo)
  datos.append('titulo', titulo)
  datos.append('emisor', emisor)
  datos.append('otorgadoEn', otorgadoEn)
  if (vigenteHasta) datos.append('vigenteHasta', vigenteHasta)
  datos.append('loteId', loteId)
  return enviarArchivo(base(productoId), datos)
}

/** Retiro logico: deja de mostrarse en la ficha, pero se conserva. */
export const retirarPremio = (productoId, premioId) => apiFetch(`${base(productoId)}/${idRuta(premioId)}`, { method: 'DELETE' })
