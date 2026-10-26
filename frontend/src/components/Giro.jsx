import './Boton.css'

/**
 * CCPIP-413: el aro de una accion en curso, el mismo en toda la plataforma. Lo usa `Boton` con `cargando`, y los
 * botones con forma de icono o de enlace que esperan una respuesta (cerrar sesion, quitar del carrito, mejorar una
 * foto) lo ponen en lugar de su icono. Es decorativo: quien lo usa marca el boton con `aria-busy`.
 */
export default function Giro({ tamano = 16 }) {
  return <span className="boton__giro" style={{ width: tamano, height: tamano }} aria-hidden="true" />
}
