import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoSobre from '../../assets/icons/sobre.svg'
import { reenviarVerificacion } from '../../api/auth.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Enlace from '../../components/Enlace.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'

/** Igual que en el servidor: un enlace por minuto como mucho (ccpip.auth.enlace-reenvio-espera). */
export const ESPERA_REENVIO_S = 60

/**
 * CW-14 (CCPIP-328): tras crear la cuenta, pide abrir el enlace del correo. Permite reenviarlo,
 * volver al formulario para corregir el correo o seguir explorando sin cuenta activa.
 * `enviado` en false: la cuenta existe pero el correo no salio, y se ofrece reenviar.
 * `esperaInicial`: segundos antes de poder reenviar; 0 si se llega desde un login sin verificar.
 */
export default function RevisaTuCorreo({ correo, enviado, esperaInicial = ESPERA_REENVIO_S, onCambiarCorreo, onIngresar }) {
  const { t } = useTranslation()
  const [espera, setEspera] = useState(esperaInicial)
  const [alerta, setAlerta] = useState(
    enviado ? null : { tono: 'aviso', titulo: t('revisaCorreo.noEnviado.titulo'), descripcion: t('revisaCorreo.noEnviado.descripcion') },
  )
  const [reenviando, setReenviando] = useState(false)

  // Cuenta regresiva hasta que el servidor acepte otro reenvio
  useEffect(() => {
    if (espera <= 0) return undefined
    const id = setTimeout(() => setEspera((s) => s - 1), 1000)
    return () => clearTimeout(id)
  }, [espera])

  async function reenviar() {
    setReenviando(true)
    try {
      await reenviarVerificacion(correo)
      setAlerta({ tono: 'exito', titulo: t('revisaCorreo.reenviado') })
      setEspera(ESPERA_REENVIO_S)
    } catch (error) {
      setAlerta({ tono: 'error', titulo: mensajeError(error, 'comun.errorConexion') })
    } finally {
      setReenviando(false)
    }
  }

  let textoReenviar = t('revisaCorreo.reenviar')
  if (reenviando) textoReenviar = t('revisaCorreo.reenviando')
  else if (espera > 0) textoReenviar = t('revisaCorreo.esperar', { segundos: espera })

  return (
    <div className="revisa">
      <span className="revisa__icono" aria-hidden="true">
        <img src={iconoSobre} width="24" height="24" alt="" />
      </span>
      <h1 id="titulo-acceso" className="heading-h2">{t('revisaCorreo.titulo')}</h1>
      <p className="revisa__texto">
        {t(enviado ? 'revisaCorreo.descripcion' : 'revisaCorreo.descripcionNoEnviado', { correo })}
      </p>

      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}

      <Boton anchoCompleto onClick={onIngresar}>{t('revisaCorreo.yaVerifique')}</Boton>
      <div className="revisa__acciones">
        <Boton tipo="secundario" tamano="M" onClick={reenviar} disabled={reenviando || espera > 0}>
          {textoReenviar}
        </Boton>
        <Boton tipo="terciario" tamano="M" onClick={onCambiarCorreo}>{t('revisaCorreo.cambiarCorreo')}</Boton>
      </div>
      <p className="caption">{t('revisaCorreo.ayuda')}</p>
      <Enlace to={RUTAS.catalogo} className="enlace-marca revisa__explorar">{t('revisaCorreo.seguirExplorando')}</Enlace>
    </div>
  )
}
