// Ledger details contain controlled metadata, never arbitrary business values.
const NUMEROS = new Set(['cantidad', 'productoId', 'loteId', 'pedidoId', 'transfItemId', 'transferenciaId',
  'origenId', 'destinoId', 'cuentaId', 'referenciaId', 'monto', 'porcentaje', 'montoAnterior', 'montoNuevo',
  'capacidadAnterior', 'capacidadNueva', 'cantidadAprobada', 'comisionAnulada', 'estrellas', 'ancho', 'alto',
  'versionOriginal', 'valor'])
const CODIGOS = new Set(['estado', 'estadoAnterior', 'estadoNuevo', 'idioma', 'codigo', 'tipo', 'causa', 'cambio'])

export function detalleAuditoria(detalle, t) {
  if (!detalle) return []
  let datos
  try { datos = JSON.parse(detalle) } catch { return [detalle] }
  if (!datos || typeof datos !== 'object' || Array.isArray(datos)) return []
  return Object.entries(datos).flatMap(([clave, valor]) => {
    if (valor == null) return []
    let texto
    if (NUMEROS.has(clave) && typeof valor === 'number' && Number.isFinite(valor)) texto = String(valor)
    else if (CODIGOS.has(clave) && typeof valor === 'string') texto = t(`auditoria.valor.${valor}`, { defaultValue: t('auditoria.datoRegistrado') })
    else if (['activa', 'destacado'].includes(clave) && typeof valor === 'boolean') texto = t(`auditoria.valor.${valor}`)
    else if (clave === 'campos' && Array.isArray(valor)) texto = String(valor.length)
    else return []
    return [`${t(`auditoria.metadata.${clave}`)}: ${texto}`]
  })
}
