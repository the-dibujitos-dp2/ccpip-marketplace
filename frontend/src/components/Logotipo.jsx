import iconoHoja from '../assets/icons/hoja.svg'
import Icono from './Icono.jsx'
import './Logotipo.css'

/**
 * Marca de trabajo del prototipo. Se reemplaza cuando la CCPIP entregue su manual de marca.
 * `inverso`: sobre fondo oscuro (pie de pagina, CW-08).
 */
export default function Logotipo({ inverso = false }) {
  return (
    <div className={`logotipo${inverso ? ' logotipo--inverso' : ''}`}>
      <span className="logotipo__isotipo">
        <Icono src={iconoHoja} tamano={20} />
      </span>
      <span className="logotipo__texto">
        <span className="logotipo__nombre">Mercado CCPIP</span>
        <span className="logotipo__lema">Comercio de pueblos indígenas</span>
      </span>
    </div>
  )
}
