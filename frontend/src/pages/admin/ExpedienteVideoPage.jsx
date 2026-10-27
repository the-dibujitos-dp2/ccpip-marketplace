import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import { aprobarVideo, expedienteVideo, rechazarVideo } from '../../api/moderacion.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { duracion, fechaHora } from '../../formato.js'
import { RUTAS } from '../../router.js'
import { CabeceraSolicitud, CampoMotivo, DialogoDecision, SinExpediente, useDecision } from './revision.jsx'
import '../productor/productor.css'
import './admin.css'

const TONO_ESTADO = { PENDIENTE: 'info', APROBADA: 'exito', RECHAZADA: 'error' }
const ENVIAR = { aprobar: aprobarVideo, rechazar: rechazarVideo }
const RECARGAR_SI = ['VIDEO_YA_RESUELTO']

/** El video con su titulo, duracion, formato y peso. */
function Reproductor({ video, etiqueta }) {
  const { t } = useTranslation()
  return (
    <figure className="moderacion__video">
      <video src={video.url} controls preload="metadata" aria-label={etiqueta} />
      <figcaption className="caption">
        {[duracion(video.duracionSegundos), video.formato, video.bytes && t('moderacion.video.megas', { mb: (video.bytes / 1048576).toFixed(1) })]
          .filter(Boolean).join(' · ')}
      </figcaption>
    </figure>
  )
}

/**
 * CCPIP-118: el subadministrador mira el video que un productor subio a su tienda y lo aprueba (se ve en la
 * tienda) o lo rechaza, siempre con motivo, que el productor ve. Si reemplaza a otro (CCPIP-391), se ven
 * los dos. No hay pantalla en Figma: sigue el formato de S-02.
 */
export default function ExpedienteVideoPage({ path, id }) {
  const { t } = useTranslation()
  const [exp, setExp] = useState(null)
  const [error, setError] = useState(null)

  const cargar = useCallback(async () => {
    try {
      setExp(await expedienteVideo(id))
      setError(null)
    } catch (e) {
      setError(e)
    }
  }, [id])

  useEffect(() => {
    cargar()
  }, [cargar])

  const decision = useDecision({
    textos: 'moderacion.video',
    id, enviar: ENVIAR, alDecidir: setExp, recargar: cargar, recargarSi: RECARGAR_SI,
  })

  const migas = `${t('admin.nav.moderacion')} / ${t('moderacion.que.videos')}${exp ? ` / ${exp.titulo}` : ''}`
  if (!exp) return <SinExpediente path={path} migas={migas} textos="moderacion.video" error={error} volverA={RUTAS.adminModeracionVideos} />

  return (
    <LayoutAdmin path={path} migas={migas} titulo={`${exp.productor.organizacion} · ${exp.titulo}`}>
      {decision.aviso && <Alerta {...decision.aviso} onCerrar={() => decision.setAviso(null)} />}
      <div className="expediente">
        <div className="expediente__principal">
          <CabeceraSolicitud titulo={exp.titulo} tono={TONO_ESTADO[exp.estado]} estado={t(`moderacion.estado.${exp.estado}`)}
            detalle={[exp.productor.organizacion, exp.productor.pueblo, exp.productor.region]}
            enviada={`${t('moderacion.expediente.enviada', { nombre: exp.subidoPor, fecha: fechaHora(exp.subidoEn) })} · ${exp.reemplaza ? t('moderacion.tipo.reemplazo') : t('moderacion.tipo.nuevo')}`} />

          <section className="tarjeta-panel" aria-labelledby="video-enviado">
            <h2 id="video-enviado" className="heading-h4">{t('moderacion.video.enviado')}</h2>
            <Reproductor video={exp} etiqueta={exp.titulo} />
          </section>

          {exp.reemplaza && (
            <section className="tarjeta-panel" aria-labelledby="video-anterior">
              <h2 id="video-anterior" className="heading-h4">{t('moderacion.video.anterior')}</h2>
              <p className="body-s">{t('moderacion.video.anteriorTexto', { titulo: exp.reemplaza.titulo })}</p>
              <Reproductor video={exp.reemplaza} etiqueta={exp.reemplaza.titulo} />
            </section>
          )}
        </div>

        <aside className="tarjeta-panel expediente__decision" aria-labelledby="video-decision">
          <h2 id="video-decision" className="heading-h3">{t('moderacion.expediente.decision')}</h2>
          <p className="caption">{t('moderacion.video.criterio')}</p>
          {exp.puedeDecidir ? (
            <>
              <CampoMotivo decision={decision} etiqueta={t('moderacion.expediente.motivo')} ayuda={t('moderacion.expediente.motivoAyuda')} />
              <Boton tamano="M" icono={iconoEscudo} anchoCompleto onClick={() => decision.pedirConfirmacion('aprobar')} disabled={decision.decidiendo}>
                {t('moderacion.video.aprobar')}
              </Boton>
              <Boton tipo="destructivo" tamano="M" icono={iconoProhibido} anchoCompleto onClick={() => decision.pedirConfirmacion('rechazar')}
                disabled={decision.decidiendo}>
                {t('moderacion.video.rechazar')}
              </Boton>
            </>
          ) : (
            <>
              <p className="body-s">{t(`moderacion.video.resuelto.${exp.estado}`, { defaultValue: t('moderacion.video.retirado') })}</p>
              {exp.motivo && <p className="body-s">«{exp.motivo}»</p>}
              {exp.decididoEn && <p className="caption">{t('moderacion.expediente.decididaPor', { nombre: exp.decididoPor, fecha: fechaHora(exp.decididoEn) })}</p>}
            </>
          )}
        </aside>
      </div>

      <DialogoDecision decision={decision} producto={exp.titulo} organizacion={exp.productor.organizacion} />
    </LayoutAdmin>
  )
}
