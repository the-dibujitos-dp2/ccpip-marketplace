import izquierda from './botanica-izquierda.svg'
import derecha from './botanica-derecha.svg'
import './DecoracionBotanica.css'

export default function DecoracionBotanica() {
  return (
    <div className="decoracion-botanica" data-testid="decoracion-botanica" aria-hidden="true" style={{ pointerEvents: 'none' }}>
      <img className="decoracion-botanica__rama decoracion-botanica__rama--izquierda" src={izquierda} alt="" />
      <img className="decoracion-botanica__rama decoracion-botanica__rama--derecha" src={derecha} alt="" />
    </div>
  )
}
