import aceites from '../assets/lineas/aceites.svg'
import artesania from '../assets/lineas/artesania.svg'
import cacao from '../assets/lineas/cacao.svg'
import cafe from '../assets/lineas/cafe.svg'
import comunidad from '../assets/lineas/comunidad.svg'
import infusiones from '../assets/lineas/infusiones.svg'
import portada from '../assets/lineas/portada.svg'
import superfrutos from '../assets/lineas/superfrutos.svg'

const POR_LINEA = {
  ACEITES: aceites,
  ARTESANIA: artesania,
  TEXTIL: artesania,
  CACAO: cacao,
  CAFE: cafe,
  INFUSIONES: infusiones,
  SUPERFRUTOS: superfrutos,
  // Portada de tienda sin foto: el marcador verde con hoja, como en CW-04 y PW-08
  PORTADA: portada,
}

/** Foto real si existe; si no, el marcador de su linea (Figma) hasta que el productor suba fotos. */
export default function ImagenProducto({ url, linea, alt = '', className }) {
  if (url) return <img className={className} src={url} alt={alt} loading="lazy" decoding="async" />
  // Sin width/height: el tamano lo define el CSS de cada lugar (un height fijo deformaba la imagen)
  return <img className={className} src={POR_LINEA[linea] || comunidad} alt="" />
}
