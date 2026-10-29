import { useCallback } from 'react'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import Paginacion from '../../components/Paginacion.jsx'
import { useTranslation } from 'react-i18next'
import iconoAlmacen from '../../assets/icons/almacen.svg'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { existenciasAgregadas } from '../../api/admin.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Cargando from '../../components/Cargando.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaLarga, unidades } from '../../formato.js'
import '../productor/productor.css'
import './admin.css'
import './ExistenciasPage.css'

/**
 * CCPIP-354 (RF-244): el superadministrador consulta las existencias agregadas de los puntos de acopio.
 * Solo lectura y solo cifras agregadas por punto y por linea: el detalle por productor y lote es del
 * administrador de cada punto (A-02), asi que esta pantalla no enlaza a ningun inventario (SA-08).
 */
export default function ExistenciasPage({ path }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => existenciasAgregadas(), [])
  const { datos, error } = useConsulta(consultar)

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.existencias')} titulo={t('admin.existencias.titulo')}>
      {error && <Alerta tono="error" titulo={t('admin.existencias.error')} descripcion={t('comun.errorConexion')} />}
      {!datos && !error && <Cargando />}
      {datos && <Resultado datos={datos} />}
    </LayoutAdmin>
  )
}

function Resultado({ datos }) {
  const { t } = useTranslation()
  const { totales, puntos, lineas } = datos
  const paginaPuntos = usePaginacionLocal(puntos)
  const paginaLineas = usePaginacionLocal(lineas)
  return (
    <>
      <p className="body-s">{t('admin.existencias.corte', { fecha: fechaLarga(datos.fecha) })}</p>
      <div className="productor__kpis">
        <TarjetaKpi titulo={t('admin.existencias.kpi.puntos')} valor={`${totales.puntosConExistencias} / ${totales.puntos}`}
          detalle={t('admin.existencias.kpi.puntosDetalle')} tono="neutro" icono={iconoAlmacen} />
        <TarjetaKpi titulo={t('admin.existencias.kpi.productos')} valor={String(totales.productos)}
          detalle={t('admin.existencias.kpi.lotes', { count: totales.lotes })} tono="neutro" icono={iconoCaja} />
        <TarjetaKpi titulo={t('admin.existencias.kpi.vencidos')} valor={String(totales.lotesVencidos)}
          detalle={t('admin.existencias.kpi.vencidosDetalle')} tono={totales.lotesVencidos > 0 ? 'error' : 'neutro'} icono={iconoReloj} />
      </div>

      <section className="productor__seccion" aria-labelledby="existencias-puntos">
        <h2 id="existencias-puntos" className="heading-h3">{t('admin.existencias.porPunto')}</h2>
        <div className="tabla" role="table" aria-labelledby="existencias-puntos">
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.existencias.col.punto')}</span>
            <span className="tabla__celda existencias__conteo" role="columnheader">{t('admin.existencias.col.productos')}</span>
            <span className="tabla__celda existencias__conteo" role="columnheader">{t('admin.existencias.col.productores')}</span>
            <span className="tabla__celda existencias__conteo" role="columnheader">{t('admin.existencias.col.lotes')}</span>
            <span className="tabla__celda existencias__cantidades" role="columnheader">{t('admin.existencias.col.vigente')}</span>
            <span className="tabla__celda existencias__cantidades" role="columnheader">{t('admin.existencias.col.vencido')}</span>
          </div>
          {paginaPuntos.filas.map((p) => (
            <div key={p.id} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <span className="label-m">{p.nombre}</span>
                <span className="caption existencias__lugar">{[p.provincia, p.departamento].filter(Boolean).join(', ')}</span>
              </span>
              <span className="tabla__celda existencias__conteo" role="cell">{p.productos}</span>
              <span className="tabla__celda existencias__conteo" role="cell">{p.productores}</span>
              <span className="tabla__celda existencias__conteo" role="cell">{p.lotes}</span>
              <span className="tabla__celda existencias__cantidades" role="cell"><Cantidades lista={p.cantidades} campo="vigente" /></span>
              <span className="tabla__celda existencias__cantidades" role="cell"><Cantidades lista={p.cantidades} campo="vencido" /></span>
            </div>
          ))}
        </div>
        <Paginacion {...paginaPuntos} />
      </section>

      <section className="productor__seccion" aria-labelledby="existencias-lineas">
        <h2 id="existencias-lineas" className="heading-h3">{t('admin.existencias.porLinea')}</h2>
        {lineas.length === 0 ? (
          <p className="body-s">{t('admin.existencias.vacio')}</p>
        ) : (
          <div className="tabla" role="table" aria-labelledby="existencias-lineas">
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('admin.existencias.col.linea')}</span>
              <span className="tabla__celda existencias__conteo" role="columnheader">{t('admin.existencias.col.productos')}</span>
              <span className="tabla__celda existencias__conteo" role="columnheader">{t('admin.existencias.col.puntos')}</span>
              <span className="tabla__celda existencias__cantidades" role="columnheader">{t('admin.existencias.col.vigente')}</span>
              <span className="tabla__celda existencias__cantidades" role="columnheader">{t('admin.existencias.col.vencido')}</span>
            </div>
            {paginaLineas.filas.map((l) => (
              <div key={l.codigo ?? 'sin-linea'} className="tabla__fila" role="row">
                <span className="tabla__celda tabla__celda--flex label-m" role="cell">{l.nombre ?? t('admin.existencias.sinLinea')}</span>
                <span className="tabla__celda existencias__conteo" role="cell">{l.productos}</span>
                <span className="tabla__celda existencias__conteo" role="cell">{l.puntos}</span>
                <span className="tabla__celda existencias__cantidades" role="cell"><Cantidades lista={l.cantidades} campo="vigente" /></span>
                <span className="tabla__celda existencias__cantidades" role="cell"><Cantidades lista={l.cantidades} campo="vencido" /></span>
              </div>
            ))}
          </div>
        )}
        <Paginacion {...paginaLineas} />
        <p className="caption">{t('admin.existencias.nota')}</p>
      </section>
    </>
  )
}

/** Una cantidad por unidad ("200 Kilogramo"); las unidades distintas no se suman entre si. */
function Cantidades({ lista, campo }) {
  const conSaldo = lista.filter((c) => Number(c[campo]) > 0)
  if (conSaldo.length === 0) return <span className="caption">—</span>
  return (
    <ul className="existencias__lista">
      {conSaldo.map((c) => <li key={c.unidad}>{`${unidades(c[campo])} ${c.nombreUnidad}`}</li>)}
    </ul>
  )
}
