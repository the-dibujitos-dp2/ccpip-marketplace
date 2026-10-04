import { useSesion } from '../../sesion.js'
import CabeceraTienda from './CabeceraTienda.jsx'
import PieDePagina from './PieDePagina.jsx'

/** Estructura de toda pagina publica de la tienda web (CW): cabecera, contenido y pie. */
export default function LayoutTienda({ termino, children }) {
  const { user, cerrarSesion, cerrando } = useSesion()
  return (
    <div className="layout-tienda">
      <CabeceraTienda user={user} onCerrarSesion={cerrarSesion} cerrando={cerrando} termino={termino} />
      {children}
      <PieDePagina />
    </div>
  )
}
