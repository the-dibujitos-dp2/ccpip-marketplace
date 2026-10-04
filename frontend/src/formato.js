const SOLES = new Intl.NumberFormat('es-PE', { style: 'currency', currency: 'PEN' })
const MES_ANIO = new Intl.DateTimeFormat('es-PE', { month: 'long', year: 'numeric', timeZone: 'UTC' })
const FECHA_CORTA = new Intl.DateTimeFormat('es-PE', { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC' })
const FECHA = new Intl.DateTimeFormat('es-PE', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })

/** 46 -> "S/ 46.00" */
export function precio(valor) {
  return SOLES.format(Number(valor))
}

/** "2026-10-04" -> "octubre de 2026" */
export function mesAnio(iso) {
  return iso ? MES_ANIO.format(new Date(`${iso}T00:00:00Z`)) : ''
}

/** "2026-10-04" -> "4 oct 2026" */
export function fecha(iso) {
  return iso ? FECHA.format(new Date(`${iso}T00:00:00Z`)).replace('.', '') : ''
}

/** Mayuscula inicial: "octubre de 2026" -> "Octubre de 2026" */
export function capital(texto) {
  return texto ? texto.charAt(0).toUpperCase() + texto.slice(1) : ''
}

/** "2026-11-20" -> "20/11/2026" (tablas, como en PW-06) */
export function fechaCorta(iso) {
  return iso ? FECHA_CORTA.format(new Date(`${iso}T00:00:00Z`)) : ''
}
