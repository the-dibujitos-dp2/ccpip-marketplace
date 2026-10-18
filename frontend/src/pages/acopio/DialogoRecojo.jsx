import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { registrarRecojo } from '../../api/acopio.js'
import Campo from '../../components/Campo.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'
import { erroresPorCampo, mensajeError } from '../../i18n/errores.js'
import './DialogoRecojo.css'

/** Que campo corrige cada rechazo del servidor. Ninguno dice cual era el dato correcto (RNF-05). */
const CAMPO_POR_CODIGO = {
  PERSONA_NO_COINCIDE: 'persona',
  CELULAR_INVALIDO: 'celular',
  CELULAR_NO_COINCIDE: 'celular',
}

/**
 * CCPIP-71 (RF-53): el administrador verifica en el mostrador quien recoge el pedido y su celular. El
 * servidor decide si coinciden con el comprador o la persona autorizada, asi que aqui solo se pide
 * y se muestra cada rechazo junto al campo que hay que corregir. Se monta una vez por pedido
 * (`key`), con los campos vacios.
 *
 * `onRegistrado(pedido)` recibe el pedido ya entregado. `onDesactualizado(error)` avisa que el pedido
 * cambio mientras tanto (409) o ya no esta en el punto (404): la pantalla lo explica y se actualiza.
 */
export default function DialogoRecojo({ pedido, onCancelar, onRegistrado, onDesactualizado }) {
  const { t } = useTranslation()
  const [persona, setPersona] = useState('')
  const [celular, setCelular] = useState('')
  const [errores, setErrores] = useState({})
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(e) {
    e?.preventDefault()
    const faltan = {}
    if (!persona.trim()) faltan.persona = t('acopio.recojo.validacion.persona')
    if (!celular.trim()) faltan.celular = t('acopio.recojo.validacion.celular')
    setErrores(faltan)
    setError(null)
    if (Object.keys(faltan).length > 0) return

    setEnviando(true)
    try {
      onRegistrado(await registrarRecojo(pedido.numero, { persona: persona.trim(), celular: celular.trim() }))
    } catch (err) {
      // Un 401 ya lo maneja la app (vuelve a ingresar)
      if (err.status === 401) return
      if (err.status === 404 || err.status === 409) onDesactualizado(err)
      else if (err.body?.campos) setErrores(erroresPorCampo(err))
      else if (CAMPO_POR_CODIGO[err.code]) setErrores({ [CAMPO_POR_CODIGO[err.code]]: mensajeError(err) })
      else setError(err)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <DialogoFormulario
      titulo={t('acopio.recojo.titulo', { numero: pedido.numero })}
      confirmar={t('acopio.recojo.registrar')}
      enviando={enviando}
      onEnviar={enviar}
      onCancelar={onCancelar}
      className="recojo__formulario"
      error={error}
      tituloError={t('acopio.recojo.error')}
    >
      <p>{t('acopio.recojo.instrucciones')}</p>
      <Campo
        etiqueta={t('acopio.recojo.nombre')}
        value={persona}
        onChange={(e) => setPersona(e.target.value)}
        error={errores.persona}
        maxLength={160}
        autoComplete="off"
      />
      <Campo
        etiqueta={t('compra.celular')}
        ayuda={t('acopio.recojo.celularAyuda')}
        value={celular}
        onChange={(e) => setCelular(e.target.value)}
        error={errores.celular}
        maxLength={20}
        inputMode="tel"
        autoComplete="off"
      />
    </DialogoFormulario>
  )
}
