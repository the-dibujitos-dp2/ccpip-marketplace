import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { bandejaAutorizaciones } from '../../api/autorizaciones.js'
import Badge from '../../components/Badge.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { diaMes } from '../../formato.js'
import { RUTAS } from '../../router.js'
import '../productor/productor.css'
import './admin.css'
import { CeldaRevisar, CeldasSolicitud, PestanasEstado, TablaBandeja } from './revision.jsx'

const ESTADOS = ['SOLICITADA', 'APROBADA', 'RECHAZADA', 'REVOCADA']

/** Recomendacion del ultimo analisis preliminar; es una sugerencia, nunca una decision (CCPIP-50). */
export function BadgeAnalisis({ recomendacion }) {
  const { t } = useTranslation()
  if (recomendacion === 'SIN_OBSERVACIONES') return <Badge tono="exito">{t('autorizaciones.recomendacion.SIN_OBSERVACIONES')}</Badge>
  if (recomendacion === 'REVISAR') return <Badge tono="aviso">{t('autorizaciones.recomendacion.REVISAR')}</Badge>
  return <Badge tono="neutro">{t('autorizaciones.recomendacion.ninguna')}</Badge>
}

/**
 * S-01 adaptada (CCPIP-174, CCPIP-284, CCPIP-39): solicitudes de autorizacion de productos para el sub
 * administrador. Pendientes primero las mas antiguas; las resueltas y revocadas son las decididas en la
 * plataforma.
 */
export default function AutorizacionesPage({ path }) {
  const { t } = useTranslation()
  const [estado, setEstado] = useState('SOLICITADA')
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)

  const cargar = useCallback(async (e) => {
    setError(null)
    try {
      setDatos(await bandejaAutorizaciones(e))
    } catch (err) {
      setError(err)
    }
  }, [])

  useEffect(() => {
    cargar(estado)
  }, [cargar, estado])

  const c = datos?.contadores
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.autorizaciones')} titulo={t('autorizaciones.titulo')}>
      {c && (
        <div className="productor__kpis">
          <TarjetaKpi titulo={t('autorizaciones.kpi.pendientes')} valor={c.pendientes} detalle={t('autorizaciones.kpi.pendientesDetalle')} tono="neutro" icono={iconoReloj} />
          <TarjetaKpi titulo={t('autorizaciones.kpi.aprobadas')} valor={c.aprobadasMes} detalle={t('autorizaciones.kpi.conMotivo')} icono={iconoCheck} />
          <TarjetaKpi titulo={t('autorizaciones.kpi.rechazadas')} valor={c.rechazadasMes} detalle={t('autorizaciones.kpi.conMotivo')} tono="neutro" icono={iconoProhibido} />
          <TarjetaKpi titulo={t('autorizaciones.kpi.revocadas')} valor={c.revocadasMes ?? 0} detalle={t('autorizaciones.kpi.conMotivo')} tono="neutro" icono={iconoProhibido} />
        </div>
      )}
      <PestanasEstado textos="autorizaciones" estados={ESTADOS} valor={estado} onCambiar={setEstado} />
      <TablaBandeja textos="autorizaciones" estado={estado} datos={datos} error={error} columnas={[
        { clase: 'tabla__celda--flex', texto: t('autorizaciones.col.producto') },
        { clase: 'autorizaciones__organizacion', texto: t('autorizaciones.col.organizacion') },
        { clase: 'autorizaciones__evidencias', texto: t('autorizaciones.col.evidencias') },
        { clase: 'autorizaciones__analisis', texto: t('autorizaciones.col.analisis') },
        { clase: 'autorizaciones__fecha', texto: estado === 'SOLICITADA' ? t('autorizaciones.col.enviada') : t('autorizaciones.col.decidida') },
        { clase: 'autorizaciones__accion', texto: <span className="visually-hidden">{t('autorizaciones.col.accion')}</span> },
      ]} fila={(s) => (
        <div key={s.id} className="tabla__fila" role="row">
          <CeldasSolicitud solicitud={s} />
          <span className="tabla__celda autorizaciones__evidencias" role="cell">
            <span>{t('autorizaciones.evidencias', { count: s.evidencias })}</span>
            {s.certificadosVigentes > 0
              ? <Badge tono="exito">{t('autorizaciones.certificados', { count: s.certificadosVigentes })}</Badge>
              : <Badge tono="aviso">{t('autorizaciones.sinCertificado')}</Badge>}
          </span>
          <span className="tabla__celda autorizaciones__analisis" role="cell"><BadgeAnalisis recomendacion={s.recomendacion} /></span>
          <span className="tabla__celda autorizaciones__fecha" role="cell">{diaMes(estado === 'SOLICITADA' ? s.solicitadaEn : s.decididaEn)}</span>
          <CeldaRevisar textos="autorizaciones" solicitud={s} pendiente={estado === 'SOLICITADA'} to={`${RUTAS.adminAutorizaciones}/${s.id}`} />
        </div>
      )} />
      <p className="productor__nota">{t('autorizaciones.nota')}</p>
    </LayoutAdmin>
  )
}
