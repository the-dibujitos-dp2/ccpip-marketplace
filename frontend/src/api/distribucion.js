// Distribucion de pedidos a domicilio: lo que ve el distribuidor (CCPIP-107).
import { apiFetch } from './http.js'

/**
 * Una pagina de los pedidos preparados y disponibles para distribuir, el que lleva mas esperando primero:
 * { items: [{ numero, origen: { id, nombre, distrito, direccion }, destino: { ubigeo, departamento, provincia,
 * distrito }, disponibleDesde, esperaMinutos }], siguiente, total, puntos: [{ id, nombre, total }] }. El destino llega
 * solo hasta el distrito: la direccion y el contacto de quien recibe se conocen al asumir la entrega.
 * `punto` filtra por punto de acopio; `cursor` es el `siguiente` de la pagina anterior (nulo en la ultima).
 * `puntos` son los que tienen pedidos, sin importar el filtro.
 */
export function listarPedidosDisponibles({ punto, cursor, limite } = {}) {
  const query = new URLSearchParams()
  if (punto != null) query.set('punto', punto)
  if (cursor) query.set('cursor', cursor)
  if (limite) query.set('limite', limite)
  const texto = query.toString()
  return apiFetch(`/api/distribuidor/pedidos/disponibles${texto ? `?${texto}` : ''}`)
}
