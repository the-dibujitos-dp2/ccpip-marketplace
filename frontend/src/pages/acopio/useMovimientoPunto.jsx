import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarLotesPunto, registrarMovimiento } from '../../api/acopio.js'
import Alerta from '../../components/Alerta.jsx'
import DialogoMovimiento, { AvisoRegistrado } from '../../components/DialogoMovimiento.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { MOTIVOS_PUNTO } from '../../movimientos.js'

/**
 * CCPIP-121 y CCPIP-287: "Nuevo movimiento" en las pantallas del punto (A-02 y A-03). Devuelve
 * `abrir(loteInicial?)`, los avisos (registrado o error al pedir los lotes) y el dialogo para
 * montar; al registrar, la pantalla se actualiza con `recargar`. CCPIP-421: el motivo va primero.
 */
export function useMovimientoPunto(recargar) {
  const { t } = useTranslation()
  const [dialogo, setDialogo] = useState(null)
  const [registrado, setRegistrado] = useState(null)
  const [errorLotes, setErrorLotes] = useState(null)

  /** El formulario necesita todos los lotes de sus puntos, aun sin saldo; se piden al abrirlo. */
  async function abrir(loteInicial) {
    setErrorLotes(null)
    try {
      const lotes = await listarLotesPunto()
      setDialogo({ lotes, loteInicial, clave: Date.now() })
    } catch (err) {
      if (err.status !== 401) setErrorLotes(err)
    }
  }

  function alRegistrar(r) {
    setDialogo(null)
    setRegistrado(r)
    recargar()
  }

  const avisos = (
    <>
      {registrado && <AvisoRegistrado registrado={registrado} onCerrar={() => setRegistrado(null)} />}
      {errorLotes && (
        <Alerta tono="error" titulo={t('movimiento.errorLotes')} descripcion={mensajeError(errorLotes, 'comun.errorConexion')} onCerrar={() => setErrorLotes(null)} />
      )}
    </>
  )

  const formulario = dialogo && (
    <DialogoMovimiento
      key={dialogo.clave}
      conProductor
      motivoPrimero
      lotes={dialogo.lotes}
      loteInicial={dialogo.loteInicial}
      motivos={MOTIVOS_PUNTO}
      registrar={registrarMovimiento}
      onRegistrado={alRegistrar}
      onCancelar={() => setDialogo(null)}
    />
  )

  return { abrir, avisos, formulario }
}
