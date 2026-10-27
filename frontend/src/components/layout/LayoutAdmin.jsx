import { useTranslation } from 'react-i18next'
import iconoAlmacen from '../../assets/icons/almacen.svg'
import iconoAviso from '../../assets/icons/alerta-aviso.svg'
import iconoBillete from '../../assets/icons/billete.svg'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoEtiqueta from '../../assets/icons/etiqueta.svg'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoMerma from '../../assets/icons/merma.svg'
import iconoPorcentaje from '../../assets/icons/porcentaje.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import iconoTablero from '../../assets/icons/tablero.svg'
import iconoUsuario from '../../assets/icons/usuario.svg'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import LayoutPanel from './LayoutPanel.jsx'

/**
 * Solo los modulos que ya existen. Auditoria y los demas reportes (SA-06 a SA-10) llegan con sus historias.
 * El catalogo maestro (lineas y productos) es del superadministrador. `roles`: quienes ven cada modulo.
 */
const NAV = [
  { to: RUTAS.admin, clave: 'tablero', icono: iconoTablero, roles: ['SUPER_ADMIN'] },
  { to: RUTAS.adminPermisos, clave: 'permisos', icono: iconoEscudo, roles: ['SUPER_ADMIN'] },
  { to: RUTAS.adminAutorizaciones, clave: 'autorizaciones', icono: iconoEscudo, roles: ['SUB_ADMIN'], prefijo: true },
  // CCPIP-46: lo que los productores piden publicar
  { to: RUTAS.adminModeracion, clave: 'moderacion', icono: iconoEtiqueta, roles: ['SUB_ADMIN'], prefijo: true },
  { to: RUTAS.adminProductores, clave: 'productores', icono: iconoComunidad, roles: ['SUB_ADMIN'], prefijo: true },
  { to: RUTAS.adminDenuncias, clave: 'denuncias', icono: iconoAviso, roles: ['SUB_ADMIN', 'SUPER_ADMIN'], prefijo: true },
  { to: RUTAS.adminComision, clave: 'comision', icono: iconoPorcentaje, roles: ['SUPER_ADMIN'] },
  { to: RUTAS.adminComisiones, clave: 'comisiones', icono: iconoBillete, roles: ['SUPER_ADMIN', 'SUB_ADMIN'] },
  // SA-05 (CCPIP-345): reportes agregados de la plataforma
  { to: RUTAS.adminReportes, clave: 'reportes', icono: iconoGrafico, roles: ['SUPER_ADMIN'] },
  { to: RUTAS.adminParametros, clave: 'parametros', icono: iconoReloj, roles: ['SUPER_ADMIN'] },
  { to: RUTAS.adminCatalogo, clave: 'catalogo', icono: iconoEtiqueta, roles: ['SUPER_ADMIN'] },
  // CCPIP-354: existencias agregadas de los puntos de acopio (sin el detalle de A-02, SA-08)
  { to: RUTAS.adminExistencias, clave: 'existencias', icono: iconoAlmacen, roles: ['SUPER_ADMIN'] },
  // SA-03, SA-04 (CCPIP-95, 124, 125, 301 a 308): lo que cada rol ve lo decide el servidor
  { to: RUTAS.adminCuentas, clave: 'cuentas', icono: iconoUsuario, roles: ['SUPER_ADMIN', 'SUB_ADMIN'], prefijo: true },
  // CCPIP-77, 298, 299 y 300: catalogo de causas de merma
  { to: RUTAS.adminMotivosMerma, clave: 'motivosMerma', icono: iconoMerma, roles: ['SUPER_ADMIN'] },
  // S-05 (CCPIP-126): las acciones de gobernanza propias del sub administrador
  { to: RUTAS.adminMiHistorial, clave: 'miHistorial', icono: iconoReloj, roles: ['SUB_ADMIN'] },
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
