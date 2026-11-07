import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoComunidad from '../../assets/icons/comunidad.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { existenciasDePunto } from '../../api/admin.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaLarga } from '../../formato.js'
import { RUTAS } from '../../router.js'
import { Cantidades } from './ExistenciasPage.jsx'
import '../productor/productor.css'
import './admin.css'
import './ExistenciasPage.css'

/**
 * CCPIP-451: el detalle de existencias de un punto de acopio, abierto desde Existencias (CCPIP-354). Sigue
 * siendo agregado (SA-08): por linea dentro del punto, con sus lotes vencidos y por vencer, sin nombrar
 * productores ni productos, que es lo que ve el administrador del punto (A-02).
 */
export default function ExistenciasPuntoPage({ path, id }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => existenciasDePunto(id), [id])
  const { datos, error } = useConsulta(consultar)
  const noExiste = error?.status === 404
  const titulo = datos?.punto.nombre ?? t('admin.existencias.detalle.titulo')
  return (
    <LayoutAdmin path={path} migas={`${t('admin.nav.existencias')} / ${titulo}`} titulo={titulo}>
      <Boton tipo="terciario" tamano="S" to={RUTAS.adminExistencias}>{t('admin.existencias.detalle.volver')}</Boton>
      {error && <Alerta tono="error" titulo={t(noExiste ? 'admin.existencias.detalle.noExiste' : 'admin.existencias.error')}
        descripcion={noExiste ? undefined : t('comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {datos && <Detalle datos={datos} />}
    </LayoutAdmin>
  )
}

function Detalle({ datos }) {
  const { t } = useTranslation()
  const { punto, lineas } = datos
  return (
    <>
      <p className="body-s">
        {[punto.provincia, punto.departamento].filter(Boolean).join(', ')}
        {' · '}
        {t('admin.existencias.corte', { fecha: fechaLarga(datos.fecha) })}
      </p>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('admin.existencias.kpi.productos')} valor={String(punto.productos)}
          detalle={t('admin.existencias.kpi.lotes', { count: punto.lotes })} tono="neutro" icono={iconoCaja} />
        <TarjetaKpi titulo={t('admin.existencias.col.productores')} valor={String(punto.productores)}
          detalle={t('admin.existencias.detalle.productoresDetalle')} tono="neutro" icono={iconoComunidad} />
        <TarjetaKpi titulo={t('admin.existencias.kpi.vencidos')} valor={String(punto.lotesVencidos)}
          detalle={t('admin.existencias.detalle.porVencer', { count: datos.lotesPorVencer, dias: datos.diasPorVencer })}
          tono={punto.lotesVencidos > 0 ? 'error' : datos.lotesPorVencer > 0 ? 'aviso' : 'neutro'} icono={iconoReloj} />
      </div>

      <section className="productor__seccion" aria-labelledby="existencias-punto-lineas">
        <h2 id="existencias-punto-lineas" className="heading-h3">{t('admin.existencias.porLinea')}</h2>
        {lineas.length === 0 ? (
          <p className="body-s">{t('admin.existencias.detalle.vacio')}</p>
        ) : (
          <div className="tabla" role="table" aria-labelledby="existencias-punto-lineas" tabIndex={0}>
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.existencias.col.linea')}</span>
              <span className="tabla__celda existencias__conteo" role="columnheader">{t('admin.existencias.col.productos')}</span>
              <span className="tabla__celda existencias__conteo" role="columnheader">{t('admin.existencias.col.productores')}</span>
              <span className="tabla__celda existencias__conteo" role="columnheader">{t('admin.existencias.col.lotes')}</span>
              <span className="tabla__celda existencias__conteo" role="columnheader">{t('admin.existencias.detalle.colPorVencer')}</span>
              <span className="tabla__celda existencias__cantidades" role="columnheader">{t('admin.existencias.col.vigente')}</span>
              <span className="tabla__celda existencias__cantidades" role="columnheader">{t('admin.existencias.col.vencido')}</span>
            </div>
            {lineas.map((l) => (
              <div key={l.codigo ?? 'sin-linea'} className="tabla__fila" role="row">
                <span className="tabla__celda tabla__celda--flex label-m" role="cell">{l.nombre ?? t('admin.existencias.sinLinea')}</span>
                <span className="tabla__celda existencias__conteo" role="cell">{l.productos}</span>
                <span className="tabla__celda existencias__conteo" role="cell">{l.productores}</span>
                <span className="tabla__celda existencias__conteo" role="cell">{l.lotes}</span>
                <span className="tabla__celda existencias__conteo" role="cell">{l.lotesPorVencer}</span>
                <span className="tabla__celda existencias__cantidades" role="cell"><Cantidades lista={l.cantidades} campo="vigente" /></span>
                <span className="tabla__celda existencias__cantidades" role="cell"><Cantidades lista={l.cantidades} campo="vencido" /></span>
              </div>
            ))}
          </div>
        )}
        <p className="caption">{t('admin.existencias.nota')}</p>
      </section>
    </>
  )
}
