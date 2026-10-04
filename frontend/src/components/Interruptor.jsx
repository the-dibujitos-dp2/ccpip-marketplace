import './Interruptor.css'

/** Interruptor encendido/apagado ("Mostrar solo disponibles" en CW-02). */
export default function Interruptor({ etiqueta, activo, onCambiar }) {
  return (
    <label className="interruptor">
      <input type="checkbox" role="switch" checked={activo} onChange={(e) => onCambiar(e.target.checked)} />
      <span className="interruptor__pista" aria-hidden="true" />
      <span>{etiqueta}</span>
    </label>
  )
}
