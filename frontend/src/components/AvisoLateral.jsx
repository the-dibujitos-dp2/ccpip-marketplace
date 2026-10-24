import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoExito from '../assets/icons/check-circulo.svg'
import iconoCerrar from '../assets/icons/cerrar.svg'
import Enlace from './Enlace.jsx'
import './AvisoLateral.css'

/** Tiempo que el aviso permanece en pantalla antes de irse solo. */
export const DURACION_AVISO_LATERAL = 4000

/**
 * CCPIP-414: aviso que entra desde la derecha, confirma una accion y se va solo tras unos segundos.
 * Se pausa mientras el puntero o el foco estan sobre el, para dar tiempo a leerlo o usar el enlace.
 * `enlace` es opcional: { to, texto }. Para reiniciar el tiempo en un aviso nuevo, cambiar su `key`.
 */
export default function AvisoLateral({ titulo, enlace, onCerrar, duracion = DURACION_AVISO_LATERAL }) {
  const { t } = useTranslation()
  const [pausado, setPausado] = useState(false)

  useEffect(() => {
    if (pausado) return undefined
    const temporizador = setTimeout(onCerrar, duracion)
    return () => clearTimeout(temporizador)
  }, [pausado, duracion, onCerrar])

  return (
    <div
      className="aviso-lateral"
      role="status"
      aria-live="polite"
      onMouseEnter={() => setPausado(true)}
      onMouseLeave={() => setPausado(false)}
      onFocus={() => setPausado(true)}
      // Pasar el foco del enlace al boton de cerrar no cuenta como salir del aviso
      onBlur={(e) => !e.currentTarget.contains(e.relatedTarget) && setPausado(false)}
      onKeyDown={(e) => e.key === 'Escape' && onCerrar()}
    >
      <img src={iconoExito} width="20" height="20" alt="" />
      <div className="aviso-lateral__texto">
        <p className="aviso-lateral__titulo">{titulo}</p>
        {enlace && (
          <Enlace to={enlace.to} className="aviso-lateral__enlace">
            {enlace.texto}
          </Enlace>
        )}
      </div>
      <button type="button" className="aviso-lateral__cerrar" onClick={onCerrar} aria-label={t('comun.cerrarAviso')}>
        <img src={iconoCerrar} width="18" height="18" alt="" />
      </button>
    </div>
  )
}
