import { localeFechas } from './i18n/index.js'

// CCPIP-98: las fechas siguen el idioma elegido. Los precios y cantidades se escriben igual en ambos
// idiomas, en soles y con el formato de Peru (S/ 1,234.50): multimoneda esta fuera de alcance (CCPIP-99).
const OPCIONES = {
  soles: { style: 'currency', currency: 'PEN' },
  unidades: { maximumFractionDigits: 3 },
  mesAnio: { month: 'long', year: 'numeric', timeZone: 'UTC' },
  mes: { month: 'long', timeZone: 'UTC' },
  fechaCorta: { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC' },
  fecha: { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' },
  fechaLarga: { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' },
  diaMes: { day: 'numeric', month: 'short', timeZone: 'America/Lima' },
  fechaHora: { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: 'America/Lima' },
}

// Crear un Intl.*Format es costoso: se guarda uno por formato y locale
const cache = new Map()
function formato(nombre) {
  const numerico = nombre === 'soles' || nombre === 'unidades'
  const locale = numerico ? 'es-PE' : localeFechas()
  const clave = `${nombre}|${locale}`
  if (!cache.has(clave)) {
    const Tipo = numerico ? Intl.NumberFormat : Intl.DateTimeFormat
    cache.set(clave, new Tipo(locale, OPCIONES[nombre]))
  }
  return cache.get(clave)
}

/** 46 -> "S/ 46.00" */
export function precio(valor) {
  return formato('soles').format(Number(valor))
}

/** 12.5 -> "12.5" (cantidades de stock, hasta 3 decimales) */
export function unidades(valor) {
  return formato('unidades').format(Number(valor))
}

/** "2026-10-04" -> "octubre de 2026" · "October 2026" */
export function mesAnio(iso) {
  return iso ? formato('mesAnio').format(new Date(`${iso}T00:00:00Z`)) : ''
}

/** "2026-10-04" -> "4 oct 2026" · "4 Oct 2026" */
export function fecha(iso) {
  return iso ? formato('fecha').format(new Date(`${iso}T00:00:00Z`)).replace('.', '') : ''
}

/** "2026-10-01" -> "1 de octubre de 2026" · "1 October 2026" (vigencias, SA-02) */
export function fechaLarga(iso) {
  return iso ? formato('fechaLarga').format(new Date(`${iso}T00:00:00Z`)) : ''
}

/** Mayuscula inicial: "octubre de 2026" -> "Octubre de 2026" */
export function capital(texto) {
  return texto ? texto.charAt(0).toUpperCase() + texto.slice(1) : ''
}

/** "2026-11-20" -> "20/11/2026" (tablas, como en PW-06) */
export function fechaCorta(iso) {
  return iso ? formato('fechaCorta').format(new Date(`${iso}T00:00:00Z`)) : ''
}

/** "2026-11-16T14:12:00Z" -> "16/11 09:12", en hora de Lima (tablas de movimientos, A-03) */
export function fechaHora(instante) {
  return instante ? formato('fechaHora').format(new Date(instante)).replace(',', '') : ''
}

/** "2026-10" -> "setiembre" · "September": el mes anterior a un periodo (comparaciones de SA-01) */
export function mesAnterior(periodo) {
  if (!periodo) return ''
  const [anio, mes] = periodo.split('-').map(Number)
  // Solo, el mes sale con mayuscula en espanol ("Setiembre"); dentro de una frase va en minuscula
  const texto = formato('mes').format(new Date(Date.UTC(anio, mes - 2, 1)))
  return localeFechas().startsWith('es') ? texto.toLocaleLowerCase('es') : texto
}

/** "2026-11-16T14:12:00Z" -> "16 nov" · "16 Nov", en hora de Lima (listas de pedidos, CW-09) */
export function diaMes(instante) {
  return instante ? formato('diaMes').format(new Date(instante)).replace('.', '') : ''
}
