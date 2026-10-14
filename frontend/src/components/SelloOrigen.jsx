import { useTranslation } from 'react-i18next'
import iconoArbol from '../assets/icons/arbol.svg'
import iconoComunidad from '../assets/icons/comunidad.svg'
import iconoCompacto from '../assets/icons/sello-compacto.svg'
import iconoCompleto from '../assets/icons/sello-completo.svg'
import iconoMini from '../assets/icons/sello-mini.svg'
import iconoSinQuimicos from '../assets/icons/sin-quimicos.svg'
import './SelloOrigen.css'

/**
 * Sello de origen verificado por la Camara. Solo para productores verificados (RF-15).
 * completo: ficha · compacto: tarjetas y cabeceras · mini: listados.
 */
export default function SelloOrigen({ variante = 'compacto' }) {
  const { t } = useTranslation()
  if (variante === 'mini') {
    return (
      <span className="sello sello--mini" title={t('sello.titulo')}>
        <img src={iconoMini} width="16" height="16" alt={t('sello.corto')} />
      </span>
    )
  }
  if (variante === 'compacto') {
    return (
      <span className="sello sello--compacto">
        <img src={iconoCompacto} width="14" height="14" alt="" />
        {t('sello.corto')}
      </span>
    )
  }
  return (
    <div className="sello sello--completo">
      <p className="sello__titulo">
        <img src={iconoCompleto} width="24" height="24" alt="" />
        {t('sello.titulo')}
      </p>
      <ul>
        <li><img src={iconoArbol} width="20" height="20" alt="" />{t('sello.ceroDeforestacion')}</li>
        <li><img src={iconoSinQuimicos} width="20" height="20" alt="" />{t('sello.ceroPesticidas')}</li>
        <li><img src={iconoComunidad} width="20" height="20" alt="" />{t('sello.comunidadIndigena')}</li>
      </ul>
    </div>
  )
}
