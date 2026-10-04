import ImagenProducto from './ImagenProducto.jsx'
import Enlace from './Enlace.jsx'
import SelloOrigen from './SelloOrigen.jsx'
import './TarjetaTienda.css'

/** Tarjeta "Conoce a quienes lo hacen" (CW-01) y del listado de comunidades (CCPIP-153). */
export default function TarjetaTienda({ tienda }) {
  return (
    <article className="tarjeta-tienda">
      <ImagenProducto url={tienda.portada} linea="PORTADA" className="tarjeta-tienda__portada" />
      <div className="tarjeta-tienda__cuerpo">
        {tienda.origenVerificado && <SelloOrigen variante="compacto" />}
        <h3 className="tarjeta-tienda__nombre">{tienda.nombre}</h3>
        <p className="caption">{[tienda.pueblo, tienda.region].filter(Boolean).join(' · ')}</p>
        {tienda.cita && <p className="tarjeta-tienda__cita">“{tienda.cita}”</p>}
        <Enlace to={`/tiendas/${tienda.id}`} className="enlace-marca tarjeta-tienda__enlace">
          Visitar tienda<span className="visually-hidden"> de {tienda.nombre}</span> <span aria-hidden="true">→</span>
        </Enlace>
      </div>
    </article>
  )
}
