import iconoArbol from '../assets/icons/arbol.svg'
import iconoComunidad from '../assets/icons/comunidad.svg'
import iconoCompacto from '../assets/icons/sello-compacto.svg'
import iconoCompleto from '../assets/icons/sello-completo.svg'
import iconoMini from '../assets/icons/sello-mini.svg'
import iconoSinQuimicos from '../assets/icons/sin-quimicos.svg'
import './SelloOrigen.css'

/**
 * Sello de origen verificado por la Camara. Solo para productores verificados (RF-15).
 * completo: ficha · compacto: tarjetas y cabeceras · mini: listados.
 */
export default function SelloOrigen({ variante = 'compacto' }) {
  if (variante === 'mini') {
    return (
      <span className="sello sello--mini" title="Origen verificado por la CCPIP">
        <img src={iconoMini} width="16" height="16" alt="Origen verificado" />
      </span>
    )
  }
  if (variante === 'compacto') {
    return (
      <span className="sello sello--compacto">
        <img src={iconoCompacto} width="14" height="14" alt="" />
        Origen verificado
      </span>
    )
  }
  return (
    <div className="sello sello--completo">
      <p className="sello__titulo">
        <img src={iconoCompleto} width="24" height="24" alt="" />
        Origen verificado por la CCPIP
      </p>
      <ul>
        <li><img src={iconoArbol} width="20" height="20" alt="" />Cero deforestación</li>
        <li><img src={iconoSinQuimicos} width="20" height="20" alt="" />Cero pesticidas o herbicidas</li>
        <li><img src={iconoComunidad} width="20" height="20" alt="" />Elaborado por comunidad indígena</li>
      </ul>
    </div>
  )
}
