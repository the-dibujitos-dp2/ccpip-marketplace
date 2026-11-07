import aceites from '../assets/lineas/aceites.svg'
import artesania from '../assets/lineas/artesania.svg'
import cacao from '../assets/lineas/cacao.svg'
import cafe from '../assets/lineas/cafe.svg'
import comunidad from '../assets/lineas/comunidad.svg'
import infusiones from '../assets/lineas/infusiones.svg'
import portada from '../assets/lineas/portada.svg'
import cafeRef from '../assets/editorial/home/cafe.webp'
import cacaoRef from '../assets/editorial/home/cacao.webp'
import feriaRef from '../assets/editorial/feria.jpg'
import amazoniaRef from '../assets/editorial/home/amazonia.webp'
import { useContext } from 'react'
import { useTranslation } from 'react-i18next'
import { ContextoVisualConsumidor } from './ContextoVisualConsumidor.jsx'
import './imagenProductoI18n.js'
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

export default function ImagenProducto({ url, linea, alt = '', className }) {
  const consumer = useContext(ContextoVisualConsumidor)
  const { t } = useTranslation('imagenReferencial')
  if (url) return <img className={className} src={url} alt={alt} loading="lazy" decoding="async" />
  if (!consumer) return <img className={className} src={POR_LINEA[linea] || comunidad} alt="" />
  const cafe = linea === 'CAFE'
  const cacaoLinea = linea === 'CACAO'
  const isCommunity = linea === 'PORTADA' || linea === 'COMUNIDAD'
  const src = cafe ? cafeRef : cacaoLinea ? cacaoRef : isCommunity ? amazoniaRef : feriaRef
  const caption = isCommunity ? t('captionComunidad') : cafe || cacaoLinea ? t('captionCafeCacao') : t('captionOferta')
  const accessibleAlt = alt ? `${alt}, ${t('aclaracion')}` : ''
  return (
    <span className={`imagen-producto-referencial${className ? ` ${className}` : ''}`}>
      <img className="imagen-producto-referencial__img" src={src} alt={accessibleAlt} aria-hidden={!accessibleAlt || undefined} loading="lazy" decoding="async" />
      <small className="imagen-producto-referencial__caption" aria-hidden="true">{caption}</small>
    </span>
  )
}