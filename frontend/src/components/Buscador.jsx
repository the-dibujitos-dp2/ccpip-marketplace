import iconoBuscar from '../assets/icons/buscar.svg'
import Icono from './Icono.jsx'
import './Buscador.css'

/**
 * Buscador en pildora de las pantallas de gestion (PW, A-03). Enviar el formulario busca;
 * `etiqueta` es el nombre accesible del campo, que no se ve.
 */
export default function Buscador({ id, etiqueta, onBuscar, className = '', ...campo }) {
  return (
    <form className={`buscador ${className}`} role="search" onSubmit={onBuscar}>
      <Icono src={iconoBuscar} tamano={20} />
      <label htmlFor={id} className="visually-hidden">{etiqueta}</label>
      <input id={id} type="search" {...campo} />
    </form>
  )
}
