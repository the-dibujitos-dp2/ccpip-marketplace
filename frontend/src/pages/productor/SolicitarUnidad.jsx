import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarSolicitudesUnidad, solicitarUnidad } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'

const TONO = { PENDIENTE: 'aviso', APROBADA: 'exito', RECHAZADA: 'error' }

/**
 * CCPIP-119 (RF-162): si ninguna unidad del catalogo le sirve, el productor la pide a la Camara. No se puede
 * usar hasta que la aprueben (CCPIP-137): mientras tanto sigue eligiendo del catalogo. Aqui ve tambien sus
 * solicitudes y lo que decidio la Camara, con el motivo si la rechazo (CCPIP-353).
 */
export default function SolicitarUnidad() {
  const { t } = useTranslation()
  const [solicitudes, setSolicitudes] = useState([])
  const [abierto, setAbierto] = useState(false)
  const [form, setForm] = useState({ nombre: '', justificacion: '' })
  const [errores, setErrores] = useState({})
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [aviso, setAviso] = useState(null)

  const cargar = () => listarSolicitudesUnidad().then(setSolicitudes).catch(() => setSolicitudes([]))
  useEffect(() => {
    cargar()
  }, [])

  function abrir() {
    setForm({ nombre: '', justificacion: '' })
    setErrores({})
    setError(null)
    setAbierto(true)
  }

  async function enviar(e) {
    e?.preventDefault()
    const faltan = {}
    if (!form.nombre.trim()) faltan.nombre = t('unidadSolicitada.errorNombre')
    if (!form.justificacion.trim()) faltan.justificacion = t('unidadSolicitada.errorJustificacion')
    setErrores(faltan)
    if (Object.keys(faltan).length) return
    setEnviando(true)
    setError(null)
    try {
      const nueva = await solicitarUnidad({ nombre: form.nombre.trim(), justificacion: form.justificacion.trim() })
      setAbierto(false)
      setAviso({ tono: 'exito', titulo: t('unidadSolicitada.enviada', { nombre: nueva.nombre }), descripcion: t('unidadSolicitada.enviadaTexto') })
      cargar()
    } catch (err) {
      setError(err)
    } finally {
      setEnviando(false)
    }
  }

  const set = (campo) => (e) => setForm({ ...form, [campo]: e.target.value })

  return (
    <div className="solicitar-unidad">
      <Boton tipo="terciario" tamano="S" onClick={abrir}>{t('unidadSolicitada.abrir')}</Boton>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      {solicitudes.length > 0 && (
        <ul className="solicitar-unidad__lista" aria-label={t('unidadSolicitada.tuyas')}>
          {solicitudes.map((s) => (
            <li key={s.id} className="caption">
              <Badge tono={TONO[s.estado]}>{t(`unidadSolicitada.estado.${s.estado}`)}</Badge>{' '}
              <strong>{s.estado === 'APROBADA' && s.unidad ? s.unidad.nombre : s.nombre}</strong>
              {s.estado === 'APROBADA' && ` · ${t('unidadSolicitada.yaPuedes')}`}
              {s.estado === 'RECHAZADA' && ` · ${t('unidadSolicitada.motivo', { motivo: s.motivo })}`}
            </li>
          ))}
        </ul>
      )}
      {abierto && (
        <DialogoFormulario
          titulo={t('unidadSolicitada.titulo')}
          confirmar={t('unidadSolicitada.enviar')}
          enviando={enviando}
          onEnviar={enviar}
          onCancelar={() => setAbierto(false)}
          className="solicitar-unidad__form"
          error={error}
          tituloError={t('unidadSolicitada.error')}
        >
          <p className="body-s">{t('unidadSolicitada.texto')}</p>
          <Campo etiqueta={t('unidadSolicitada.nombre')} value={form.nombre} onChange={set('nombre')} error={errores.nombre}
            maxLength={60} placeholder={t('unidadSolicitada.nombrePlaceholder')} />
          <Campo etiqueta={t('unidadSolicitada.justificacion')} tipo="area" value={form.justificacion} onChange={set('justificacion')}
            error={errores.justificacion} maxLength={500} ayuda={t('unidadSolicitada.justificacionAyuda')} />
        </DialogoFormulario>
      )}
    </div>
  )
}
