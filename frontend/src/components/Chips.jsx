import iconoCheck from '../assets/icons/check.svg'
import Icono from './Icono.jsx'
import './Chips.css'

/** Seleccion multiple con chips (lineas de producto en PW-04, filtros activos en CW-02). */
export default function Chips({ opciones, seleccion, onCambiar, etiqueta, error }) {
  function alternar(valor) {
    onCambiar(seleccion.includes(valor) ? seleccion.filter((v) => v !== valor) : [...seleccion, valor])
  }
  return (
    <fieldset className="chips" aria-invalid={error ? 'true' : undefined}>
      <legend className="campo__etiqueta">{etiqueta}</legend>
      <div className="chips__lista">
        {opciones.map((o) => {
          const activo = seleccion.includes(o.valor)
          return (
            <button
              key={o.valor}
              type="button"
              className={`chip${activo ? ' chip--activo' : ''}`}
              aria-pressed={activo}
              onClick={() => alternar(o.valor)}
            >
              {activo && <Icono src={iconoCheck} tamano={14} />}
              {o.etiqueta}
            </button>
          )
        })}
      </div>
      {error && <p className="campo__ayuda campo__ayuda--error">{error}</p>}
    </fieldset>
  )
}
