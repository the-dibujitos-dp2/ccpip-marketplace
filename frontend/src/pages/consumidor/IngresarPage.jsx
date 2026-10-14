import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import { login } from '../../api/auth.js'
import { ApiError } from '../../api/http.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Enlace from '../../components/Enlace.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Logotipo from '../../components/Logotipo.jsx'
import SelectorIdioma from '../../components/SelectorIdioma.jsx'
import { localeFechas } from '../../i18n/index.js'
import { RUTAS } from '../../router.js'
import './IngresarPage.css'

// Partes separadas por puntos literales: sin retroceso exponencial (Sonar S8786)
const CORREO_VALIDO = /^[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+$/

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
      return { tono: 'aviso', titulo: t('ingresar.errores.sinConfirmar.titulo'), descripcion: t('ingresar.errores.sinConfirmar.descripcion') }
    case 'CUENTA_INACTIVA':
      return { tono: 'error', titulo: t('ingresar.errores.inactiva.titulo'), descripcion: t('ingresar.errores.inactiva.descripcion') }
    default:
      return { tono: 'error', titulo: t('ingresar.errores.credenciales.titulo'), descripcion: t('ingresar.errores.credenciales.descripcion') }
  }
}

/**
 * CW-08: inicio de sesion (CCPIP-27). La pestana "Crear cuenta" del prototipo aparece cuando
 * exista el registro de consumidores. `aviso` llega de fuera: sesion expirada o cerrada.
 */
export default function IngresarPage({ aviso, onCerrarAviso, onIngreso }) {
  const { t } = useTranslation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [enviando, setEnviando] = useState(false)

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

  return (
    <div className="ingresar">
      <aside className="ingresar__marca">
        <Enlace to={RUTAS.catalogo} aria-label={t('marca.irAlInicio')} className="ingresar__logo">
          <Logotipo inverso />
        </Enlace>
        <div className="ingresar__mensaje">
          <p className="ingresar__titular">{t('ingresar.titular')}</p>
          <p className="ingresar__bajada">{t('ingresar.bajada')}</p>
          <div className="ingresar__fotos" aria-hidden="true">
            {['CACAO', 'SUPERFRUTOS', 'ACEITES', 'CAFE'].map((l) => (
              <ImagenProducto key={l} linea={l} className="ingresar__foto" />
            ))}
          </div>
        </div>
        <p className="ingresar__confianza">
          <img src={iconoEscudo} width="16" height="16" alt="" />
          {t('ingresar.confianza')}
        </p>
      </aside>

      <main className="ingresar__formulario-zona">
        <SelectorIdioma className="ingresar__idioma" />
        <section className="ingresar__tarjeta" aria-labelledby="titulo-ingresar">
          <div className="pestanas" role="tablist" aria-label={t('ingresar.acceso')}>
            <span className="pestanas__item pestanas__item--activa" role="tab" aria-selected="true">
              {t('ingresar.iniciarSesion')}
            </span>
          </div>
          <h1 id="titulo-ingresar" className="heading-h2 ingresar__titulo">{t('ingresar.titulo')}</h1>

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
          <p className="caption ingresar__pie">{t('pie.legal')}</p>
        </section>
      </main>
    </div>
  )
}
