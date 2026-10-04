import './Avatar.css'

/** Iniciales sobre fondo acento suave (sidebar PW). */
export default function Avatar({ nombre }) {
  const iniciales = (nombre ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0].toUpperCase())
    .join('')
  return (
    <span className="avatar" aria-hidden="true">
      {iniciales}
    </span>
  )
}
