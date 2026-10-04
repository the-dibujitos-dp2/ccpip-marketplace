import Boton from '../components/Boton.jsx'
import EstadoVacio from '../components/EstadoVacio.jsx'
import { navigate, RUTAS } from '../router.js'

/** Destino del enlace de la ficha. El perfil completo de la comunidad llega con CCPIP-154. */
export default function ProductorPage() {
  return (
    <main className="catalogo">
      <EstadoVacio
        titulo="Muy pronto podrás conocer a esta comunidad"
        descripcion="Estamos preparando el perfil con su historia y todos sus productos."
      >
        <Boton tamano="M" onClick={() => navigate(RUTAS.catalogo)}>
          Volver al catálogo
        </Boton>
      </EstadoVacio>
    </main>
  )
}
