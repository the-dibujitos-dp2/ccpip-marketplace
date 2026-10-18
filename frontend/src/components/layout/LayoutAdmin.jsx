import { useTranslation } from 'react-i18next'
import iconoBillete from '../../assets/icons/billete.svg'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import iconoPorcentaje from '../../assets/icons/porcentaje.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import iconoTablero from '../../assets/icons/tablero.svg'
import iconoUsuario from '../../assets/icons/usuario.svg'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import LayoutPanel from './LayoutPanel.jsx'

/**
 * Solo los modulos que ya existen. Lineas, reportes y auditoria (SA-05 a SA-10) llegan con sus historias. `roles`: quienes ven cada modulo.
 */
const NAV = [
  { to: RUTAS.admin, clave: 'tablero', icono: iconoTablero, roles: ['SUPER_ADMIN'] },
  { to: RUTAS.adminAutorizaciones, clave: 'autorizaciones', icono: iconoEscudo, roles: ['SUB_ADMIN'], prefijo: true },
  { to: RUTAS.adminComision, clave: 'comision', icono: iconoPorcentaje, roles: ['SUPER_ADMIN'] },
  { to: RUTAS.adminComisiones, clave: 'comisiones', icono: iconoBillete, roles: ['SUPER_ADMIN', 'SUB_ADMIN'] },
  { to: RUTAS.adminParametros, clave: 'parametros', icono: iconoReloj, roles: ['SUPER_ADMIN'] },
  // SA-03, SA-04 (CCPIP-95, 124, 125, 301 a 308): lo que cada rol ve lo decide el servidor
  { to: RUTAS.adminCuentas, clave: 'cuentas', icono: iconoUsuario, roles: ['SUPER_ADMIN', 'SUB_ADMIN'], prefijo: true },
]

/** Estructura web de la administracion de la Camara (Figma SA-01 a SA-10). */
export default function LayoutAdmin({ path, migas, titulo, children }) {
  const { t } = useTranslation()
  const { user, cerrarSesion, cerrando } = useSesion()
  const rol = t(`roles.${user.role}`, { defaultValue: user.roleName })
  const nav = NAV.filter((n) => n.roles.includes(user.role)).map((n) => ({
    ...n,
    texto: t(`admin.nav.${n.clave}`),
    activo: (p) => p === n.to || (n.prefijo && p.startsWith(`${n.to}/`)),
  }))
  return (
    <LayoutPanel
      path={path}
      rol={rol}
      nav={nav}
      user={user}
      subtitulo={rol}
      migas={`${t('admin.plataforma')} / ${migas}`}
      titulo={titulo}
      onCerrarSesion={cerrarSesion}
      cerrando={cerrando}
    >
      {children}
    </LayoutPanel>
  )
}
