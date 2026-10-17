/**
 * CCPIP-134 (RF-108): la tarifa que corresponde a la modalidad de la oferta y la cantidad. Es el
 * mismo criterio que pe.ccpip.carrito.Tarifas en el servidor, que es quien decide al agregar, en el
 * carrito y al comprar; aqui solo se anticipa en la ficha para responder al instante.
 *
 * - MINORISTA: precio minorista.
 * - MAYORISTA: solo desde el minimo, a precio mayorista; bajo el minimo no se puede comprar.
 * - AMBAS: desde el minimo, el mayorista; bajo el minimo, el minorista.
 *
 * `faltante` son las unidades que faltan para el minimo (0 si ya se llego; null sin tarifa mayorista).
 * Una cantidad invalida (vacia, decimal) cuenta como cero.
 */
export function resolverTarifa({ modalidad, precio, precioMayorista, cantidadMinimaMayorista: minimo }, cantidad) {
  const unidades = Number.isInteger(Number(cantidad)) && cantidad !== '' ? Math.max(0, Number(cantidad)) : 0
  const vendeMinorista = modalidad !== 'MAYORISTA' && precio != null
  const vendeMayorista = modalidad !== 'MINORISTA' && precioMayorista != null && minimo != null
  if (!vendeMayorista) {
    return vendeMinorista
      ? { tarifa: 'MINORISTA', precio, comprable: true, faltante: null }
      : { tarifa: null, precio: null, comprable: false, faltante: null }
  }
  const faltante = Math.max(0, minimo - unidades)
  if (faltante === 0) return { tarifa: 'MAYORISTA', precio: precioMayorista, comprable: true, faltante }
  return vendeMinorista
    ? { tarifa: 'MINORISTA', precio, comprable: true, faltante }
    : { tarifa: 'MAYORISTA', precio: precioMayorista, comprable: false, faltante }
}
