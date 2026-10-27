import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import { restablecerContrasena } from '../../api/auth.js'
import { ApiError } from '../../api/http.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Enlace from '../../components/Enlace.jsx'
import { navigate, RUTAS } from '../../router.js'
import { contrasenaValida } from './acceso.js'
import LayoutAcceso from './LayoutAcceso.jsx'

const ENLACE_FALLIDO = { ENLACE_USADO: 'usado', ENLACE_VENCIDO: 'vencido', ENLACE_INVALIDO: 'invalido' }

/**
 * CCPIP-33: destino del enlace del correo, /restablecer-contrasena?token=... Pide la nueva clave
 * dos veces y la cambia; el enlace no inicia sesion. Quita el token de la barra de direcciones para
 * que no quede en el historial. Si el enlace ya se uso, vencio o no sirve, ofrece pedir otro.
 */
export default function RestablecerContrasenaPage({ search, onRestablecida }) {
  const { t } = useTranslation()
  const [token] = useState(() => new URLSearchParams(search).get('token') ?? '')
  const [estado, setEstado] = useState(token ? 'formulario' : 'invalido')
  const [clave, setClave] = useState('')
  const [repetida, setRepetida] = useState('')
  const [errores, setErrores] = useState({})
  const [enviando, setEnviando] = useState(false)
  const [conexion, setConexion] = useState(false)

  useEffect(() => {
    if (token) window.history.replaceState(null, '', RUTAS.restablecerContrasena)
  }, [token])

  async function enviar(e) {
    e.preventDefault()
    const nuevos = {}
    if (!contrasenaValida(clave)) nuevos.clave = t('registro.validacion.contrasena')
    if (clave !== repetida) nuevos.repetida = t('recuperar.validacion.noCoinciden')
    setErrores(nuevos)
    if (Object.keys(nuevos).length) return
    setEnviando(true)
    setConexion(false)
    try {
      const { email } = await restablecerContrasena(token, clave)
      onRestablecida?.(email)
      setEstado('exito')
    } catch (error) {
      if (error instanceof ApiError && ENLACE_FALLIDO[error.code]) setEstado(ENLACE_FALLIDO[error.code])
      else if (error instanceof ApiError && error.status === 400) setErrores({ clave: t('registro.validacion.contrasena') })
      else setConexion(true)
    } finally {
      setEnviando(false)
    }
  }

  let contenido
  if (estado === 'exito') {
    contenido = (
      <>
        <Alerta tono="exito" titulo={t('recuperar.exito.titulo')} descripcion={t('recuperar.exito.descripcion')} />
        <Boton anchoCompleto onClick={() => navigate(RUTAS.ingresar)}>{t('ingresar.iniciarSesion')}</Boton>
      </>
    )
  } else if (estado === 'formulario') {
    contenido = (
      <>
        {conexion && <Alerta tono="error" titulo={t('ingresar.errores.conexion.titulo')} descripcion={t('comun.errorConexion')} />}
        <form className="ingresar__formulario" onSubmit={enviar} noValidate>
          <Campo
            etiqueta={t('recuperar.nueva')}
            tipo="contrasena"
            autoComplete="new-password"
            value={clave}
            onChange={(e) => setClave(e.target.value)}
            error={errores.clave}
            ayuda={t('registro.contrasenaAyuda')}
          />
          <Campo
            etiqueta={t('recuperar.repetir')}
            tipo="contrasena"
            autoComplete="new-password"
            value={repetida}
            onChange={(e) => setRepetida(e.target.value)}
            error={errores.repetida}
          />
          <Boton type="submit" anchoCompleto cargando={enviando}>
            {enviando ? t('recuperar.guardando') : t('recuperar.guardar')}
          </Boton>
        </form>
      </>
    )
  } else {
    contenido = (
      <>
        <Alerta tono="aviso" titulo={t(`recuperar.${estado}.titulo`)} descripcion={t(`recuperar.${estado}.descripcion`)} />
        <Boton anchoCompleto onClick={() => navigate(RUTAS.recuperarContrasena)}>{t('recuperar.pedirNuevo')}</Boton>
      </>
    )
  }

  return (
    <LayoutAcceso bajada={t('ingresar.bajada')} etiqueta="titulo-acceso">
      <div className="revisa">
        <span className="revisa__icono" aria-hidden="true">
          <img src={iconoEscudo} width="24" height="24" alt="" />
        </span>
        <h1 id="titulo-acceso" className="heading-h2">{t('recuperar.nuevaTitulo')}</h1>
        {contenido}
        {estado !== 'exito' && <Enlace to={RUTAS.ingresar} className="enlace-marca">{t('recuperar.volver')}</Enlace>}
      </div>
    </LayoutAcceso>
  )
}
