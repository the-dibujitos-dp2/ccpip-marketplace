import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import { evidenciasFisicas, registrarEnvioFisica, registrarRecepcionFisica } from '../../api/autorizaciones.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import { fechaCorta, fechaHora } from '../../formato.js'
import { erroresPorCampo, mensajeError } from '../../i18n/errores.js'

const TONO_RESULTADO = { CONFORME: 'exito', OBSERVADA: 'aviso', NO_CONFORME: 'error' }
const RESULTADOS = ['CONFORME', 'OBSERVADA', 'NO_CONFORME']
// Errores de una fecha o de las observaciones: se marcan en su campo
const CAMPO_DEL_ERROR = { FECHA_FUTURA: 'fecha', RECEPCION_ANTES_DEL_ENVIO: 'fecha', OBSERVACIONES_REQUERIDAS: 'observaciones' }

const hoy = () => new Date().toLocaleDateString('en-CA', { timeZone: 'America/Lima' })
const VACIO = { descripcion: '', referencia: '', fecha: '', resultado: '', observaciones: '' }

/**
 * CCPIP-35 (RF-12) y CCPIP-329 (RF-219), en el expediente S-02: el subadministrador registra el envio de una
 * evidencia fisica cuando la evaluacion la requiera y despues su recepcion con el resultado. Es opcional:
 * aprobar no la exige y el resultado no decide la solicitud. Lo registrado no se edita.
 */
export default function EvidenciasFisicas({ autorizacionId, pendiente }) {
  const { t } = useTranslation()
  const f = (k, o) => t(`autorizaciones.fisica.${k}`, o)
  const consultar = useCallback(() => evidenciasFisicas(autorizacionId), [autorizacionId])
  const { datos, setDatos } = useConsulta(consultar)
  // dialogo: { tipo: 'envio' } o { tipo: 'recepcion', fisica }
  const [dialogo, setDialogo] = useState(null)
  const [form, setForm] = useState(VACIO)
  const [errores, setErrores] = useState({})
  const [aviso, setAviso] = useState(null)
  const [ocupado, setOcupado] = useState(false)

  const fisicas = datos ?? []
  if (!pendiente && fisicas.length === 0) return null

  const cambiar = (campo) => (e) => setForm((actual) => ({ ...actual, [campo]: e.target.value }))

  function abrir(nuevo) {
    setDialogo(nuevo)
    setForm({ ...VACIO, fecha: hoy() })
    setErrores({})
  }

  function validar() {
    const faltan = {}
    if (!form.fecha) faltan.fecha = f('errorFecha')
    if (dialogo.tipo === 'envio' && !form.descripcion.trim()) faltan.descripcion = f('errorDescripcion')
    if (dialogo.tipo === 'recepcion') {
      if (!form.resultado) faltan.resultado = f('errorResultado')
      else if (form.resultado !== 'CONFORME' && !form.observaciones.trim()) faltan.observaciones = f('errorObservaciones')
    }
    setErrores(faltan)
    return Object.keys(faltan).length === 0
  }

  async function enviar() {
    if (!validar()) return
    setOcupado(true)
    try {
      const guardada = dialogo.tipo === 'envio'
        ? await registrarEnvioFisica(autorizacionId, {
          descripcion: form.descripcion.trim(), referencia: form.referencia.trim(), enviadaEl: form.fecha,
          observaciones: form.observaciones.trim(),
        })
        : await registrarRecepcionFisica(autorizacionId, dialogo.fisica.id, {
          recibidaEl: form.fecha, resultado: form.resultado, observaciones: form.observaciones.trim(),
        })
      setDatos(dialogo.tipo === 'envio' ? [guardada, ...fisicas] : fisicas.map((x) => (x.id === guardada.id ? guardada : x)))
      setAviso({ tono: 'exito', titulo: f(dialogo.tipo === 'envio' ? 'envioRegistrado' : 'recepcionRegistrada') })
      setDialogo(null)
    } catch (e) {
      const campo = CAMPO_DEL_ERROR[e.code]
      const campos = e.body?.campos ? erroresPorCampo(e) : {}
      if (campo) setErrores({ [campo]: mensajeError(e, 'autorizaciones.fisica.error') })
      else if (Object.keys(campos).length > 0) setErrores({ ...campos, fecha: campos.enviadaEl ?? campos.recibidaEl })
      else {
        setAviso({ tono: 'error', titulo: mensajeError(e, 'autorizaciones.fisica.error') })
        setDialogo(null)
      }
    } finally {
      setOcupado(false)
    }
  }

  const titulo = dialogo?.tipo === 'envio' ? f('dialogoEnvio') : dialogo ? f('dialogoRecepcion', { descripcion: dialogo.fisica.descripcion }) : ''

  return (
    <section className="tarjeta-panel" aria-labelledby="expediente-fisicas">
      <div className="expediente__titulo-seccion">
        <h2 id="expediente-fisicas" className="heading-h4">{f('titulo')}</h2>
        {pendiente && (
          <Boton tipo="secundario" tamano="S" icono={iconoCaja} onClick={() => abrir({ tipo: 'envio' })}>
            {f('registrarEnvio')}
          </Boton>
        )}
      </div>
      <p className="body-s">{f('texto')}</p>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}

      {fisicas.length > 0 && (
        <ul className="expediente__evidencias" aria-label={f('lista')}>
          {fisicas.map((x) => (
            <li key={x.id} className="expediente__evidencia">
              <div className="expediente__evidencia-fila">
                <span className="expediente__evidencia-datos">
                  <span className="label-m">{x.descripcion}</span>
                  <span className="caption">
                    {[f('enviadaEl', { fecha: fechaCorta(x.enviadaEl) }), x.referencia].filter(Boolean).join(' · ')}
                  </span>
                  {x.observaciones && <span className="body-s">«{x.observaciones}»</span>}
                  <span className="caption">{f('registradaPor', { nombre: x.registradaPor, fecha: fechaHora(x.registradaEn) })}</span>
                </span>
                {x.recepcion ? (
                  <Badge tono={TONO_RESULTADO[x.recepcion.resultado]}>{f(`resultado.${x.recepcion.resultado}`)}</Badge>
                ) : (
                  <Boton tipo="secundario" tamano="S" icono={iconoCheck} onClick={() => abrir({ tipo: 'recepcion', fisica: x })}>
                    {f('registrarRecepcion')}
                  </Boton>
                )}
              </div>
              {x.recepcion && (
                <div className="expediente__sugerencia">
                  <div>
                    <p className="caption">{f('recibidaEl', { fecha: fechaCorta(x.recepcion.recibidaEl) })}</p>
                    {x.recepcion.observaciones && <p className="body-s">«{x.recepcion.observaciones}»</p>}
                    <p className="caption">{f('registradaPor', { nombre: x.recepcion.registradaPor, fecha: fechaHora(x.recepcion.registradaEn) })}</p>
                  </div>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      <Dialogo
        abierto={dialogo !== null}
        titulo={titulo}
        confirmar={dialogo?.tipo === 'envio' ? f('registrarEnvio') : f('registrarRecepcion')}
        ocupado={ocupado}
        onCancelar={() => setDialogo(null)}
        onConfirmar={enviar}
      >
        <p>{dialogo?.tipo === 'envio' ? f('dialogoEnvioTexto') : f('dialogoRecepcionTexto')}</p>
        {dialogo?.tipo === 'envio' && (
          <>
            <Campo etiqueta={f('descripcion')} ayuda={f('descripcionAyuda')} value={form.descripcion} maxLength={300}
              error={errores.descripcion} onChange={cambiar('descripcion')} />
            <Campo etiqueta={f('referencia')} value={form.referencia} maxLength={120}
              error={errores.referencia} onChange={cambiar('referencia')} />
          </>
        )}
        <Campo type="date" etiqueta={dialogo?.tipo === 'envio' ? f('fechaEnvio') : f('fechaRecepcion')} value={form.fecha}
          max={hoy()} error={errores.fecha} onChange={cambiar('fecha')} />
        {dialogo?.tipo === 'recepcion' && (
          <Campo tipo="selector" etiqueta={f('resultadoEtiqueta')} value={form.resultado} error={errores.resultado}
            opciones={RESULTADOS.map((r) => ({ valor: r, etiqueta: f(`resultado.${r}`) }))} onChange={cambiar('resultado')} />
        )}
        <Campo tipo="area" etiqueta={f('observaciones')} ayuda={dialogo?.tipo === 'recepcion' ? f('observacionesAyuda') : undefined}
          value={form.observaciones} maxLength={500} error={errores.observaciones} onChange={cambiar('observaciones')} />
      </Dialogo>
    </section>
  )
}
