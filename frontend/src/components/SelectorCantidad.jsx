import iconoMas from '../assets/icons/mas.svg'
import iconoMenos from '../assets/icons/menos.svg'
import Icono from './Icono.jsx'
import './SelectorCantidad.css'

/**
 * Selector de cantidad del Figma ("− 1 +", CW-03c y CW-05). El valor es texto para que la
 * persona pueda borrar y escribir; quien lo usa valida que sea un entero de al menos `min`.
 */
export default function SelectorCantidad({ valor, onCambiar, min = 1, etiqueta = 'Cantidad', invalido = false, disabled = false, id, describedBy }) {
  const numero = Number(valor)
  const actual = Number.isInteger(numero) && numero >= min ? numero : min
  return (
    <div className={`selector-cantidad${invalido ? ' selector-cantidad--error' : ''}${disabled ? ' selector-cantidad--deshabilitado' : ''}`}>
      <button
        type="button"
        className="selector-cantidad__boton"
        onClick={() => onCambiar(String(actual - 1))}
        disabled={disabled || actual <= min}
        aria-label="Quitar una unidad"
      >
        <Icono src={iconoMenos} tamano={18} />
      </button>
      <input
        id={id}
        className="selector-cantidad__valor"
        type="text"
        inputMode="numeric"
        value={valor}
        onChange={(e) => onCambiar(e.target.value.trim())}
        disabled={disabled}
        aria-label={etiqueta}
        aria-invalid={invalido ? 'true' : undefined}
        aria-describedby={describedBy}
      />
      <button
        type="button"
        className="selector-cantidad__boton"
        onClick={() => onCambiar(String(actual + 1))}
        disabled={disabled}
        aria-label="Agregar una unidad"
      >
        <Icono src={iconoMas} tamano={18} />
      </button>
    </div>
  )
}
