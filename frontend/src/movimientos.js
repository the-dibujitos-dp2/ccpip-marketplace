// CCPIP-121, CCPIP-287 y CCPIP-283: catalogos de motivos y la clave de cada envio de un movimiento.

/**
 * Motivos por tipo, igual que InventarioModelos.Motivo del backend. Sin ajuste de conteo (el
 * CHANGELOG del Figma del 30/09 lo quito del punto) ni merma, que tiene su propia accion (A-04).
 */
export const MOTIVOS_PUNTO = { ENTRADA: ['RECEPCION'], SALIDA: ['DEVOLUCION_PRODUCTOR'] }
export const MOTIVOS_ALMACEN = { ENTRADA: ['PRODUCCION'], SALIDA: ['RETIRO_PRODUCTOR'] }

/** Una clave por envio del formulario: si el envio se repite, el servidor no cuenta dos veces (RNF-15). */
export function nuevaClave() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  const b = globalThis.crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6] & 0x0f) | 0x40
  b[8] = (b[8] & 0x3f) | 0x80
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('')
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
}
