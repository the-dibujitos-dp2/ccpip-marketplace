import iconoBolsa from '../assets/icons/bolsa-marca.svg'
import iconoReloj from '../assets/icons/reloj.svg'
import './BadgeDisponibilidad.css'

/** Disponibilidad sin cantidades ni lotes: solo si hay o no hay stock. */
export default function BadgeDisponibilidad({ disponible }) {
  return disponible ? (
    <span className="badge badge--disponible">
      <img src={iconoBolsa} width="14" height="14" alt="" />
      Disponible
    </span>
  ) : (
    <span className="badge badge--agotado">
      <img src={iconoReloj} width="14" height="14" alt="" />
      Agotado
    </span>
  )
}
