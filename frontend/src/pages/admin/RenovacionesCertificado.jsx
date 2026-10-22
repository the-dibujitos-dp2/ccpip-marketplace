import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoReloj from '../../assets/icons/reloj.svg'
import { renovacionesAutorizacion, solicitarRenovacion } from '../../api/autorizaciones.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import { fechaCorta, fechaHora } from '../../formato.js'
import { erroresPorCampo, mensajeError } from '../../i18n/errores.js'

const TONO = { PENDIENTE: 'aviso', ATENDIDA: 'exito', CANCELADA: 'neutro' }

/**
 * CCPIP-321 (RF-205), en el expediente S-02: el subadministrador pide renovar un certificado de una
 * autorizacion aprobada y ve las solicitudes. Pedirla avisa al productor y no cambia la venta: si el
 * certificado vence sin renovarse, la oferta se pausa sola (C5), sin revocarla.
 */
export default function RenovacionesCertificado({ autorizacionId, certificados, aprobada }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => renovacionesAutorizacion(autorizacionId), [autorizacionId])
  const { datos, setDatos } = useConsulta(consultar)
  const [elegido, setElegido] = useState(null)
  const [motivo, setMotivo] = useState('')
  const [plazo, setPlazo] = useState('')
  const [errores, setErrores] = useState({})
  const [aviso, setAviso] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  const renovaciones = datos ?? []
  const pendiente = (evidenciaId) => renovaciones.some((r) => r.certificado.evidenciaId === evidenciaId && r.estado === 'PENDIENTE')
  if (!aprobada && renovaciones.length === 0) return null

  function abrir(certificado) {
    setElegido(certificado)
    setMotivo('')
    setPlazo('')
    setErrores({})
  }

  async function enviar() {
    if (!motivo.trim()) {
      setErrores({ motivo: t('autorizaciones.renovacion.errorMotivo') })
      return
    }
    setOcupado(true)
    try {
      const nueva = await solicitarRenovacion(autorizacionId, { evidenciaId: elegido.id, motivo: motivo.trim(), plazo })
      setDatos([nueva, ...renovaciones])
      setAviso({ tono: 'exito', titulo: t('autorizaciones.renovacion.registrada'), descripcion: t('autorizaciones.renovacion.registradaTexto') })
      setElegido(null)
    } catch (e) {
      const campos = e.body?.campos ? erroresPorCampo(e) : {}
      if (e.code === 'PLAZO_PASADO') setErrores({ plazo: mensajeError(e, 'autorizaciones.renovacion.error') })
      else if (Object.keys(campos).length > 0) setErrores(campos)
      else {
        setAviso({ tono: 'error', titulo: mensajeError(e, 'autorizaciones.renovacion.error') })
        setElegido(null)
      }
    } finally {
      setOcupado(false)
    }
  }

  return (
    <section className="tarjeta-panel" aria-labelledby="expediente-renovaciones">
      <h2 id="expediente-renovaciones" className="heading-h4">{t('autorizaciones.renovacion.titulo')}</h2>
      <p className="body-s">{t('autorizaciones.renovacion.texto')}</p>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}

      {aprobada && certificados.length > 0 && (
        <ul className="expediente__evidencias" aria-label={t('autorizaciones.renovacion.certificados')}>
          {certificados.map((c) => (
            <li key={c.id} className="expediente__evidencia">
              <div className="expediente__evidencia-fila">
                <span className="expediente__evidencia-datos">
                  <span className="label-m">{c.titulo}</span>
                  <span className="caption">{c.vigenteHasta && t('autorizaciones.renovacion.vence', { fecha: fechaCorta(c.vigenteHasta) })}</span>
                </span>
                {pendiente(c.id) ? (
                  <Badge tono="aviso">{t('autorizaciones.renovacion.estado.PENDIENTE')}</Badge>
                ) : (
                  <Boton tipo="secundario" tamano="S" icono={iconoReloj} onClick={() => abrir(c)}>
                    {t('autorizaciones.renovacion.solicitar')}
                  </Boton>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {renovaciones.length > 0 && (
        <ul className="expediente__historial" aria-label={t('autorizaciones.renovacion.solicitudes')}>
          {renovaciones.map((r) => (
            <li key={r.id}>
              <Badge tono={TONO[r.estado]}>{t(`autorizaciones.renovacion.estado.${r.estado}`)}</Badge>
              <span className="body-s">{t('autorizaciones.renovacion.de', { certificado: r.certificado.titulo, plazo: fechaCorta(r.plazo) })}</span>
              <span className="body-s">«{r.motivo}»</span>
              <span className="caption">
                {t('autorizaciones.renovacion.pedidaPor', { nombre: r.solicitadaPor, fecha: fechaHora(r.solicitadaEn) })}
                {r.ventaPausadaDesde && ` · ${t('autorizaciones.renovacion.efecto', { fecha: fechaCorta(r.ventaPausadaDesde) })}`}
              </span>
            </li>
          ))}
        </ul>
      )}

      <Dialogo
        abierto={elegido !== null}
        titulo={elegido ? t('autorizaciones.renovacion.dialogo', { certificado: elegido.titulo }) : ''}
        confirmar={t('autorizaciones.renovacion.confirmar')}
        ocupado={ocupado}
        onCancelar={() => setElegido(null)}
        onConfirmar={enviar}
      >
        <p>{t('autorizaciones.renovacion.dialogoTexto')}</p>
        <Campo tipo="area" etiqueta={t('autorizaciones.renovacion.motivo')} value={motivo} maxLength={500}
          error={errores.motivo} onChange={(e) => setMotivo(e.target.value)} />
        <Campo type="date" etiqueta={t('autorizaciones.renovacion.plazo')} ayuda={t('autorizaciones.renovacion.plazoAyuda')}
          value={plazo} error={errores.plazo} onChange={(e) => setPlazo(e.target.value)} />
      </Dialogo>
    </section>
  )
}
