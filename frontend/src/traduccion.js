// CCPIP-171 (RF-163): que texto mostrar de un campo traducido del catalogo y en que idioma esta.
/** El texto a mostrar de un campo: su original si se pidio "Ver original" y hay una traduccion vigente. */
export function textoSegun(traducciones, campo, valor, verOriginal) {
  const info = traducciones?.[campo]
  return verOriginal && info?.vigente && info.original != null ? info.original : valor
}

/** `lang` del texto mostrado: el original es espanol (el de una traduccion desactualizada tambien). */
export function idiomaSegun(traducciones, campo, verOriginal) {
  const info = traducciones?.[campo]
  if (!info) return undefined
  return !info.vigente || verOriginal ? 'es' : undefined
}
