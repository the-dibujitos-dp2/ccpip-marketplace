import './Pestanas.css'

/** Pestanas con subrayado de marca (PW-03, CW-04). `opciones`: [{ valor, etiqueta }] */
export default function Pestanas({ opciones, valor, onCambiar, etiqueta }) {
  return (
    <div className="pestanas" role="tablist" aria-label={etiqueta}>
      {opciones.map((o) => (
        <button
          key={o.valor}
          type="button"
          role="tab"
          aria-selected={o.valor === valor}
          className={`pestanas__item${o.valor === valor ? ' pestanas__item--activa' : ''}`}
          onClick={() => onCambiar(o.valor)}
        >
          {o.etiqueta}
        </button>
      ))}
    </div>
  )
}
