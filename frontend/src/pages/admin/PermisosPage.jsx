import { useTranslation } from 'react-i18next'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import PermisosConfigurables from './PermisosConfigurables.jsx'
import '../productor/productor.css'
import './admin.css'

/**
 * CCPIP-28 y CCPIP-296: el superadministrador consulta los permisos de cada rol y los concede o revoca.
 * No se muestran las rutas y metodos del servidor: serian el mapa completo del API.
 */
export default function PermisosPage({ path }) {
  const { t } = useTranslation()
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.permisos')} titulo={t('admin.permisos.titulo')}>
      <PermisosConfigurables />
    </LayoutAdmin>
  )
}
