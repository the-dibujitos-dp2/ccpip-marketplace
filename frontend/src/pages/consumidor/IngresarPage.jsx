import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { login } from '../../api/auth.js'
import { ApiError } from '../../api/http.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Enlace from '../../components/Enlace.jsx'
import { localeFechas } from '../../i18n/index.js'
import { navigate, RUTAS } from '../../router.js'
import { CORREO_VALIDO } from './acceso.js'
import LayoutAcceso from './LayoutAcceso.jsx'
import RegistroFormulario, { REGISTRO_VACIO } from './RegistroFormulario.jsx'
import RevisaTuCorreo from './RevisaTuCorreo.jsx'

function alertaPorError(error, t) {
  if (!(error instanceof ApiError)) {
    return { tono: 'error', titulo: t('ingresar.errores.conexion.titulo'), descripcion: t('comun.errorConexion') }
  }
  switch (error.code) {
    case 'CUENTA_BLOQUEADA': {
      const hasta = new Date(error.body.lockedUntil).toLocaleTimeString(localeFechas(), { hour: '2-digit', minute: '2-digit' })
      return {
        tono: 'error',
        titulo: t('ingresar.errores.bloqueada.titulo'),
        descripcion: t('ingresar.errores.bloqueada.descripcion', { hasta }),
      }
    }
    case 'CORREO_NO_CONFIRMADO':
      return { code: error.code, tono: 'aviso', titulo: t('ingresar.errores.sinConfirmar.titulo'), descripcion: t('ingresar.errores.sinConfirmar.descripcion') }
    case 'CUENTA_INACTIVA':
      return { tono: 'error', titulo: t('ingresar.errores.inactiva.titulo'), descripcion: t('ingresar.errores.inactiva.descripcion') }
    default:
      return { tono: 'error', titulo: t('ingresar.errores.credenciales.titulo'), descripcion: t('ingresar.errores.credenciales.descripcion') }
  }
}

/** Conserva ?volver= al cambiar de pestana, para volver a la pagina que pidio ingresar. */
function conVolver(ruta, search) {
  const volver = new URLSearchParams(search).get('volver')
  return volver ? `${ruta}?volver=${encodeURIComponent(volver)}` : ruta
}

/**
 * CW-08: pestanas "Crear cuenta" (CCPIP-26) e "Iniciar sesion" (CCPIP-27), en /registro e
 * /ingresar. Tras crear la cuenta muestra CW-14 (CCPIP-328). `aviso` llega de fuera: sesion
 * expirada o cerrada. `correoInicial`: el de la cuenta recien creada o verificada.
 */
export default function IngresarPage({ modo = 'ingresar', search = '', correoInicial = '', aviso, onCerrarAviso, onIngreso, onCorreoConocido }) {
  const { t } = useTranslation()
  const [email, setEmail] = useState(correoInicial)
  const [password, setPassword] = useState('')
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [enviando, setEnviando] = useState(false)
  // Cuenta recien creada: { correo, enviado, esperaInicial?, datos }. Con ella, /registro muestra CW-14.
  const [registrado, setRegistrado] = useState(null)
  const [datosRegistro, setDatosRegistro] = useState(REGISTRO_VACIO)

  const registro = modo === 'registro'
  const alertaVisible = alerta || aviso

  async function handleSubmit(e) {
    e.preventDefault()
    const nuevos = {}
    if (!CORREO_VALIDO.test(email.trim())) nuevos.email = t('ingresar.validacion.correo')
    if (!password) nuevos.password = t('ingresar.validacion.contrasena')
    setErrores(nuevos)
    if (Object.keys(nuevos).length) return

    setEnviando(true)
    setAlerta(null)
    onCerrarAviso()
    try {
      onIngreso(await login(email.trim(), password))
    } catch (error) {
      setAlerta(alertaPorError(error, t))
      setPassword('')
    } finally {
      setEnviando(false)
    }
  }

  function alRegistrarse(cuenta) {
    setRegistrado(cuenta)
    setDatosRegistro(cuenta.datos)
    setEmail(cuenta.correo)
    onCorreoConocido?.(cuenta.correo)
  }

  let contenido
  if (registro && registrado) {
    contenido = (
      <RevisaTuCorreo
        correo={registrado.correo}
        enviado={registrado.enviado}
        esperaInicial={registrado.esperaInicial}
        onIngresar={() => navigate(conVolver(RUTAS.ingresar, search))}
        onCambiarCorreo={() => setRegistrado(null)}
      />
    )
  } else if (registro) {
    contenido = <RegistroFormulario inicial={datosRegistro} onRegistrado={alRegistrarse} />
  } else {
    contenido = (
      <>
        <h1 id="titulo-acceso" className="heading-h2 ingresar__titulo">{t('ingresar.titulo')}</h1>

        {alertaVisible && (
          <Alerta
            {...alertaVisible}
            onCerrar={() => {
              setAlerta(null)
              onCerrarAviso()
            }}
          />
        )}

        <form className="ingresar__formulario" onSubmit={handleSubmit} noValidate>
          <Campo
            etiqueta={t('ingresar.correo')}
            type="email"
            autoComplete="username"
            placeholder={t('ingresar.correoEjemplo')}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            error={errores.email}
          />
          <Campo
            etiqueta={t('ingresar.contrasena')}
            tipo="contrasena"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={errores.password}
          />
          <Boton type="submit" anchoCompleto disabled={enviando}>
            {enviando ? t('ingresar.ingresando') : t('ingresar.iniciarSesion')}
          </Boton>
        </form>
        {alerta?.code === 'CORREO_NO_CONFIRMADO' && (
          <Enlace
            to={conVolver(RUTAS.registro, search)}
            className="enlace-marca ingresar__pedir-enlace"
            onClick={() => setRegistrado({ correo: email.trim(), enviado: true, esperaInicial: 0, datos: datosRegistro })}
          >
            {t('registro.pedirEnlace')}
          </Enlace>
        )}
      </>
    )
  }

  return (
    <LayoutAcceso bajada={registro ? t('registro.bajada') : t('ingresar.bajada')} etiqueta="titulo-acceso">
      <nav className="pestanas" aria-label={t('ingresar.acceso')}>
        <Enlace
          to={conVolver(RUTAS.registro, search)}
          className={`pestanas__item${registro ? ' pestanas__item--activa' : ''}`}
          aria-current={registro ? 'page' : undefined}
        >
          {t('registro.crearCuenta')}
        </Enlace>
        <Enlace
          to={conVolver(RUTAS.ingresar, search)}
          className={`pestanas__item${registro ? '' : ' pestanas__item--activa'}`}
          aria-current={registro ? undefined : 'page'}
        >
          {t('ingresar.iniciarSesion')}
        </Enlace>
      </nav>
      {contenido}
      <p className="caption ingresar__pie">{t('pie.legal')}</p>
    </LayoutAcceso>
  )
}
