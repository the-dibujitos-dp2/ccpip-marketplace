import { useTranslation } from 'react-i18next'
import iconoAviso from '../../assets/icons/alerta-aviso.svg'
import iconoBillete from '../../assets/icons/billete.svg'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import iconoPorcentaje from '../../assets/icons/porcentaje.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import iconoTablero from '../../assets/icons/tablero.svg'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import LayoutPanel from './LayoutPanel.jsx'

/**
 * Solo los modulos que ya existen. Sub administradores, lineas, reportes, auditoria y usuarios
 * (SA-03 a SA-10) llegan con sus historias. `roles`: quienes ven cada modulo.
 */
const NAV = [
  { to: RUTAS.admin, clave: 'tablero', icono: iconoTablero, roles: ['SUPER_ADMIN'] },
  { to: RUTAS.adminAutorizaciones, clave: 'autorizaciones', icono: iconoEscudo, roles: ['SUB_ADMIN'], prefijo: true },
  { to: RUTAS.adminDenuncias, clave: 'denuncias', icono: iconoAviso, roles: ['SUB_ADMIN', 'SUPER_ADMIN'], prefijo: true },
  { to: RUTAS.adminComision, clave: 'comision', icono: iconoPorcentaje, roles: ['SUPER_ADMIN'] },
  { to: RUTAS.adminComisiones, clave: 'comisiones', icono: iconoBillete, roles: ['SUPER_ADMIN', 'SUB_ADMIN'] },
  { to: RUTAS.adminParametros, clave: 'parametros', icono: iconoReloj, roles: ['SUPER_ADMIN'] },
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
