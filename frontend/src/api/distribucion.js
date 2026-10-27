// Distribucion de pedidos a domicilio: lo que ve y hace el distribuidor (CCPIP-107, CCPIP-108, CCPIP-317, CCPIP-109).
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

/**
 * CCPIP-317: las entregas asignadas al distribuidor, solo las suyas: [{ numero, origen, destino, estado, asignadaEn,
 * retiradaEn }]. `estado` es el de la entrega (ASIGNADA, RETIRADA, EN_CAMINO o ENTREGADA); primero las que estan en
 * curso, la que tomo antes primero, y despues las que entrego hoy.
 */
export const listarMisEntregas = () => apiFetch('/api/distribuidor/pedidos')

/**
 * CCPIP-109: registra que retiro del punto de acopio la entrega que tomo; devuelve la entrega con estado RETIRADA y
 * retiradaEn. Si no es suya responde 409 PEDIDO_NO_ASIGNADO; si ya no se puede retirar, 409 PEDIDO_NO_RETIRABLE.
 * Repetirlo no cambia nada.
 */
export const retirarEntrega = (numero) =>
  apiFetch(`/api/distribuidor/pedidos/${encodeURIComponent(numero)}/retirar`, { method: 'POST' })
