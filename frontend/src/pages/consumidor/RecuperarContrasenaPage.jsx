import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoSobre from '../../assets/icons/sobre.svg'
import { solicitarRecuperacion } from '../../api/auth.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Enlace from '../../components/Enlace.jsx'
import { RUTAS } from '../../router.js'
import { CORREO_VALIDO } from './acceso.js'
import LayoutAcceso from './LayoutAcceso.jsx'

/**
 * CCPIP-33: "¿Olvidaste tu contraseña?" (estilo CW-14). La respuesta es la misma exista o no la
 * cuenta, para no revelar que correos estan registrados.
 */
export default function RecuperarContrasenaPage({ correoInicial = '' }) {
  const { t } = useTranslation()
  const [correo, setCorreo] = useState(correoInicial)
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [enviado, setEnviado] = useState(false)

  async function enviar(e) {
    e.preventDefault()
    if (!CORREO_VALIDO.test(correo.trim())) {
      setError(t('ingresar.validacion.correo'))
      return
    }
    setError(null)
    setEnviando(true)
    try {
      await solicitarRecuperacion(correo.trim())
      setEnviado(true)
    } catch {
      setError(t('comun.errorConexion'))
    } finally {
      setEnviando(false)
    }
  }

  return (
    <LayoutAcceso bajada={t('ingresar.bajada')} etiqueta="titulo-acceso">
      <div className="revisa">
        <span className="revisa__icono" aria-hidden="true">
          <img src={iconoSobre} width="24" height="24" alt="" />
        </span>
        <h1 id="titulo-acceso" className="heading-h2">{t('recuperar.titulo')}</h1>
        {enviado ? (
          <>
            <Alerta tono="exito" titulo={t('recuperar.enviado.titulo')} descripcion={t('recuperar.enviado.descripcion', { correo: correo.trim() })} />
            <p className="revisa__texto">{t('recuperar.enviado.noLlego')}</p>
            <Boton tipo="secundario" anchoCompleto onClick={() => setEnviado(false)}>{t('recuperar.otraVez')}</Boton>
          </>
        ) : (
          <>
            <p className="revisa__texto">{t('recuperar.bajada')}</p>
            <form className="ingresar__formulario" onSubmit={enviar} noValidate>
              <Campo
                etiqueta={t('ingresar.correo')}
                type="email"
                autoComplete="email"
                placeholder={t('ingresar.correoEjemplo')}
                value={correo}
                onChange={(e) => setCorreo(e.target.value)}
                error={error}
              />
              <Boton type="submit" anchoCompleto disabled={enviando}>
                {enviando ? t('verificarCorreo.enviando') : t('recuperar.enviar')}
              </Boton>
            </form>
          </>
        )}
        <Enlace to={RUTAS.ingresar} className="enlace-marca">{t('recuperar.volver')}</Enlace>
      </div>
    </LayoutAcceso>
  )
}
