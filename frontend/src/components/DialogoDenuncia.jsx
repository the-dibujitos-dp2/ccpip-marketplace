import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { MOTIVOS_DENUNCIA, presentarDenuncia } from '../api/valoracion.js'
import { mensajeError } from '../i18n/errores.js'
import Alerta from './Alerta.jsx'
import Campo from './Campo.jsx'
import Dialogo from './Dialogo.jsx'

const MINIMO = 10

/**
 * CCPIP-83 (RF-90): el comprador denuncia una oferta ({ productoId }) o una comunidad ({ productorId })
 * con motivo y descripcion. Se registra sin aprobacion previa; el denunciado no ve quien la presento.
 */
export default function DialogoDenuncia({ abierto, objetivo, onCerrar, onEnviada }) {
  const { t } = useTranslation()
  const [motivo, setMotivo] = useState('')
  const [descripcion, setDescripcion] = useState('')
  const [errores, setErrores] = useState({})
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)

  function cerrar() {
    setMotivo('')
    setDescripcion('')
    setErrores({})
    setError(null)
    onCerrar()
  }

  async function enviar() {
    const faltan = {}
    if (!motivo) faltan.motivo = t('denuncia.errores.motivo')
    if (descripcion.trim().length < MINIMO) faltan.descripcion = t('denuncia.errores.descripcion', { minimo: MINIMO })
    setErrores(faltan)
    if (Object.keys(faltan).length > 0) return
    setEnviando(true)
    setError(null)
    try {
      const denuncia = await presentarDenuncia({ ...objetivo.ids, motivo, descripcion })
      setMotivo('')
      setDescripcion('')
      onEnviada?.(denuncia)
    } catch (e) {
      setError(e)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <Dialogo abierto={abierto} titulo={t('denuncia.titulo')} confirmar={t('denuncia.enviar')} onConfirmar={enviar}
      onCancelar={cerrar} ocupado={enviando}>
      <p>{t('denuncia.sobre', { nombre: objetivo?.nombre ?? '' })}</p>
      {error && <Alerta tono="error" titulo={mensajeError(error, 'denuncia.errores.envio')} />}
      <Campo tipo="selector" etiqueta={t('denuncia.motivo')} value={motivo} error={errores.motivo}
        onChange={(e) => setMotivo(e.target.value)}
        opciones={MOTIVOS_DENUNCIA.map((m) => ({ valor: m, etiqueta: t(`denuncia.motivos.${m}`) }))} />
      <Campo tipo="area" etiqueta={t('denuncia.descripcion')} value={descripcion} maxLength={1000} error={errores.descripcion}
        ayuda={t('denuncia.descripcionAyuda')} onChange={(e) => setDescripcion(e.target.value)} />
      <p className="caption">{t('denuncia.privacidad')}</p>
    </Dialogo>
  )
}
