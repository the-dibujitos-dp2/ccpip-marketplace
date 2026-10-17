import { useTranslation } from 'react-i18next'
import Badge from './Badge.jsx'
import './AvisoTraduccion.css'

/**
 * CCPIP-171 (RF-163): etiqueta el contenido traducido de una seccion segun su origen ("Traducido por
 * la comunidad" o "Traduccion automatica") y ofrece "Ver original", que alterna al texto en su
 * idioma original. CCPIP-373: si el original cambio despues de traducirlo, avisa que se muestra el
 * original. `traducciones` es el mapa por campo que manda el catalogo; `campos`, los de esta seccion.
 */
export default function AvisoTraduccion({ traducciones, campos, verOriginal, onAlternar }) {
  const { t } = useTranslation()
  const infos = campos.map((c) => traducciones?.[c]).filter(Boolean)
  if (infos.length === 0) return null
  const vigentes = infos.filter((i) => i.vigente)
  const origenes = [...new Set(vigentes.map((i) => i.origen))].sort()
  const desactualizada = infos.some((i) => !i.vigente)

  return (
    <div className="aviso-traduccion" role="note" aria-label={t('traduccion.aviso')}>
      {origenes.map((o) => (
        <Badge key={o} tono={o === 'AUTOMATICA' ? 'info' : 'marca'} conPunto={false}>
          {t(`traduccion.origen.${o}`)}
        </Badge>
      ))}
      {vigentes.length > 0 && (
        <button type="button" className="enlace-marca aviso-traduccion__alternar" aria-pressed={verOriginal} onClick={onAlternar}>
          {verOriginal ? t('traduccion.verTraduccion') : t('traduccion.verOriginal')}
        </button>
      )}
      {verOriginal && vigentes.length > 0 && <span className="caption">{t('traduccion.mostrandoOriginal')}</span>}
      {desactualizada && <p className="caption aviso-traduccion__desactualizada">{t('traduccion.desactualizada')}</p>}
    </div>
  )
}
