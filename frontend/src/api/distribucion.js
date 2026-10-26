// Distribucion de pedidos a domicilio: lo que ve y hace el distribuidor (CCPIP-107, CCPIP-108).
import { apiFetch } from './http.js'

/**
 * Una pagina de los pedidos preparados y disponibles para distribuir, el que lleva mas esperando primero:
 * { items: [{ numero, origen: { id, nombre, distrito, direccion }, destino: { ubigeo, departamento, provincia,
 * distrito }, disponibleDesde, esperaMinutos }], siguiente, total, puntos: [{ id, nombre, total }] }. El destino llega
 * solo hasta el distrito: la direccion y el contacto de quien recibe los consulta quien asumio la entrega (CCPIP-110).
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

/**
 * CCPIP-108: asume la entrega de un pedido disponible; devuelve { numero, origen, destino, asignadaEn }. Si otro
 * distribuidor se adelanto responde 409 PEDIDO_YA_ASUMIDO; si el pedido ya no esta disponible, 409 PEDIDO_NO_DISPONIBLE.
 */
export const asumirEntrega = (numero) =>
  apiFetch(`/api/distribuidor/pedidos/${encodeURIComponent(numero)}/asumir`, { method: 'POST' })
