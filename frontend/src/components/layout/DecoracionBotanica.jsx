import derecha from '../../assets/editorial/botanica/derecha.svg'
import izquierda from '../../assets/editorial/botanica/izquierda.svg'
import './DecoracionBotanica.css'

/**
 * CCPIP-435 · Herbario: follaje tenue en los márgenes exteriores de la tienda, solo en escritorio y solo
 * cuando sobra espacio a los lados del contenido. Es decoración: fuera del árbol accesible, sin foco ni
 * eventos del puntero, y detrás de todo (cabecera, contenido, pie y diálogos lo cubren).
 */
export default function DecoracionBotanica() {
  return (
    <div className="decoracion-botanica" data-testid="decoracion-botanica" aria-hidden="true">
      <img className="decoracion-botanica__rama decoracion-botanica__rama--izquierda" src={izquierda} alt="" decoding="async" />
      <img className="decoracion-botanica__rama decoracion-botanica__rama--derecha" src={derecha} alt="" decoding="async" />
    </div>
  )
}
