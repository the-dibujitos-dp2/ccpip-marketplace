import iconoHoja from '../assets/icons/hoja-marca.svg'
import './EstadoVacio.css'

/** Estado vacio del sistema Monte: nunca una lista en blanco, siempre un mensaje y una salida. */
export default function EstadoVacio({ titulo, descripcion, children }) {
  return (
    <div className="estado-vacio" role="status">
      <span className="estado-vacio__icono">
        <img src={iconoHoja} width="32" height="32" alt="" />
      </span>
      <p className="estado-vacio__titulo">{titulo}</p>
      {descripcion && <p className="estado-vacio__descripcion">{descripcion}</p>}
      {children}
    </div>
  )
}
