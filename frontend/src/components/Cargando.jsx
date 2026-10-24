import { useTranslation } from 'react-i18next'
import './Cargando.css'

/**
 * CCPIP-413: indicador de carga del sistema Monte, igual en toda la plataforma. Se muestra mientras una
 * pantalla espera su informacion y se quita al llegar la respuesta (o un error). `texto` dice que se carga;
 * sin el, "Cargando…".
 */
export default function Cargando({ texto }) {
  const { t } = useTranslation()
  return (
    <div className="cargando" role="status">
      <span className="cargando__giro" aria-hidden="true" />
      <span className="cargando__texto">{texto ?? t('comun.cargando')}</span>
    </div>
  )
}
