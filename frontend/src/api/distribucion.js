// Distribucion de pedidos a domicilio: lo que ve el distribuidor (CCPIP-107).
import { apiFetch } from './http.js'

/**
 * Pedidos preparados y disponibles para distribuir, el que lleva mas esperando primero:
 * [{ numero, origen: { id, nombre, distrito, direccion }, destino: { ubigeo, departamento, provincia, distrito },
 * disponibleDesde, esperaMinutos }]. El destino llega solo hasta el distrito: la direccion y el contacto de quien
 * recibe se conocen al asumir la entrega.
 */
export const listarPedidosDisponibles = () => apiFetch('/api/distribuidor/pedidos/disponibles')
