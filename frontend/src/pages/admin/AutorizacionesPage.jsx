import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { bandejaAutorizaciones } from '../../api/autorizaciones.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { diaMes } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import '../productor/productor.css'
import './admin.css'

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
      <Pestanas
        etiqueta={t('autorizaciones.pestanasEtiqueta')}
        valor={estado}
        onCambiar={setEstado}
        opciones={ESTADOS.map((e) => ({ valor: e, etiqueta: t(`autorizaciones.pestana.${e}`) }))}
      />
      {error && <Alerta tono="error" titulo={mensajeError(error, 'autorizaciones.error')} />}
      {!datos && !error && <Cargando />}
      {datos && datos.solicitudes.length === 0 && <EstadoVacio titulo={t(`autorizaciones.vacio.${estado}`)} />}
      {datos && datos.solicitudes.length > 0 && (
        <div className="tabla" role="table" aria-label={t(`autorizaciones.pestana.${estado}`)}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('autorizaciones.col.producto')}</span>
            <span className="tabla__celda autorizaciones__organizacion" role="columnheader">{t('autorizaciones.col.organizacion')}</span>
            <span className="tabla__celda autorizaciones__evidencias" role="columnheader">{t('autorizaciones.col.evidencias')}</span>
            <span className="tabla__celda autorizaciones__analisis" role="columnheader">{t('autorizaciones.col.analisis')}</span>
            <span className="tabla__celda autorizaciones__fecha" role="columnheader">
              {estado === 'SOLICITADA' ? t('autorizaciones.col.enviada') : t('autorizaciones.col.decidida')}
            </span>
            <span className="tabla__celda autorizaciones__accion" role="columnheader"><span className="visually-hidden">{t('autorizaciones.col.accion')}</span></span>
          </div>
          {datos.solicitudes.map((s) => (
            <div key={s.id} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <span className="label-m">{s.producto}</span>
                <span className="caption autorizaciones__linea">{s.linea}</span>
              </span>
              <span className="tabla__celda autorizaciones__organizacion" role="cell">
                <span className="label-m">{s.organizacion}</span>
                <span className="caption autorizaciones__linea">{[s.pueblo, s.region].filter(Boolean).join(' · ')}</span>
              </span>
              <span className="tabla__celda autorizaciones__evidencias" role="cell">
                <span>{t('autorizaciones.evidencias', { count: s.evidencias })}</span>
                {s.certificadosVigentes > 0
                  ? <Badge tono="exito">{t('autorizaciones.certificados', { count: s.certificadosVigentes })}</Badge>
                  : <Badge tono="aviso">{t('autorizaciones.sinCertificado')}</Badge>}
              </span>
              <span className="tabla__celda autorizaciones__analisis" role="cell"><BadgeAnalisis recomendacion={s.recomendacion} /></span>
              <span className="tabla__celda autorizaciones__fecha" role="cell">{diaMes(estado === 'SOLICITADA' ? s.solicitadaEn : s.decididaEn)}</span>
              <span className="tabla__celda autorizaciones__accion" role="cell">
                <Boton tipo={estado === 'SOLICITADA' ? 'primario' : 'secundario'} tamano="S" to={`${RUTAS.adminAutorizaciones}/${s.id}`}
                  aria-label={t('autorizaciones.revisarDe', { producto: s.producto, organizacion: s.organizacion })}>
                  {estado === 'SOLICITADA' ? t('autorizaciones.revisar') : t('autorizaciones.ver')}
                </Boton>
              </span>
            </div>
          ))}
        </div>
      )}
      <p className="productor__nota">{t('autorizaciones.nota')}</p>
    </LayoutAdmin>
  )
}
