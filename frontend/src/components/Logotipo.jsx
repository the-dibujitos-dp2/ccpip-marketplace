import { useTranslation } from 'react-i18next'
import logoOficial from '../assets/marca/logo-ccpip.png'
import './Logotipo.css'

/**
 * Logotipo oficial CCPIP, también sobre fondo oscuro (pie de página, CW-08).
 */
export default function Logotipo({ inverso = false }) {
  const { t } = useTranslation()
  return (
    <span className={`logotipo${inverso ? ' logotipo--inverso' : ''}`}>
      <img className="logotipo__imagen" src={logoOficial} alt={t('marca.nombre')} />
    </span>
  )
}
