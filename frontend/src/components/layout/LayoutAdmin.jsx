import { useTranslation } from 'react-i18next'
import iconoTablero from '../../assets/icons/tablero.svg'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import LayoutPanel from './LayoutPanel.jsx'

/**
 * Solo los modulos que el superadministrador ya puede usar: comision, sub administradores,
 * lineas, reportes, auditoria y usuarios (SA-02 a SA-10) llegan con sus historias.
 */
const NAV = [{ to: RUTAS.admin, clave: 'tablero', icono: iconoTablero, activo: (p) => p === RUTAS.admin }]

/** Estructura web del superadministrador (Figma SA-01 a SA-10). */
export default function LayoutAdmin({ path, migas, titulo, children }) {
  const { t } = useTranslation()
  const { user, cerrarSesion, cerrando } = useSesion()
  const nav = NAV.map((n) => ({ ...n, texto: t(`admin.nav.${n.clave}`) }))
  return (
    <LayoutPanel
      path={path}
      rol={t('admin.rol')}
      nav={nav}
      user={user}
      subtitulo={t('roles.SUPER_ADMIN', { defaultValue: user.roleName })}
      migas={`${t('admin.plataforma')} / ${migas}`}
      titulo={titulo}
      onCerrarSesion={cerrarSesion}
      cerrando={cerrando}
    >
      {children}
    </LayoutPanel>
  )
}
