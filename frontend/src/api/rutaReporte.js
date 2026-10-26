/**
 * Ruta de un reporte con sus filtros en la consulta: solo los `campos` con valor y, en un enlace de
 * descarga, el idioma como `lang` (un enlace no lleva Accept-Language). Comun a los reportes del
 * productor y del punto de acopio.
 */
export function rutaReporte(base, campos, sufijo, filtros = {}, idioma = null) {
  const q = new URLSearchParams()
  for (const campo of campos) {
    if (filtros[campo] != null && filtros[campo] !== '') q.set(campo, String(filtros[campo]))
  }
  if (idioma) q.set('lang', idioma)
  const consulta = q.toString()
  return `${base}${sufijo}${consulta ? `?${consulta}` : ''}`
}
