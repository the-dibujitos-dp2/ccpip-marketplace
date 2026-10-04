import './OpcionTarjeta.css'

/** Radio en forma de tarjeta (modalidad de venta en PW-04). */
export default function OpcionTarjeta({ nombre, opciones, valor, onCambiar, etiqueta }) {
  return (
    <fieldset className="opciones-tarjeta">
      <legend className="campo__etiqueta">{etiqueta}</legend>
      <div className="opciones-tarjeta__lista">
        {opciones.map((o) => (
          <label key={o.valor} className={`opcion-tarjeta${o.valor === valor ? ' opcion-tarjeta--activa' : ''}`}>
            <input type="radio" name={nombre} value={o.valor} checked={o.valor === valor} onChange={() => onCambiar(o.valor)} />
            <span>{o.etiqueta}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}
