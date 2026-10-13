import iconoMovimientos from '../../assets/icons/movimientos.svg'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import LayoutPanel from './LayoutPanel.jsx'

/**
 * Solo los modulos que el punto ya puede usar: el tablero (A-01), inventario, merma, pedidos, etc.
 * llegan con sus historias. Mientras tanto, su inicio sigue siendo /inicio.
 */
const NAV = [
  { to: RUTAS.acopioMovimientos, texto: 'Movimientos', icono: iconoMovimientos, activo: (p) => p.startsWith(RUTAS.acopioMovimientos) },
]

/** Estructura web del administrador de punto de acopio (Figma A-01 a A-08). */
export default function LayoutAcopio({ path, punto, titulo, children }) {
  const { user, cerrarSesion, cerrando } = useSesion()
  return (
    <LayoutPanel
      path={path}
      rol="Punto de acopio"
      nav={NAV}
      user={user}
      subtitulo={punto}
      migas={punto ? `${punto} / ${titulo}` : titulo}
      titulo={titulo}
      onCerrarSesion={cerrarSesion}
      cerrando={cerrando}
    >
      {children}
    </LayoutPanel>
  )
}
