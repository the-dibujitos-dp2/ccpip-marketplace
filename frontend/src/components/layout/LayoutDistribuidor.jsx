import { useTranslation } from 'react-i18next'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoCamion from '../../assets/icons/camion.svg'
import iconoTablero from '../../assets/icons/tablero.svg'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import LayoutPanel from './LayoutPanel.jsx'

/**
 * Lo que el distribuidor puede usar: su Inicio con el resumen del reparto (CCPIP-500), Pedidos disponibles
 * (DW-01, CCPIP-107) y Mis entregas (DW-03, CCPIP-317).
 */
const NAV = [
  { to: RUTAS.distribuidor, clave: 'inicio', icono: iconoTablero, activo: (p) => p === RUTAS.distribuidor },
  { to: RUTAS.distribuidorPedidos, clave: 'pedidos', icono: iconoCaja, activo: (p) => p.startsWith(RUTAS.distribuidorPedidos) },
  { to: RUTAS.distribuidorEntregas, clave: 'entregas', icono: iconoCamion, activo: (p) => p.startsWith(RUTAS.distribuidorEntregas) },
]

/** Estructura web del distribuidor (Figma DW-01 a DW-04). */
export default function LayoutDistribuidor({ path, titulo, children }) {
  const { t } = useTranslation()
  const { user, cerrarSesion, cerrando } = useSesion()
  const nav = NAV.map((n) => ({ ...n, texto: t(`distribuidor.nav.${n.clave}`) }))
  return (
    <LayoutPanel
      path={path}
      rol={t('distribuidor.rol')}
      nav={nav}
      user={user}
      migas={`${t('distribuidor.migas')} / ${titulo}`}
      titulo={titulo}
      onCerrarSesion={cerrarSesion}
      cerrando={cerrando}
    >
      {children}
    </LayoutPanel>
  )
}
