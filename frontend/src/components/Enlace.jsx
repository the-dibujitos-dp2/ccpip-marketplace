import { navigate } from '../router.js'

/** Enlace interno: navega sin recargar, pero sigue siendo un <a> (abrir en otra pestana, accesible). */
export default function Enlace({ to, children, ...props }) {
  function handleClick(e) {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
    e.preventDefault()
    navigate(to)
  }
  return (
    <a href={to} onClick={handleClick} {...props}>
      {children}
    </a>
  )
}
