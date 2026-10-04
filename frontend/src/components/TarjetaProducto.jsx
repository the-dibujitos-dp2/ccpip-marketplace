import iconoBolsa from '../assets/icons/bolsa-marca.svg'
import iconoPaquete from '../assets/icons/paquete.svg'
import iconoReloj from '../assets/icons/reloj.svg'
import { precio } from '../formato.js'
import Enlace from './Enlace.jsx'
import Icono from './Icono.jsx'
import ImagenProducto from './ImagenProducto.jsx'
import SelloOrigen from './SelloOrigen.jsx'
import './TarjetaProducto.css'

const MODALIDAD = {
  MINORISTA: { texto: 'Minorista', icono: iconoBolsa, clase: 'marca' },
  MAYORISTA: { texto: 'Mayorista', icono: iconoPaquete, clase: 'info' },
  AMBAS: { texto: 'Minorista y mayorista', icono: iconoPaquete, clase: 'neutro' },
}

/** Tarjeta de catalogo (Figma 66:834). La comunidad va antes que el nombre: la historia primero. */
export default function TarjetaProducto({ producto }) {
  const { tienda } = producto
  const modalidad = MODALIDAD[producto.modalidad] ?? MODALIDAD.MINORISTA
  const monto = producto.precio ?? producto.precioMayorista
  return (
    <Enlace to={`/productos/${producto.id}`} className="tarjeta-producto">
      <span className="tarjeta-producto__media">
        <ImagenProducto url={producto.imagen} linea={producto.linea?.codigo} className="tarjeta-producto__imagen" />
        {tienda.origenVerificado && (
          <span className="tarjeta-producto__sello">
            <SelloOrigen variante="mini" />
          </span>
        )}
      </span>
      <span className="tarjeta-producto__info">
        <span className="caption">
          {tienda.nombre}
          {tienda.pueblo && ` · ${tienda.pueblo}`}
        </span>
        <span className="tarjeta-producto__nombre">{producto.nombre}</span>
        <span className="tarjeta-producto__etiquetas">
          {!producto.disponible && (
            <span className="etiqueta-producto etiqueta-producto--agotado">
              <Icono src={iconoReloj} tamano={14} />
              Agotado
            </span>
          )}
          <span className={`etiqueta-producto etiqueta-producto--${modalidad.clase}`}>
            <Icono src={modalidad.icono} tamano={14} />
            {modalidad.texto}
          </span>
        </span>
        <span className="tarjeta-producto__precio">
          {monto != null && <strong>{precio(monto)}</strong>}
          {producto.contenido && <span className="caption">· {producto.contenido}</span>}
        </span>
      </span>
    </Enlace>
  )
}
