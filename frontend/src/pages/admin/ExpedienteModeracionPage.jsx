import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCandado from '../../assets/icons/candado.svg'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { expedienteModeracion } from '../../api/moderacion.js'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Icono from '../../components/Icono.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaCorta, fechaHora, precio, unidades } from '../../formato.js'
import { traducirFaltante } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import { BadgeAutorizacion } from './ModeracionPage.jsx'
import { FilaEvidencia, SinExpediente } from './revision.jsx'
import '../productor/productor.css'
import './admin.css'

const TONO_ESTADO = { PENDIENTE: 'info', APROBADA: 'exito', RECHAZADA: 'error' }

/**
 * CCPIP-46 (RF-21): el expediente de una oferta que el productor pide publicar. El sub administrador ve
 * la oferta como se publicaria, su autorizacion de venta con las evidencias vigentes (que no se decide
 * aqui) y las solicitudes anteriores. No hay pantalla en Figma: sigue el formato de S-02.
 */
export default function ExpedienteModeracionPage({ path, id }) {
  const { t } = useTranslation()
  const [exp, setExp] = useState(null)
  const [error, setError] = useState(null)

  const cargar = useCallback(async () => {
    try {
      setExp(await expedienteModeracion(id))
      setError(null)
    } catch (e) {
      setError(e)
    }
  }, [id])

  useEffect(() => {
    cargar()
  }, [cargar])

  const migas = `${t('admin.nav.moderacion')}${exp ? ` / ${exp.oferta.nombre}` : ''}`
  if (!exp) return <SinExpediente path={path} migas={migas} textos="moderacion" error={error} volverA={RUTAS.adminModeracion} />

  const { oferta, autorizacion } = exp
  return (
    <LayoutAdmin path={path} migas={migas} titulo={`${exp.productor.organizacion} · ${oferta.nombre}`}>
      <div className="expediente">
        <div className="expediente__principal">
          <section className="tarjeta-panel expediente__cabecera" aria-label={t('moderacion.expediente.solicitud')}>
            <div>
              <h2 className="heading-h3">{oferta.nombre}</h2>
              <p className="body-s">
                {[oferta.lineas[0], exp.productor.organizacion, exp.productor.pueblo, exp.productor.region].filter(Boolean).join(' · ')}
              </p>
              <p className="caption">
                {t('moderacion.expediente.enviada', { nombre: exp.solicitadaPor, fecha: fechaHora(exp.solicitadaEn) })}
                {' · '}
                {exp.republicacion ? t('moderacion.tipo.republicacion') : t('moderacion.tipo.nueva')}
              </p>
            </div>
            <Badge tono={TONO_ESTADO[exp.estado]}>{t(`moderacion.estado.${exp.estado}`)}</Badge>
          </section>

          <Oferta oferta={oferta} />

          <section className="tarjeta-panel" aria-labelledby="moderacion-autorizacion">
            <div className="expediente__titulo-seccion">
              <h2 id="moderacion-autorizacion" className="heading-h4">{t('moderacion.expediente.autorizacion')}</h2>
              <BadgeAutorizacion estado={autorizacion.estado} vigente={autorizacion.vigente} />
            </div>
            <p className="body-s">
              {autorizacion.vigente && autorizacion.vigenteHasta
                ? t('moderacion.expediente.autorizadaHasta', { fecha: fechaCorta(autorizacion.vigenteHasta) })
                : t(`moderacion.expediente.autorizacionTexto.${autorizacion.vigente ? 'vigente' : 'noVigente'}`)}
            </p>
            {autorizacion.id && (
              <Boton tipo="terciario" tamano="S" to={`${RUTAS.adminAutorizaciones}/${autorizacion.id}`}>{t('moderacion.expediente.verAutorizacion')}</Boton>
            )}
            {autorizacion.evidencias.length > 0 && (
              <>
                <span className="caption"><Icono src={iconoCandado} tamano={14} /> {t('autorizaciones.expediente.privadas')}</span>
                <ul className="expediente__evidencias" aria-label={t('moderacion.expediente.evidencias')}>
                  {autorizacion.evidencias.map((e) => (
                    <li key={e.id} className="expediente__evidencia">
                      <FilaEvidencia evidencia={e} detalle={[t(`evidencias.tipo.${e.tipo}`), e.emisor, e.formato]} />
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>

          {exp.historial.length > 1 && (
            <section className="tarjeta-panel" aria-labelledby="moderacion-historial">
              <h2 id="moderacion-historial" className="heading-h4">{t('moderacion.expediente.historial')}</h2>
              <ul className="expediente__historial">
                {exp.historial.map((s) => (
                  <li key={s.id}>
                    <Badge tono={TONO_ESTADO[s.estado]}>{t(`moderacion.estado.${s.estado}`)}</Badge>
                    {s.motivo && <span className="body-s">«{s.motivo}»</span>}
                    <span className="caption">
                      {s.decididaEn
                        ? t('moderacion.expediente.decididaPor', { nombre: s.decididaPor, fecha: fechaHora(s.decididaEn) })
                        : t('moderacion.expediente.enviada', { nombre: s.solicitadaPor, fecha: fechaHora(s.solicitadaEn) })}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>

        <aside className="tarjeta-panel expediente__decision" aria-labelledby="moderacion-decision">
          <h2 id="moderacion-decision" className="heading-h3">{t('moderacion.expediente.decision')}</h2>
          {exp.puedeDecidir && (
            <ul className="expediente__checklist" aria-label={t('moderacion.expediente.revision')}>
              {exp.faltan.length === 0 ? (
                <li><Icono src={iconoCheck} tamano={18} />{t('moderacion.expediente.lista')}</li>
              ) : exp.faltan.map((f) => (
                <li key={f}><Icono src={iconoReloj} tamano={18} />{traducirFaltante(f)}</li>
              ))}
            </ul>
          )}
          <p className="caption">{t('moderacion.expediente.noSustituye')}</p>
          {!exp.puedeDecidir && <p className="body-s">{t(`moderacion.expediente.resuelta.${exp.estado}`)}</p>}
        </aside>
      </div>
    </LayoutAdmin>
  )
}

/** La oferta como la veria el comprador: fotos, descripcion, presentacion, precios, stock e historia. */
function Oferta({ oferta }) {
  const { t } = useTranslation()
  const presentacion = [oferta.unidad, oferta.contenido].filter(Boolean).join(' · ')
  return (
    <section className="tarjeta-panel" aria-labelledby="moderacion-oferta">
      <h2 id="moderacion-oferta" className="heading-h4">{t('moderacion.expediente.oferta')}</h2>
      {oferta.imagenes.length > 0 && (
        <ul className="moderacion__fotos" aria-label={t('moderacion.expediente.fotos')}>
          {oferta.imagenes.map((img, i) => (
            <li key={img.id}>
              <img src={img.urlMiniatura ?? img.url} alt={t('moderacion.expediente.foto', { n: i + 1, producto: oferta.nombre })} width="96" height="96" />
            </li>
          ))}
        </ul>
      )}
      {oferta.descripcion && <p className="body-s">{oferta.descripcion}</p>}
      <dl className="moderacion__datos">
        <dt>{t('moderacion.expediente.lineas')}</dt>
        <dd>{oferta.lineas.join(', ') || '—'}</dd>
        <dt>{t('moderacion.expediente.presentacion')}</dt>
        <dd>{presentacion || '—'}</dd>
        <dt>{t('moderacion.expediente.modalidad')}</dt>
        <dd>{t(`modalidad.${oferta.modalidad}`)}</dd>
        {oferta.precio != null && (
          <>
            <dt>{t('moderacion.expediente.precio')}</dt>
            <dd>{precio(oferta.precio)}</dd>
          </>
        )}
        {oferta.precioMayorista != null && (
          <>
            <dt>{t('moderacion.expediente.precioMayorista')}</dt>
            <dd>{t('moderacion.expediente.desde', { precio: precio(oferta.precioMayorista), cantidad: unidades(oferta.cantidadMinimaMayorista ?? 0) })}</dd>
          </>
        )}
        <dt>{t('moderacion.expediente.enVenta')}</dt>
        <dd>{t('comun.und', { cantidad: unidades(oferta.enVenta) })}</dd>
      </dl>
      {oferta.historia && (
        <div className="moderacion__historia">
          <h3 className="label-m">{oferta.historiaTitulo || t('moderacion.expediente.historia')}</h3>
          <p className="body-s">{oferta.historia}</p>
        </div>
      )}
    </section>
  )
}
