const SOLES = new Intl.NumberFormat('es-PE', { style: 'currency', currency: 'PEN' })

/** 46 -> "S/ 46.00" */
export function precio(valor) {
  return SOLES.format(Number(valor))
}
