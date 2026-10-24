import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarPermisos } from '../../api/admin.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Cargando from '../../components/Cargando.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import '../productor/productor.css'
import './admin.css'

/** CCPIP-28: consulta de las reglas de acceso declaradas en el servidor; esta pantalla no modifica roles. */
export default function PermisosPage({ path }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => consultarPermisos(), [])
  const { datos, error } = useConsulta(consultar)

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.permisos')} titulo={t('admin.permisos.titulo')}>
      <p className="body-s">{t('admin.permisos.descripcion')}</p>
      {error && <Alerta tono="error" titulo={t('admin.permisos.error')} descripcion={t('comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {datos && <Tabla permisos={datos} />}
    </LayoutAdmin>
  )
}

function Tabla({ permisos }) {
  const { t } = useTranslation()
  return (
    <div className="tabla" role="table" aria-label={t('admin.permisos.tabla')}>
      <div className="tabla__fila tabla__fila--encabezado" role="row">
        <span className="tabla__celda" role="columnheader">{t('admin.permisos.rol')}</span>
        <span className="tabla__celda" role="columnheader">{t('admin.permisos.metodo')}</span>
        <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.permisos.ruta')}</span>
      </div>
      {permisos.flatMap(({ rol, permisos: accesos }) => accesos.length
        ? accesos.map(({ metodo, ruta }) => (
          <div key={`${rol}:${metodo}:${ruta}`} className="tabla__fila permisos__fila" role="row">
            <span className="tabla__celda" role="cell">{t(`roles.${rol}`, { defaultValue: rol })}</span>
            <span className="tabla__celda" role="cell">{metodo}</span>
            <span className="tabla__celda tabla__celda--flex permisos__ruta" role="cell">{ruta}</span>
          </div>
        ))
        : [<div key={rol} className="tabla__fila" role="row">
          <span className="tabla__celda" role="cell">{t(`roles.${rol}`, { defaultValue: rol })}</span>
          <span className="tabla__celda tabla__celda--flex" role="cell">{t('admin.permisos.vacio')}</span>
        </div>])}
    </div>
  )
}
