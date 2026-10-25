import { useTranslation } from 'react-i18next'
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
  MINORISTA: { icono: iconoBolsa, clase: 'marca' },
  MAYORISTA: { icono: iconoPaquete, clase: 'info' },
  AMBAS: { icono: iconoPaquete, clase: 'neutro' },
}

/**
 * Tarjeta de catalogo (Figma 66:834). La comunidad va antes que el nombre: la historia primero.
 * `puesto` (CCPIP-339): su lugar entre los mas vendidos, en la esquina de la foto.
 */
export default function TarjetaProducto({ producto, puesto }) {
  const { t } = useTranslation()
  const { tienda } = producto
  const clave = MODALIDAD[producto.modalidad] ? producto.modalidad : 'MINORISTA'
  const modalidad = MODALIDAD[clave]
  const monto = producto.precio ?? producto.precioMayorista
  return (
    <Enlace to={`/productos/${producto.id}`} className="tarjeta-producto">
      <span className="tarjeta-producto__media">
        <ImagenProducto url={producto.imagen} linea={producto.linea?.codigo} className="tarjeta-producto__imagen" />
        {puesto != null && <span className="tarjeta-producto__puesto">{t('tarjetaProducto.puesto', { puesto })}</span>}
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
              {t('tarjetaProducto.agotado')}
            </span>
          )}
          <span className={`etiqueta-producto etiqueta-producto--${modalidad.clase}`}>
            <Icono src={modalidad.icono} tamano={14} />
            {t(`modalidad.${clave}`)}
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
