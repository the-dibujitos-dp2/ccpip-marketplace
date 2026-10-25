import { useTranslation } from 'react-i18next'
import iconoCaja from '../../assets/icons/caja.svg'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import LayoutPanel from './LayoutPanel.jsx'

/**
 * Solo lo que el distribuidor ya puede usar: Pedidos disponibles (DW-01, CCPIP-107). Mis entregas, el mapa
 * y el historial llegan con sus historias (CCPIP-108 a 110, 317).
 */
const NAV = [
  { to: RUTAS.distribuidorPedidos, clave: 'pedidos', icono: iconoCaja, activo: (p) => p.startsWith(RUTAS.distribuidorPedidos) },
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
