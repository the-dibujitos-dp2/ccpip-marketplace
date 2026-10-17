import { useTranslation } from 'react-i18next'
import iconoAlmacen from '../../assets/icons/almacen.svg'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoMovimientos from '../../assets/icons/movimientos.svg'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import LayoutPanel from './LayoutPanel.jsx'

/**
 * Solo los modulos que el punto ya puede usar: el tablero (A-01), merma, reportes, etc.
 * llegan con sus historias. Mientras tanto, su inicio sigue siendo /inicio.
 */
const NAV = [
  { to: RUTAS.acopioPedidos, clave: 'pedidos', icono: iconoCaja, activo: (p) => p.startsWith(RUTAS.acopioPedidos) },
  { to: RUTAS.acopioMovimientos, clave: 'movimientos', icono: iconoMovimientos, activo: (p) => p.startsWith(RUTAS.acopioMovimientos) },
  // A-02 (CCPIP-162): inventario por producto, productor y lote
  { to: RUTAS.acopioInventario, clave: 'inventario', icono: iconoAlmacen, activo: (p) => p.startsWith(RUTAS.acopioInventario) },
  { to: RUTAS.acopioTransferencias, clave: 'transferencias', icono: iconoMovimientos, activo: (p) => p.startsWith(RUTAS.acopioTransferencias) },
]

/** Estructura web del administrador de punto de acopio (Figma A-01 a A-08). */
export default function LayoutAcopio({ path, punto, titulo, children }) {
  const { t } = useTranslation()
  const { user, cerrarSesion, cerrando } = useSesion()
  const nav = NAV.map((n) => ({ ...n, texto: t(`acopio.nav.${n.clave}`) }))
  return (
    <LayoutPanel
      path={path}
      rol={t('acopio.rol')}
      nav={nav}
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
