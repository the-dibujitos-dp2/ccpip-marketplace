import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import { decidirProductor } from '../../api/autorizaciones.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import { mensajeError } from '../../i18n/errores.js'

/**
 * CCPIP-85 y CCPIP-322 (Figma S-03 y S-04): desde las denuncias, el sub administrador bloquea o
 * desbloquea las ventas del productor. Motivo obligatorio y consecuencias explicadas antes de confirmar.
 * Devuelve por {@code onDecidido} la ficha del productor que responde el servidor.
 */
export default function BloqueoVenta({ productorId, organizacion, bloqueada, onDecidido }) {
  const { t } = useTranslation()
  const [abierto, setAbierto] = useState(false)
  const [motivo, setMotivo] = useState('')
  const [error, setError] = useState(null)
  const [errorServidor, setErrorServidor] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [hecho, setHecho] = useState(null) // 'bloquear' | 'desbloquear': se traduce al mostrar
  const accion = bloqueada ? 'desbloquear' : 'bloquear'

  function abrir() {
    setMotivo('')
    setError(null)
    setErrorServidor(null)
    setHecho(null)
    setAbierto(true)
  }

  async function confirmar() {
    if (!motivo.trim()) {
      setError(t('autorizaciones.expediente.motivoRequerido'))
      return
    }
    setError(null)
    setOcupado(true)
    try {
      const ficha = await decidirProductor(productorId, accion, motivo.trim())
      setAbierto(false)
      setHecho(accion)
      onDecidido(ficha)
    } catch (e) {
      setErrorServidor(mensajeError(e, 'autorizaciones.expediente.errorDecision'))
    } finally {
      setOcupado(false)
    }
  }

  return (
    <section className="tarjeta-panel" aria-labelledby="bloqueo-venta">
      <h2 id="bloqueo-venta" className="heading-h4">{t('denunciasAdmin.bloqueo')}</h2>
      {hecho && <Alerta tono="exito" titulo={t(`productores.accion.${hecho}.hecho`)} onCerrar={() => setHecho(null)} />}
      <p className="body-s">{t(bloqueada ? 'denunciasAdmin.bloqueadoTexto' : 'denunciasAdmin.bloqueoTexto')}</p>
      <Boton tipo={bloqueada ? 'secundario' : 'destructivo'} tamano="M" icono={bloqueada ? iconoCheck : iconoProhibido} onClick={abrir}>
        {t(`productores.accion.${accion}.boton`)}
      </Boton>
      <Dialogo
        abierto={abierto}
        titulo={t(`productores.accion.${accion}.titulo`, { organizacion })}
        confirmar={t(`productores.accion.${accion}.confirmar`)}
        tipoConfirmar={bloqueada ? 'primario' : 'destructivo'}
        ocupado={ocupado}
        onCancelar={() => setAbierto(false)}
        onConfirmar={confirmar}
      >
        <p>{t(`productores.accion.${accion}.texto`)}</p>
        {errorServidor && <Alerta tono="error" titulo={errorServidor} />}
        <Campo
          tipo="area"
          etiqueta={t('autorizaciones.expediente.motivo')}
          ayuda={t('autorizaciones.expediente.motivoAyuda')}
          error={error}
          value={motivo}
          maxLength={500}
          onChange={(e) => setMotivo(e.target.value)}
        />
      </Dialogo>
    </section>
  )
}
