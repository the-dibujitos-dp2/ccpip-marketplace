import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCandado from '../../assets/icons/candado.svg'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import iconoIa from '../../assets/icons/ia.svg'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import {
  analizarEvidencias,
  aprobarAutorizacion,
  expedienteAutorizacion,
  rechazarAutorizacion,
  revocarAutorizacion,
} from '../../api/autorizaciones.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Icono from '../../components/Icono.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaCorta, fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import { BadgeAnalisis } from './AutorizacionesPage.jsx'
import RenovacionesCertificado from './RenovacionesCertificado.jsx'
import { CampoMotivo, DialogoDecision, FilaEvidencia, SinExpediente, useDecision } from './revision.jsx'
import '../productor/productor.css'
import './admin.css'

const TONO_ESTADO = { BORRADOR: 'neutro', SOLICITADA: 'info', APROBADA: 'exito', RECHAZADA: 'error', REVOCADA: 'error' }
const TONO_HALLAZGO = { CONFORME: 'exito', INCONSISTENCIA: 'aviso', FALTANTE: 'error' }
const ENVIAR = { aprobar: aprobarAutorizacion, rechazar: rechazarAutorizacion, revocar: revocarAutorizacion }
const RECARGAR_SI = ['SOLICITUD_YA_RESUELTA', 'AUTORIZACION_NO_APROBADA']

/**
 * S-02 adaptada: expediente de la solicitud de autorizacion de un producto. El sub administrador revisa
 * las evidencias, puede pedir el analisis preliminar (CCPIP-50) y decide con motivo: aprobar (CCPIP-174)
 * o rechazar (CCPIP-284). La decision humana es la unica que autoriza; sin analisis se decide a mano.
 * Una autorizacion aprobada se puede revocar con motivo (CCPIP-39): deja de venderse al instante.
 */
export default function ExpedienteAutorizacionPage({ path, id }) {
  const { t } = useTranslation()
  const [exp, setExp] = useState(null)
  const [error, setError] = useState(null)
  const [analizando, setAnalizando] = useState(false)
  const [sinServicio, setSinServicio] = useState(false)

  const cargar = useCallback(async () => {
    try {
      setExp(await expedienteAutorizacion(id))
      setError(null)
    } catch (e) {
      setError(e)
    }
  }, [id])

  useEffect(() => {
    cargar()
  }, [cargar])

  const decision = useDecision({
    textos: 'autorizaciones',
    id, enviar: ENVIAR, alDecidir: setExp, recargar: cargar, recargarSi: RECARGAR_SI,
  })
  const { aviso, setAviso, decidiendo, pedirConfirmacion } = decision

  async function analizar() {
    setAnalizando(true)
    setAviso(null)
    try {
      const analisis = await analizarEvidencias(id)
      setSinServicio(false)
      setExp((actual) => ({ ...actual, analisis }))
    } catch (e) {
      if (e.code === 'IA_NO_DISPONIBLE') {
        // CCPIP-50: sin servicio, la solicitud pasa a revision manual y se puede decidir igual
        setSinServicio(true)
        await cargar()
      } else {
        setAviso({ tono: 'error', titulo: mensajeError(e, 'autorizaciones.expediente.errorAnalisis') })
      }
    } finally {
      setAnalizando(false)
    }
  }

  const migas = `${t('admin.nav.autorizaciones')}${exp ? ` / ${exp.producto}` : ''}`
  if (!exp) return <SinExpediente path={path} migas={migas} textos="autorizaciones" error={error} volverA={RUTAS.adminAutorizaciones} />

  const analisis = exp.analisis
  const completado = analisis?.estado === 'COMPLETADO'
  const manual = sinServicio || analisis?.estado === 'NO_DISPONIBLE'
  const hallazgosDe = (evidenciaId) => (completado ? analisis.hallazgos.filter((h) => h.evidenciaId === evidenciaId) : [])
  const faltantes = completado ? analisis.hallazgos.filter((h) => !h.evidenciaId) : []

  return (
    <LayoutAdmin path={path} migas={migas} titulo={`${exp.productor.organizacion} · ${exp.producto}`}>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      <div className="expediente">
        <div className="expediente__principal">
          <section className="tarjeta-panel expediente__cabecera" aria-label={t('autorizaciones.expediente.solicitud')}>
            <div>
              <h2 className="heading-h3">{exp.producto}</h2>
              <p className="body-s">
                {[exp.linea, exp.productor.organizacion, exp.productor.pueblo, exp.productor.region].filter(Boolean).join(' · ')}
              </p>
              {exp.solicitadaEn && <p className="caption">{t('autorizaciones.expediente.enviada', { fecha: fechaHora(exp.solicitadaEn) })}</p>}
            </div>
            <Badge tono={TONO_ESTADO[exp.estado]}>{t(`autorizaciones.estado.${exp.estado}`)}</Badge>
          </section>

          <section className="tarjeta-panel" aria-labelledby="expediente-evidencias">
            <div className="expediente__titulo-seccion">
              <h2 id="expediente-evidencias" className="heading-h4">{t('autorizaciones.expediente.evidencias', { count: exp.evidencias.length })}</h2>
              <span className="caption"><Icono src={iconoCandado} tamano={14} /> {t('autorizaciones.expediente.privadas')}</span>
            </div>
            {exp.evidencias.length === 0 && <p className="body-s">{t('autorizaciones.expediente.sinEvidencias')}</p>}
            <ul className="expediente__evidencias">
              {exp.evidencias.map((e) => (
                <li key={e.id} className="expediente__evidencia">
                  <FilaEvidencia evidencia={e} detalle={[t(`evidencias.tipo.${e.tipo}`), e.emisor,
                    e.vigenteDesde && t('autorizaciones.expediente.desde', { fecha: fechaCorta(e.vigenteDesde) }), e.formato]} />
                  {hallazgosDe(e.id).map((h, i) => (
                    <div key={i} className="expediente__sugerencia">
                      <Icono src={iconoIa} tamano={16} />
                      <div>
                        <p className="caption">
                          {analisis.simulado ? t('autorizaciones.analisis.sugerenciaPrueba') : t('autorizaciones.analisis.sugerencia')}
                          {' · '}
                          <Badge tono={TONO_HALLAZGO[h.tipo]}>{t(`autorizaciones.hallazgo.${h.tipo}`)}</Badge>
                        </p>
                        <p className="body-s">{h.detalle}</p>
                      </div>
                    </div>
                  ))}
                </li>
              ))}
            </ul>
          </section>

          <RenovacionesCertificado
            autorizacionId={exp.id}
            aprobada={exp.estado === 'APROBADA'}
            certificados={exp.evidencias.filter((e) => e.tipo === 'CERTIFICADO')}
          />

          {exp.decisiones.length > 0 && (
            <section className="tarjeta-panel" aria-labelledby="expediente-historial">
              <h2 id="expediente-historial" className="heading-h4">{t('autorizaciones.expediente.historial')}</h2>
              <ul className="expediente__historial">
                {exp.decisiones.map((d) => (
                  <li key={d.id}>
                    <Badge tono={TONO_ESTADO[d.decision]}>{t(`autorizaciones.estado.${d.decision}`)}</Badge>
                    <span className="body-s">«{d.motivo}»</span>
                    <span className="caption">
                      {t('autorizaciones.expediente.decididaPor', { nombre: d.decididoPor, fecha: fechaHora(d.decididoEn) })}
                      {' · '}
                      {d.conAnalisis ? t('autorizaciones.expediente.conAnalisis') : t('autorizaciones.expediente.manual')}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>

        <aside className="tarjeta-panel expediente__decision" aria-labelledby="expediente-decision">
          <h2 id="expediente-decision" className="heading-h3">{t('autorizaciones.expediente.decision')}</h2>
          <ul className="expediente__checklist">
            <li>
              <Icono src={exp.certificadosVigentes > 0 ? iconoCheck : iconoReloj} tamano={18} />
              {t('autorizaciones.expediente.certificadosVigentes', { count: exp.certificadosVigentes })}
            </li>
            <li>
              <Icono src={completado ? iconoCheck : iconoReloj} tamano={18} />
              {completado ? t('autorizaciones.expediente.analisisListo') : manual ? t('autorizaciones.expediente.revisionManual') : t('autorizaciones.expediente.sinAnalisis')}
            </li>
          </ul>

          <div className="expediente__ia" role="note">
            <Icono src={iconoIa} tamano={16} />
            <p className="caption">{t('autorizaciones.analisis.noDecide')}</p>
          </div>

          {completado && (
            <section className="expediente__analisis" aria-label={t('autorizaciones.analisis.titulo')}>
              {analisis.simulado && <Alerta tono="aviso" titulo={t('autorizaciones.analisis.simuladoTitulo')} descripcion={t('autorizaciones.analisis.simuladoTexto')} />}
              <p className="label-m">{t('autorizaciones.analisis.recomendacion')} <BadgeAnalisis recomendacion={analisis.recomendacion} /></p>
              <p className="body-s">{analisis.resumen}</p>
              {faltantes.length > 0 && (
                <ul className="expediente__faltantes" aria-label={t('autorizaciones.analisis.faltantes')}>
                  {faltantes.map((h, i) => <li key={i} className="body-s">{h.detalle}</li>)}
                </ul>
              )}
              <p className="caption">{t('autorizaciones.analisis.pedido', { fecha: fechaHora(analisis.solicitadoEn), proveedor: analisis.proveedor })}</p>
              {analisis.desactualizado && <Alerta tono="info" titulo={t('autorizaciones.analisis.desactualizado')} />}
            </section>
          )}
          {manual && (
            <Alerta tono="info" titulo={t('autorizaciones.analisis.noDisponible')} descripcion={t('autorizaciones.analisis.noDisponibleTexto')} />
          )}

          {exp.puedeDecidir ? (
            <>
              <Boton tipo="secundario" tamano="M" icono={iconoIa} anchoCompleto onClick={analizar} cargando={analizando}>
                {analizando ? t('autorizaciones.analisis.analizando') : completado ? t('autorizaciones.analisis.otraVez') : t('autorizaciones.analisis.pedir')}
              </Boton>
              <CampoMotivo decision={decision} etiqueta={t('autorizaciones.expediente.motivo')} ayuda={t('autorizaciones.expediente.motivoAyuda')} />
              {exp.certificadosVigentes === 0 && <p className="caption">{t('autorizaciones.expediente.sinCertificado')}</p>}
              <Boton tamano="M" icono={iconoEscudo} anchoCompleto onClick={() => pedirConfirmacion('aprobar')} disabled={exp.certificadosVigentes === 0 || decidiendo}>
                {t('autorizaciones.expediente.aprobar')}
              </Boton>
              <Boton tipo="destructivo" tamano="M" icono={iconoProhibido} anchoCompleto onClick={() => pedirConfirmacion('rechazar')} disabled={decidiendo}>
                {t('autorizaciones.expediente.rechazar')}
              </Boton>
            </>
          ) : (
            <p className="body-s">
              {exp.estado === 'APROBADA' && exp.vigenteHasta
                ? t('autorizaciones.expediente.aprobadaHasta', { fecha: fechaCorta(exp.vigenteHasta) })
                : t('autorizaciones.expediente.resuelta')}
            </p>
          )}
          {exp.puedeRevocar && (
            <>
              <CampoMotivo decision={decision} etiqueta={t('autorizaciones.expediente.motivoRevocacion')} ayuda={t('autorizaciones.expediente.revocarAyuda')} />
              <Boton tipo="destructivo" tamano="M" icono={iconoProhibido} anchoCompleto onClick={() => pedirConfirmacion('revocar')} disabled={decidiendo}>
                {t('autorizaciones.expediente.revocar')}
              </Boton>
            </>
          )}
        </aside>
      </div>

      <DialogoDecision decision={decision} producto={exp.producto} organizacion={exp.productor.organizacion} />
    </LayoutAdmin>
  )
}
