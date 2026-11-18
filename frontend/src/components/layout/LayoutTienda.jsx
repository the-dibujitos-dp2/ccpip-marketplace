import { useSesion } from '../../sesion.js'
import CabeceraTienda from './CabeceraTienda.jsx'
import DecoracionBotanica from './DecoracionBotanica.jsx'
import PieDePagina from './PieDePagina.jsx'

import { ContextoVisualConsumidor } from '../ContextoVisualConsumidor.jsx'
import '../../pages/consumidor/ConsumerEditorial.css'
import '../../pages/consumidor/RutasEditorial.css'

/** Estructura de toda pagina publica de la tienda web (CW): cabecera, contenido y pie. */
export default function LayoutTienda({ termino, children }) {
  const { user, cerrarSesion, cerrando } = useSesion()
  return (
    <ContextoVisualConsumidor.Provider value={true}>
      <div className="layout-tienda">
        <CabeceraTienda user={user} onCerrarSesion={cerrarSesion} cerrando={cerrando} termino={termino} />
        {children}
        <PieDePagina />
        {/* CCPIP-435: el Herbario de los márgenes, al final para no anteceder al contenido */}
        <DecoracionBotanica />
      </div>
    </ContextoVisualConsumidor.Provider>
  )
}
