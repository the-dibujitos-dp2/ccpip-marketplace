// CCPIP-494: el precio siempre con su moneda y su unidad de venta, en un solo formato: "S/ 20.00 por 5 und".
import { precio } from './formato.js'

/** "5", "5 und", "5 unidades", "5u." -> se escriben igual: "5 und". */
const UNIDADES = /^(\d+(?:[.,]\d+)?)\s*(?:u|un|und|unid|unids|unidad|unidades|uds?)?\.?$/i

/**
 * La unidad de venta de una oferta: su contenido ("250 ml", "5 und") o, sin contenido, su unidad de medida
 * ("bolsa"). `unidad` puede ser el nombre o `{ nombre }`. Un contenido que solo es una cantidad de
 * unidades se normaliza a "N und", para que "20 por 5" y "16 por 5 und" se lean igual.
 */
export function unidadDeVenta(t, { contenido, unidad } = {}) {
  const texto = contenido?.trim()
  if (texto) {
    const m = texto.match(UNIDADES)
    return m ? t('precioUnidad.und', { cantidad: m[1].replace(',', '.') }) : texto
  }
  const nombre = typeof unidad === 'string' ? unidad : unidad?.nombre
  return nombre ? nombre.toLowerCase() : t('precioUnidad.unidad')
}

/** "por 5 und": lo que acompaña al monto cuando este va aparte (en negrita, por ejemplo). */
export const porUnidad = (t, oferta) => t('precioUnidad.sufijo', { unidad: unidadDeVenta(t, oferta) })

/** "S/ 20.00 por 5 und" */
export const precioPorUnidad = (t, monto, oferta) => t('precioUnidad.completo', { precio: precio(monto), unidad: unidadDeVenta(t, oferta) })
