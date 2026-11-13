import iconoHoja from '../assets/icons/hoja-marca.svg'
import Rocco from './Rocco.jsx'
import './EstadoVacio.css'

/**
 * Estado vacio del sistema Monte: nunca una lista en blanco, siempre un mensaje y una salida. Con
 * {@code rocco} (una variante, p. ej. "sorprendido" o "triste") lo acompaña Rocco en vez de la hoja
 * (CCPIP-513); se usa solo en las pantallas del comprador.
 */
export default function EstadoVacio({ titulo, descripcion, rocco, children }) {
  return (
    <div className="estado-vacio" role="status">
      {rocco ? (
        <Rocco variante={rocco} tamano={88} className="estado-vacio__rocco" />
      ) : (
        <span className="estado-vacio__icono">
          <img src={iconoHoja} width="32" height="32" alt="" />
        </span>
      )}
      <p className="estado-vacio__titulo">{titulo}</p>
      {descripcion && <p className="estado-vacio__descripcion">{descripcion}</p>}
      {children}
    </div>
  )
}
