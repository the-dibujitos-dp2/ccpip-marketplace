import { useTranslation } from 'react-i18next'
import iconoAviso from '../../assets/icons/alerta-aviso.svg'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { bandejaDenuncias } from '../../api/valoracion.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { diaMes } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import '../productor/productor.css'
import './admin.css'

/**
 * CCPIP-84 (RF-77): denuncias contra productores, separadas de los reportes analiticos del tablero.
 * Los productores llegan ordenados por cantidad de denuncias. Quien denuncio solo se ve en el expediente
 * (CCPIP-432); el productor nunca lo ve (RNF-41).
 */
export default function DenunciasPage({ path }) {
  const { t } = useTranslation()
  const { datos, error } = useConsulta(bandejaDenuncias)
  const c = datos?.contadores
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.denuncias')} titulo={t('denunciasAdmin.titulo')}>
      {c && (
        <div className="productor__kpis">
          <TarjetaKpi titulo={t('denunciasAdmin.kpi.recibidas')} valor={c.recibidas} detalle={t('denunciasAdmin.kpi.sinRevisar')} tono="neutro" icono={iconoAviso} />
          <TarjetaKpi titulo={t('denunciasAdmin.kpi.enRevision')} valor={c.enRevision} detalle={t('denunciasAdmin.kpi.abiertas')} tono="neutro" icono={iconoReloj} />
          <TarjetaKpi titulo={t('denunciasAdmin.kpi.resueltas')} valor={c.resueltas} detalle={t('denunciasAdmin.kpi.cerradas')} icono={iconoCheck} />
          <TarjetaKpi titulo={t('denunciasAdmin.kpi.desestimadas')} valor={c.desestimadas} detalle={t('denunciasAdmin.kpi.cerradas')} tono="neutro" icono={iconoProhibido} />
        </div>
      )}
      {error && <Alerta tono="error" titulo={mensajeError(error, 'denunciasAdmin.error')} />}
      {!datos && !error && <Cargando />}
      {datos && datos.productores.length === 0 && <EstadoVacio titulo={t('denunciasAdmin.vacio')} />}
      {datos?.productores.length > 0 && (
        <div className="tabla" role="table" aria-label={t('denunciasAdmin.tabla')}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('denunciasAdmin.col.productor')}</span>
            <span className="tabla__celda denuncias__num" role="columnheader">{t('denunciasAdmin.col.total')}</span>
            <span className="tabla__celda denuncias__num" role="columnheader">{t('denunciasAdmin.col.abiertas')}</span>
            <span className="tabla__celda denuncias__ultima" role="columnheader">{t('denunciasAdmin.col.ultima')}</span>
            <span className="tabla__celda denuncias__accion" role="columnheader"><span className="visually-hidden">{t('denunciasAdmin.col.accion')}</span></span>
          </div>
          {datos.productores.map((p) => (
            <div key={p.productorId} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <span className="label-m">{p.organizacion}</span>
                <span className="caption">{[p.comunidad, p.region].filter(Boolean).join(' · ')}</span>
                {p.ventaBloqueada && <Badge tono="error">{t('productores.venta.bloqueada')}</Badge>}
              </span>
              <span className="tabla__celda denuncias__num" role="cell">{p.total}</span>
              <span className="tabla__celda denuncias__num" role="cell">{p.abiertas}</span>
              <span className="tabla__celda denuncias__ultima" role="cell">{diaMes(p.ultimaEn)}</span>
              <span className="tabla__celda denuncias__accion" role="cell">
                <Boton tipo={p.abiertas > 0 ? 'primario' : 'secundario'} tamano="S" to={`${RUTAS.adminDenuncias}/productores/${p.productorId}`}
                  aria-label={t('denunciasAdmin.verHistorialDe', { organizacion: p.organizacion })}>
                  {t('denunciasAdmin.verHistorial')}
                </Boton>
              </span>
            </div>
          ))}
        </div>
      )}
      <p className="productor__nota">{t('denunciasAdmin.notaPie')}</p>
    </LayoutAdmin>
  )
}
