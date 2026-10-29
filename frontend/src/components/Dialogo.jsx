import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCerrar from '../assets/icons/cerrar.svg'
import Boton from './Boton.jsx'
import './Dialogo.css'

/**
 * Modal de confirmacion (Figma 36:484). Accesible: <dialog> nativo, Escape cierra y el foco queda dentro.
 * `cancelar` y `onCerrar` (opcionales): cuando el boton secundario es una decision (p. ej. "Conservar la
 * original", CCPIP-281), Escape y la X solo cierran sin decidir. `ancho`: "amplio" para contenido lado a lado.
 * CCPIP-413: con `ocupado` el aro va en el boton de confirmar; `ocupadoEn="cancelar"` lo pone en el secundario
 * cuando ese boton es la decision que espera respuesta.
 */
export default function Dialogo({ abierto, titulo, children, confirmar, tipoConfirmar = 'primario', onConfirmar, onCancelar, ocupado,
  ocupadoEn = 'confirmar', cancelar, tipoCancelar = 'terciario', onCerrar, etiquetaCerrar, ancho }) {
  const { t } = useTranslation()
  const ref = useRef(null)
  useEffect(() => {
    const d = ref.current
    if (!d) return
    if (abierto && !d.open) {
      if (typeof d.showModal === 'function') d.showModal()
      else d.setAttribute('open', '')
    }
    if (!abierto && d.open) {
      if (typeof d.close === 'function') d.close()
      else d.removeAttribute('open')
    }
  }, [abierto])
  return (
    <dialog ref={ref} className={`dialogo${ancho ? ` dialogo--${ancho}` : ''}`} aria-labelledby="dialogo-titulo"
      onCancel={(e) => { e.preventDefault(); (onCerrar ?? onCancelar)() }}>
      {abierto && (
        <div className="dialogo__contenido">
          <h2 id="dialogo-titulo" className="heading-h2">{titulo}</h2>
          {onCerrar && (
            <button type="button" className="dialogo__cerrar" onClick={onCerrar} disabled={ocupado} aria-label={etiquetaCerrar ?? t('comun.cerrarAviso')}>
              <img src={iconoCerrar} width="20" height="20" alt="" />
            </button>
          )}
          <div className="body-s">{children}</div>
          <div className="dialogo__acciones">
            <Boton tipo={tipoCancelar} tamano="M" onClick={onCancelar} disabled={ocupado} cargando={ocupado && ocupadoEn === 'cancelar'}>
              {cancelar ?? t('comun.cancelar')}
            </Boton>
            <Boton tipo={tipoConfirmar} tamano="M" onClick={onConfirmar} disabled={ocupado} cargando={ocupado && ocupadoEn === 'confirmar'}>
              {confirmar ?? t('comun.confirmar')}
            </Boton>
          </div>
        </div>
      )}
    </dialog>
  )
}
