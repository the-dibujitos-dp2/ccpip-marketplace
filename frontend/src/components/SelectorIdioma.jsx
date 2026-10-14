import { useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCheck from '../assets/icons/check.svg'
import iconoChevron from '../assets/icons/chevron-abajo.svg'
import iconoGlobo from '../assets/icons/globo.svg'
import { IDIOMAS } from '../i18n/index.js'
import { cambiarIdioma } from '../i18n/preferencia.js'
import { useSesion } from '../sesion.js'
import Icono from './Icono.jsx'
import './SelectorIdioma.css'

/** Nombre de cada idioma en su propio idioma, como en el Figma ("ES · Español", "EN · English"). */
const NOMBRES = { es: 'Español', en: 'English' }

/**
 * Selector de idioma del Figma (componente "Selector de idioma", CCPIP-265): pastilla con el idioma
 * actual y un menu con las dos opciones. Va en la barra superior de la tienda, en los paneles y en el
 * inicio de sesion, para que todo el flujo critico se pueda usar en ambos idiomas.
 */
export default function SelectorIdioma({ className = '' }) {
  const { t, i18n } = useTranslation()
  const { user } = useSesion()
  const [abierto, setAbierto] = useState(false)
  const contenedor = useRef(null)
  const idMenu = useId()
  const actual = i18n.resolvedLanguage

  useEffect(() => {
    if (!abierto) return undefined
    const fuera = (e) => !contenedor.current?.contains(e.target) && setAbierto(false)
    const escape = (e) => {
      if (e.key === 'Escape') {
        setAbierto(false)
        contenedor.current?.querySelector('button')?.focus()
      }
    }
    document.addEventListener('mousedown', fuera)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', fuera)
      document.removeEventListener('keydown', escape)
    }
  }, [abierto])

  function elegir(idioma) {
    setAbierto(false)
    if (idioma !== actual) void cambiarIdioma(idioma, user)
  }

  return (
    <div className={`selector-idioma ${className}`} ref={contenedor}>
      <button
        type="button"
        className="selector-idioma__boton"
        aria-haspopup="menu"
        aria-expanded={abierto}
        aria-controls={abierto ? idMenu : undefined}
        aria-label={t('idioma.etiqueta', { idioma: NOMBRES[actual] })}
        onClick={() => setAbierto((v) => !v)}
      >
        <Icono src={iconoGlobo} tamano={16} />
        <span>{`${actual.toUpperCase()} · ${NOMBRES[actual]}`}</span>
        <Icono src={iconoChevron} tamano={16} />
      </button>
      {abierto && (
        <ul id={idMenu} className="selector-idioma__menu" role="menu" aria-label={t('idioma.menu')}>
          {IDIOMAS.map((idioma) => (
            <li key={idioma} role="none">
              <button
                type="button"
                role="menuitemradio"
                aria-checked={idioma === actual}
                lang={idioma}
                className="selector-idioma__opcion"
                onClick={() => elegir(idioma)}
              >
                {NOMBRES[idioma]}
                {idioma === actual && <Icono src={iconoCheck} tamano={16} />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
