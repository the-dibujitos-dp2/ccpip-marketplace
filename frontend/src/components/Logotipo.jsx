import { useTranslation } from 'react-i18next'
import iconoHoja from '../assets/icons/hoja.svg'
import Icono from './Icono.jsx'
import './Logotipo.css'

/**
 * Marca de trabajo del prototipo. Se reemplaza cuando la CCPIP entregue su manual de marca.
 * `inverso`: sobre fondo oscuro (pie de pagina, CW-08).
 */
export default function Logotipo({ inverso = false }) {
  const { t } = useTranslation()
  return (
    <div className={`logotipo${inverso ? ' logotipo--inverso' : ''}`}>
      <span className="logotipo__isotipo">
        <Icono src={iconoHoja} tamano={20} />
      </span>
      <span className="logotipo__texto">
        <span className="logotipo__nombre">{t('marca.nombre')}</span>
        <span className="logotipo__lema">{t('marca.lema')}</span>
      </span>
    </div>
  )
}
