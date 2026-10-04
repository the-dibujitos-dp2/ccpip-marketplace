import { useTiendaActual } from '../../api/tiendaActual.js'
import LayoutProductor from '../../components/layout/LayoutProductor.jsx'
import { useSesion } from '../../sesion.js'

/** Envoltura de toda pantalla del productor: barra lateral con su tienda y cierre de sesion. */
export default function PaginaProductor({ path, seccion, titulo, acciones, children }) {
  const { user, cerrarSesion, cerrando } = useSesion()
  const tienda = useTiendaActual()
  const nombre = tienda?.nombreTienda ?? tienda?.comunidad
  return (
    <LayoutProductor
      path={path}
      user={user}
      tienda={nombre}
      migas={nombre ? `${nombre} / ${seccion}` : seccion}
      titulo={titulo}
      acciones={acciones}
      onCerrarSesion={cerrarSesion}
      cerrando={cerrando}
    >
      {children}
    </LayoutProductor>
  )
}
