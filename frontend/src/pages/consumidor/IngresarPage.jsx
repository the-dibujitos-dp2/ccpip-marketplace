import { useState } from 'react'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import { login } from '../../api/auth.js'
import { ApiError } from '../../api/http.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Enlace from '../../components/Enlace.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Logotipo from '../../components/Logotipo.jsx'
import { RUTAS } from '../../router.js'
import './IngresarPage.css'

// Partes separadas por puntos literales: sin retroceso exponencial (Sonar S8786)
const CORREO_VALIDO = /^[^\s@]+@[^\s@.]+(?:\.[^\s@.]+)+$/

function alertaPorError(error) {
  if (!(error instanceof ApiError)) {
    return { tono: 'error', titulo: 'No pudimos conectar', descripcion: 'Revisa tu conexión e inténtalo otra vez.' }
  }
  switch (error.code) {
    case 'CUENTA_BLOQUEADA': {
      const hasta = new Date(error.body.lockedUntil).toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit' })
      return {
        tono: 'error',
        titulo: 'Cuenta bloqueada temporalmente',
        descripcion: `Por seguridad bloqueamos tu cuenta tras varios intentos fallidos. Vuelve a intentarlo a las ${hasta}.`,
      }
    }
    case 'CORREO_NO_CONFIRMADO':
      return { tono: 'aviso', titulo: 'Confirma tu correo', descripcion: 'Abre el enlace que te enviamos a tu correo y vuelve a ingresar.' }
    case 'CUENTA_INACTIVA':
      return { tono: 'error', titulo: 'Cuenta desactivada', descripcion: 'Comunícate con la CCPIP para reactivarla.' }
    default:
      return { tono: 'error', titulo: 'No pudimos iniciar sesión', descripcion: 'El correo o la contraseña no son correctos.' }
  }
}

/**
 * CW-08: inicio de sesion (CCPIP-27). La pestana "Crear cuenta" del prototipo aparece cuando
 * exista el registro de consumidores. `aviso` llega de fuera: sesion expirada o cerrada.
 */
export default function IngresarPage({ aviso, onCerrarAviso, onIngreso }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [enviando, setEnviando] = useState(false)

  const alertaVisible = alerta || aviso

  async function handleSubmit(e) {
    e.preventDefault()
    const nuevos = {}
    if (!CORREO_VALIDO.test(email.trim())) nuevos.email = 'Escribe un correo válido, por ejemplo nombre@correo.com'
    if (!password) nuevos.password = 'Escribe tu contraseña'
    setErrores(nuevos)
    if (Object.keys(nuevos).length) return

    setEnviando(true)
    setAlerta(null)
    onCerrarAviso()
    try {
      onIngreso(await login(email.trim(), password))
    } catch (error) {
      setAlerta(alertaPorError(error))
      setPassword('')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="ingresar">
      <aside className="ingresar__marca">
        <Enlace to={RUTAS.catalogo} aria-label="Mercado CCPIP, ir al inicio" className="ingresar__logo">
          <Logotipo inverso />
        </Enlace>
        <div className="ingresar__mensaje">
          <p className="ingresar__titular">Compra directo a las comunidades que cuidan el bosque</p>
          <p className="ingresar__bajada">Ingresa para comprar, seguir tus pedidos y administrar tu tienda.</p>
          <div className="ingresar__fotos" aria-hidden="true">
            {['CACAO', 'SUPERFRUTOS', 'ACEITES', 'CAFE'].map((l) => (
              <ImagenProducto key={l} linea={l} className="ingresar__foto" />
            ))}
          </div>
        </div>
        <p className="ingresar__confianza">
          <img src={iconoEscudo} width="16" height="16" alt="" />
          Origen verificado por la Cámara de Comercio de los Pueblos Indígenas del Perú
        </p>
      </aside>

      <main className="ingresar__formulario-zona">
        <section className="ingresar__tarjeta" aria-labelledby="titulo-ingresar">
          <div className="pestanas" role="tablist" aria-label="Acceso">
            <span className="pestanas__item pestanas__item--activa" role="tab" aria-selected="true">
              Iniciar sesión
            </span>
          </div>
          <h1 id="titulo-ingresar" className="heading-h2 ingresar__titulo">Ingresa a tu cuenta</h1>

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
              etiqueta="Correo electrónico"
              type="email"
              autoComplete="username"
              placeholder="nombre@correo.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              error={errores.email}
            />
            <Campo
              etiqueta="Contraseña"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              error={errores.password}
            />
            <Boton type="submit" anchoCompleto disabled={enviando}>
              {enviando ? 'Ingresando…' : 'Iniciar sesión'}
            </Boton>
          </form>
          <p className="caption ingresar__pie">© 2026 CCPIP · Prototipo académico con datos de prueba</p>
        </section>
      </main>
    </div>
  )
}
