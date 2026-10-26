import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { bandejaModeracion, bandejaVideos } from '../../api/moderacion.js'
import Badge from '../../components/Badge.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { diaMes } from '../../formato.js'
import { navigate, RUTAS } from '../../router.js'
import '../productor/productor.css'
import './admin.css'
import { CeldaRevisar, CeldasSolicitud, PestanasEstado, TablaBandeja } from './revision.jsx'

const ESTADOS = ['PENDIENTE', 'APROBADA', 'RECHAZADA']

/**
 * La autorizacion de venta de la oferta, que la moderacion no decide: solo la muestra (CCPIP-46). Aprobar
 * la publicacion exige que este aprobada y vigente (CCPIP-331).
 */
export function BadgeAutorizacion({ estado, vigente }) {
  const { t } = useTranslation()
  if (vigente) return <Badge tono="exito">{t('moderacion.autorizacion.vigente')}</Badge>
  return <Badge tono="aviso">{t(`moderacion.autorizacion.estado.${estado}`, { defaultValue: estado })}</Badge>
}

/** Que se revisa: las ofertas (CCPIP-46) o los videos de las tiendas (CCPIP-118). */
function QueRevisar({ videos }) {
  const { t } = useTranslation()
  return (
    <nav className="moderacion__que" aria-label={t('moderacion.que.etiqueta')}>
      {[[false, RUTAS.adminModeracion, 'ofertas'], [true, RUTAS.adminModeracionVideos, 'videos']].map(([esVideos, ruta, clave]) => (
        <a key={clave} href={ruta} className={`chip${videos === esVideos ? ' chip--activo' : ''}`} aria-current={videos === esVideos ? 'page' : undefined}
          onClick={(e) => { e.preventDefault(); navigate(ruta) }}>
          {t(`moderacion.que.${clave}`)}
        </a>
      ))}
    </nav>
  )
}

/** Duracion de un video en m:ss. */
export const duracion = (segundos) => `${Math.floor(segundos / 60)}:${String(Math.round(segundos % 60)).padStart(2, '0')}`

/**
 * CCPIP-46 (RF-21): las ofertas que los productores piden publicar y, con `videos`, los videos que suben a su
 * tienda (CCPIP-118). Pendientes primero los mas antiguos; los decididos, del mas reciente al mas antiguo. No
 * hay pantalla en Figma: sigue el formato de S-01.
 */
export default function ModeracionPage({ path, videos = false }) {
  const { t } = useTranslation()
  const [estado, setEstado] = useState('PENDIENTE')
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)

  const cargar = useCallback(async (e) => {
    setError(null)
    setDatos(null)
    try {
      setDatos(await (videos ? bandejaVideos : bandejaModeracion)(e))
    } catch (err) {
      setError(err)
    }
  }, [videos])

  useEffect(() => {
    cargar(estado)
  }, [cargar, estado])

  const c = datos?.contadores
  const pendiente = estado === 'PENDIENTE'
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.moderacion')} titulo={t('moderacion.titulo')}>
      <QueRevisar videos={videos} />
      {c && (
        <div className="productor__kpis">
          {[
            ['pendientes', c.pendientes, iconoReloj, 'neutro', 'pendientesDetalle'],
            ['aprobadas', c.aprobadasMes, iconoCheck, 'exito', 'conMotivo'],
            ['rechazadas', c.rechazadasMes, iconoProhibido, 'neutro', 'conMotivo'],
          ].map(([clave, valor, icono, tono, detalle]) => (
            <TarjetaKpi key={clave} titulo={t(`moderacion.kpi.${clave}`)} valor={valor} icono={icono} tono={tono} detalle={t(`moderacion.kpi.${detalle}`)} />
          ))}
        </div>
      )}
      <PestanasEstado textos="moderacion" estados={ESTADOS} valor={estado} onCambiar={setEstado} />
      {videos ? (
        <TablaBandeja textos="moderacion" estado={estado} datos={datos} error={error} columnas={[
          { clase: 'tabla__celda--flex', texto: t('moderacion.col.video') },
          { clase: 'autorizaciones__organizacion', texto: t('moderacion.col.organizacion') },
          { clase: 'moderacion__tipo', texto: t('moderacion.col.tipo') },
          { clase: 'autorizaciones__fecha', texto: pendiente ? t('moderacion.col.enviada') : t('moderacion.col.decidida') },
          { clase: 'autorizaciones__accion', texto: <span className="visually-hidden">{t('moderacion.col.accion')}</span> },
        ]} fila={(v) => {
          const s = { ...v, producto: v.titulo, linea: duracion(v.duracionSegundos) }
          return (
            <div key={v.id} className="tabla__fila" role="row">
              <CeldasSolicitud solicitud={s} />
              <span className="tabla__celda moderacion__tipo" role="cell">{v.reemplazo ? t('moderacion.tipo.reemplazo') : t('moderacion.tipo.nuevo')}</span>
              <span className="tabla__celda autorizaciones__fecha" role="cell">{diaMes(pendiente ? v.subidoEn : v.decididoEn)}</span>
              <CeldaRevisar textos="moderacion" solicitud={s} pendiente={pendiente} to={`${RUTAS.adminModeracionVideos}/${v.id}`} />
            </div>
          )
        }} />
      ) : (
        <TablaBandeja textos="moderacion" estado={estado} datos={datos} error={error} columnas={[
          { clase: 'tabla__celda--flex', texto: t('moderacion.col.oferta') },
          { clase: 'autorizaciones__organizacion', texto: t('moderacion.col.organizacion') },
          { clase: 'moderacion__autorizacion', texto: t('moderacion.col.autorizacion') },
          { clase: 'moderacion__tipo', texto: t('moderacion.col.tipo') },
          { clase: 'autorizaciones__fecha', texto: pendiente ? t('moderacion.col.enviada') : t('moderacion.col.decidida') },
          { clase: 'autorizaciones__accion', texto: <span className="visually-hidden">{t('moderacion.col.accion')}</span> },
        ]} fila={(s) => (
          <div key={s.id} className="tabla__fila" role="row">
            <CeldasSolicitud solicitud={s} />
            <span className="tabla__celda moderacion__autorizacion" role="cell"><BadgeAutorizacion estado={s.autorizacion} vigente={s.autorizada} /></span>
            <span className="tabla__celda moderacion__tipo" role="cell">{s.republicacion ? t('moderacion.tipo.republicacion') : t('moderacion.tipo.nueva')}</span>
            <span className="tabla__celda autorizaciones__fecha" role="cell">{diaMes(pendiente ? s.solicitadaEn : s.decididaEn)}</span>
            <CeldaRevisar textos="moderacion" solicitud={s} pendiente={pendiente} to={`${RUTAS.adminModeracion}/${s.id}`} />
          </div>
        )} />
      )}
      <p className="productor__nota">{t(videos ? 'moderacion.notaVideos' : 'moderacion.nota')}</p>
    </LayoutAdmin>
  )
}
