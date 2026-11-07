import { useContext } from 'react'
import { useTranslation } from 'react-i18next'
import aceites from '../assets/lineas/aceites.svg'
import artesania from '../assets/lineas/artesania.svg'
import cacao from '../assets/lineas/cacao.svg'
import cafe from '../assets/lineas/cafe.svg'
import comunidad from '../assets/lineas/comunidad.svg'
import infusiones from '../assets/lineas/infusiones.svg'
import portada from '../assets/lineas/portada.svg'
import superfrutos from '../assets/lineas/superfrutos.svg'
import { ContextoVisualConsumidor } from './ContextoVisualConsumidor.jsx'
import { FOTOGRAFIAS_REFERENCIA } from './fotografiasEditoriales.js'
import './imagenProductoI18n.js'

const POR_LINEA = { ACEITES: aceites, ARTESANIA: artesania, TEXTIL: artesania, CACAO: cacao, CAFE: cafe, INFUSIONES: infusiones, SUPERFRUTOS: superfrutos, PORTADA: portada }

export default function ImagenProducto({ url, linea, alt = '', className }) {
  const consumidor = useContext(ContextoVisualConsumidor)
  const { t } = useTranslation('imagenReferencial')
  if (url) return <img className={className} src={url} alt={alt} loading="lazy" decoding="async" />
  const referencia = Object.hasOwn(FOTOGRAFIAS_REFERENCIA, linea) ? FOTOGRAFIAS_REFERENCIA[linea] : null
  if (!consumidor || !referencia) {
    const marcador = Object.hasOwn(POR_LINEA, linea) ? POR_LINEA[linea] : comunidad
    return <img className={className} src={marcador} alt="" />
  }
  const descripcion = alt ? `${alt}, ${t(referencia.caption)}` : ''
  return (
    <span className={`imagen-producto-referencial${className ? ` ${className}` : ''}`}>
      <img className="imagen-producto-referencial__img" src={referencia.src} alt={descripcion} aria-hidden={!descripcion || undefined} loading="lazy" decoding="async" />
      <small className="imagen-producto-referencial__caption" aria-hidden="true">{t(referencia.caption)}</small>
    </span>
  )
}
