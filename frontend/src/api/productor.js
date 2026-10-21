// API del productor (PW-01 a PW-08). El servidor filtra todo por el productor de la sesion.
import { ApiError, apiFetch, idRuta, restartIdle } from './http.js'

const base = '/api/productor'

export const obtenerPanel = () => apiFetch(`${base}/panel`)
/** CCPIP-106: interesados y cantidad pedida en cada oferta propia agotada o pausada. */
export const listarIntereses = () => apiFetch(`${base}/intereses`)

export const obtenerTienda = () => apiFetch(`${base}/tienda`)
export const guardarTienda = (datos) => apiFetch(`${base}/tienda`, { method: 'PUT', body: datos })
export const publicarTienda = () => apiFetch(`${base}/tienda/publicar`, { method: 'POST' })

export function listarProductos({ estado = 'PUBLICADO', q } = {}) {
  const p = new URLSearchParams({ estado })
  if (q?.trim()) p.set('q', q.trim())
  return apiFetch(`${base}/productos?${p}`)
}
export const obtenerProducto = async (id) => apiFetch(`${base}/productos/${idRuta(id)}`)
export const crearProducto = (datos) => apiFetch(`${base}/productos`, { method: 'POST', body: datos })
export const editarProducto = async (id, datos) => apiFetch(`${base}/productos/${idRuta(id)}`, { method: 'PUT', body: datos })
export const publicarProducto = async (id) => apiFetch(`${base}/productos/${idRuta(id)}/publicar`, { method: 'POST' })
export const retirarProducto = async (id) => apiFetch(`${base}/productos/${idRuta(id)}/retirar`, { method: 'POST' })

export const guardarHistoria = async (id, datos) => apiFetch(`${base}/productos/${idRuta(id)}/historia`, { method: 'PUT', body: datos })
export const proponerHistoria = async (id, respuestas, variante = 'NORMAL') =>
  apiFetch(`${base}/productos/${idRuta(id)}/historia/propuesta`, { method: 'POST', body: { respuestas, variante } })
export const aprobarHistoria = async (id, datos) => apiFetch(`${base}/productos/${idRuta(id)}/historia/aprobar`, { method: 'POST', body: datos })
export const proponerDescripcion = (datos) => apiFetch(`${base}/ia/descripcion`, { method: 'POST', body: datos })
/** CCPIP-280: `borrador` DESCRIPCION | HISTORIA. Queda registrado y no se publica nada. */
export const rechazarBorrador = async (id, borrador) =>
  apiFetch(`${base}/productos/${idRuta(id)}/ia/rechazar?${new URLSearchParams({ borrador })}`, { method: 'POST' })

// CCPIP-147 y CCPIP-170: traduccion del producto (PW-04) y de la tienda (PW-08). `idioma` es el de
// destino. Traducir responde 503 TRADUCCION_NO_DISPONIBLE si no hay proveedor; nada se publica
// hasta aprobar, donde `textos` lleva las correcciones [{ entidad, campo, texto }].
const rutaTraducciones = (productoId, idioma) =>
  productoId ? `${base}/productos/${idRuta(productoId)}/traducciones/${idioma}` : `${base}/tienda/traducciones/${idioma}`
export const consultarTraducciones = async (productoId, idioma) => apiFetch(rutaTraducciones(productoId, idioma))
export const traducirContenido = async (productoId, idioma) => apiFetch(rutaTraducciones(productoId, idioma), { method: 'POST' })
export const aprobarTraducciones = async (productoId, idioma, textos = []) =>
  apiFetch(`${rutaTraducciones(productoId, idioma)}/aprobar`, { method: 'POST', body: { textos } })

export const listarUbicaciones = () => apiFetch(`${base}/ubicaciones`)

// CCPIP-136 y CCPIP-162 (PW-16): su almacen virtual unico, por producto, ubicacion, lote y estado
export const consultarInventario = () => apiFetch(`${base}/inventario`)
/**
 * CCPIP-283 (RF-203): entrada o salida en un lote de su almacen virtual unico.
 * `{ tipo, loteId, cantidad, motivo, observacion, clave }`; devuelve `{ movimiento, saldoLote, repetido }`.
 */
export const registrarMovimientoAlmacen = (datos) => apiFetch(`${base}/almacen/movimientos`, { method: 'POST', body: datos })

// CCPIP-66 (PW-07): solo las lineas propias de cada pedido pagado, con conteos por estado
export const listarPedidos = (estado) => apiFetch(`${base}/pedidos?${new URLSearchParams({ estado })}`)

/** CCPIP-92 (RF-70): la liquidacion del periodo; sin fechas, el mes en curso. */
export function consultarLiquidacion({ desde, hasta } = {}) {
  const q = new URLSearchParams()
  if (desde) q.set('desde', desde)
  if (hasta) q.set('hasta', hasta)
  const consulta = q.toString()
  return apiFetch(`${base}/liquidaciones${consulta ? `?${consulta}` : ''}`)
}
// CCPIP-161: todos sus lotes, de todos sus productos, primero el que vence antes
export const listarTodosLosLotes = () => apiFetch(`${base}/lotes`)
export const listarLotes = async (id) => apiFetch(`${base}/productos/${idRuta(id)}/lotes`)
export const registrarLote = async (id, datos) => apiFetch(`${base}/productos/${idRuta(id)}/lotes`, { method: 'POST', body: datos })

export const marcarPrincipal = async (imagenId) => apiFetch(`${base}/imagenes/${idRuta(imagenId)}/principal`, { method: 'PUT' })
/** Una foto de producto se retira y se conserva para el historial (CCPIP-389); portada y galeria se borran. */
export const eliminarImagen = async (imagenId) => apiFetch(`${base}/imagenes/${idRuta(imagenId)}`, { method: 'DELETE' })

/**
 * Sube UNA imagen (CCPIP-44). Cada archivo va en su propia peticion: si la conexion se corta,
 * solo se reintenta ese archivo y los ya subidos se conservan.
 */
export async function subirImagen(archivo, { tipo, categoria, productoId }) {
  const datos = new FormData()
  datos.append('archivo', archivo)
  datos.append('tipo', tipo)
  if (categoria) datos.append('categoria', categoria)
  if (productoId) datos.append('productoId', String(productoId))
  return enviarArchivo(`${base}/imagenes`, datos)
}

/** CCPIP-388: otra foto en el mismo lugar; la anterior queda retirada. */
export async function reemplazarImagen(imagenId, archivo) {
  const datos = new FormData()
  datos.append('archivo', archivo)
  return enviarArchivo(`${base}/imagenes/${idRuta(imagenId)}/reemplazo`, datos)
}

// IA sobre las fotos (PW-04). Solo recomienda o propone: nada bloquea publicar ni cambia una foto solo.
/** CCPIP-144: pedir la validacion de correspondencia con la linea. 503 IA_NO_DISPONIBLE si no hay IA. */
export const validarImagen = async (imagenId) => apiFetch(`${base}/imagenes/${idRuta(imagenId)}/validacion`, { method: 'POST' })
/** CCPIP-168: la ultima validacion de cada foto vigente del producto. */
export const listarValidaciones = async (productoId) =>
  apiFetch(`${base}/imagenes/validaciones?${new URLSearchParams({ productoId: String(productoId) })}`)
/** CCPIP-176 y CCPIP-272: pide (o recupera) la version mejorada pendiente, con lo necesario para comparar. */
export const mejorarImagen = async (imagenId) => apiFetch(`${base}/imagenes/${idRuta(imagenId)}/mejora`, { method: 'POST' })
/** CCPIP-282: usar la mejorada; devuelve la foto nueva que toma el lugar de la original. */
export const aceptarMejora = async (imagenId) => apiFetch(`${base}/imagenes/${idRuta(imagenId)}/mejora/aceptar`, { method: 'POST' })
/** CCPIP-281: conservar la original. */
export const rechazarMejora = async (imagenId) => apiFetch(`${base}/imagenes/${idRuta(imagenId)}/mejora/rechazar`, { method: 'POST' })

async function enviarArchivo(url, datos) {
  const res = await fetch(url, { method: 'POST', body: datos, credentials: 'same-origin' })
  restartIdle()
  const cuerpo = await res.json().catch(() => ({}))
  if (!res.ok) throw new ApiError(res.status, cuerpo)
  return cuerpo
}

// CCPIP-32, 274, 275 y 276 (PW-19, PW-20): la cuenta bancaria de la sesion. 404 si aun no hay una.
export const consultarCuentaBancaria = () => apiFetch(`${base}/cuenta-bancaria`)
export const listarBancos = () => apiFetch(`${base}/cuenta-bancaria/bancos`)
export const registrarCuentaBancaria = (datos) => apiFetch(`${base}/cuenta-bancaria`, { method: 'POST', body: datos })
export const actualizarCuentaBancaria = (datos) => apiFetch(`${base}/cuenta-bancaria`, { method: 'PUT', body: datos })
/** `motivo`: ROBO | BLOQUEO_BANCO | SEGURIDAD | OTRO. La confirmacion expresa es el boton del dialogo. */
export const desactivarCuentaBancaria = (motivo) =>
  apiFetch(`${base}/cuenta-bancaria/desactivar`, { method: 'POST', body: { motivo, confirmo: true } })
export const reactivarCuentaBancaria = (datos, contrasena) =>
  apiFetch(`${base}/cuenta-bancaria/reactivar`, { method: 'POST', body: { datos, contrasena } })

// CCPIP-73, 277, 164, 362 y 146 (PW-17, PW-18): sus transferencias de existencias entre ubicaciones
/** `vista`: EN_CURSO | HISTORIAL. Devuelve la pestana pedida y los contadores de ambas. */
export const listarTransferencias = (vista) => apiFetch(`${base}/transferencias?${new URLSearchParams({ vista })}`)
export const obtenerTransferencia = async (id) => apiFetch(`${base}/transferencias/${idRuta(id)}`)
/** CCPIP-146: su almacen y los puntos de acopio, con su division politica, para elegir origen y destino. */
export const ubicacionesTransferencia = () => apiFetch(`${base}/transferencias/ubicaciones`)
/** `{ tipo, origenId?, destinoId, items: [{ loteId, cantidad }], observacion? }` */
export const crearTransferencia = (datos) => apiFetch(`${base}/transferencias`, { method: 'POST', body: datos })
/** CCPIP-164: separa las unidades del lote de origen (movimiento ligado a la transferencia). */
export const prepararTransferencia = async (id) => apiFetch(`${base}/transferencias/${idRuta(id)}/preparar`, { method: 'POST' })
/** CCPIP-362: despacha lo preparado; no vuelve a descontar. */
export const despacharTransferencia = async (id) => apiFetch(`${base}/transferencias/${idRuta(id)}/despachar`, { method: 'POST' })
