import aceites from '../assets/lineas/aceites.svg'
import artesania from '../assets/lineas/artesania.svg'
import cacao from '../assets/lineas/cacao.svg'
import cafe from '../assets/lineas/cafe.svg'
import comunidad from '../assets/lineas/comunidad.svg'
import infusiones from '../assets/lineas/infusiones.svg'
import superfrutos from '../assets/lineas/superfrutos.svg'

const POR_LINEA = {
  ACEITES: aceites,
  ARTESANIA: artesania,
  TEXTIL: artesania,
  CACAO: cacao,
  CAFE: cafe,
  INFUSIONES: infusiones,
  SUPERFRUTOS: superfrutos,
}

/** Marcador por linea de producto (Figma). Se reemplaza por fotografia real cuando exista. */
export default function ImagenProducto({ linea, className }) {
  return <img className={className} src={POR_LINEA[linea] || comunidad} alt="" width="160" height="160" />
}
