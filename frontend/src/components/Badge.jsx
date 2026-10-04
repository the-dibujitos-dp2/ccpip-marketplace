import './Badge.css'

/**
 * Badge de estado del sistema Monte: siempre texto + color, nunca solo color.
 * tono: neutro | marca | acento | exito | aviso | error | info
 */
export default function Badge({ tono = 'neutro', conPunto = true, children }) {
  return (
    <span className={`badge-estado badge-estado--${tono}`}>
      {conPunto && <span className="badge-estado__punto" aria-hidden="true" />}
      {children}
    </span>
  )
}
