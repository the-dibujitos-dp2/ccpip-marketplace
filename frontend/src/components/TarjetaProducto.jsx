import { precio } from '../formato.js'
import BadgeDisponibilidad from './BadgeDisponibilidad.jsx'
import Enlace from './Enlace.jsx'
import ImagenProducto from './ImagenProducto.jsx'
import SelloOrigen from './SelloOrigen.jsx'
import './TarjetaProducto.css'

/** Tarjeta de catalogo (Figma). La comunidad va antes que el nombre: la historia primero. */
export default function TarjetaProducto({ producto }) {
  const { productor } = producto
  return (
    <Enlace to={`/productos/${producto.id}`} className="tarjeta-producto">
      <span className="tarjeta-producto__media">
        <ImagenProducto linea={producto.linea.codigo} className="tarjeta-producto__imagen" />
        {productor.origenVerificado && (
          <span className="tarjeta-producto__sello">
            <SelloOrigen variante="mini" />
          </span>
        )}
      </span>
      <span className="tarjeta-producto__info">
        <span className="caption">
          {productor.comunidad} · {productor.region}
        </span>
        <span className="tarjeta-producto__nombre">{producto.nombre}</span>
        <BadgeDisponibilidad disponible />
        <span className="tarjeta-producto__precio">{precio(producto.precio)}</span>
      </span>
    </Enlace>
  )
}
