import { useEffect, useRef } from 'react'
import Boton from './Boton.jsx'
import './Dialogo.css'

/** Modal de confirmacion (Figma 36:484). Accesible: <dialog> nativo, Escape cierra y el foco queda dentro. */
export default function Dialogo({ abierto, titulo, children, confirmar = 'Confirmar', tipoConfirmar = 'primario', onConfirmar, onCancelar, ocupado }) {
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
    <dialog ref={ref} className="dialogo" aria-labelledby="dialogo-titulo" onCancel={(e) => { e.preventDefault(); onCancelar() }}>
      {abierto && (
        <div className="dialogo__contenido">
          <h2 id="dialogo-titulo" className="heading-h2">{titulo}</h2>
          <div className="body-s">{children}</div>
          <div className="dialogo__acciones">
            <Boton tipo="terciario" tamano="M" onClick={onCancelar} disabled={ocupado}>
              Cancelar
            </Boton>
            <Boton tipo={tipoConfirmar} tamano="M" onClick={onConfirmar} disabled={ocupado}>
              {confirmar}
            </Boton>
          </div>
        </div>
      )}
    </dialog>
  )
}
