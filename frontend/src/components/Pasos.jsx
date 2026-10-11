import iconoCheck from '../assets/icons/check.svg'
import Icono from './Icono.jsx'
import './Pasos.css'

/** Indicador de pasos del asistente de publicacion (PW-04 · PW-05 · PW-06) y de la compra (CW-05). */
export default function Pasos({ pasos, actual, onIr, etiqueta = 'Pasos para publicar' }) {
  return (
    <ol className="pasos" aria-label={etiqueta}>
      {pasos.map((paso, i) => {
        const estado = i < actual ? 'hecho' : i === actual ? 'actual' : 'pendiente'
        return (
          <li key={paso} className={`pasos__item pasos__item--${estado}`} aria-current={estado === 'actual' ? 'step' : undefined}>
            {i > 0 && <span className="pasos__linea" aria-hidden="true" />}
            <button type="button" className="pasos__boton" disabled={!onIr || estado === 'pendiente'} onClick={() => onIr?.(i)}>
              <span className="pasos__circulo">
                {estado === 'hecho' ? <Icono src={iconoCheck} tamano={14} /> : i + 1}
              </span>
              <span className="pasos__nombre">{paso}</span>
            </button>
          </li>
        )
      })}
    </ol>
  )
}
