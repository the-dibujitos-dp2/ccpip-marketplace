import { useState } from 'react'
import { login } from '../api/auth.js'
import { ApiError } from '../api/http.js'
import Alerta from '../components/Alerta.jsx'
import BarraConfianza from '../components/BarraConfianza.jsx'
import Boton from '../components/Boton.jsx'
import Campo from '../components/Campo.jsx'
import Logotipo from '../components/Logotipo.jsx'
import './IngresarPage.css'

const CORREO_VALIDO = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

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
      return {
        tono: 'aviso',
        titulo: 'Confirma tu correo',
        descripcion: 'Abre el enlace que te enviamos a tu correo y vuelve a ingresar.',
      }
    case 'CUENTA_INACTIVA':
      return { tono: 'error', titulo: 'Cuenta desactivada', descripcion: 'Comunícate con la CCPIP para reactivarla.' }
    default:
      return { tono: 'error', titulo: 'No pudimos iniciar sesión', descripcion: 'El correo o la contraseña no son correctos.' }
  }
}

/** Inicio de sesion (CCPIP-27). `aviso` llega de fuera: sesion expirada o cerrada. */
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
      const sesion = await login(email.trim(), password)
      onIngreso(sesion)
    } catch (error) {
      setAlerta(alertaPorError(error))
      setPassword('')
    } finally {
      setEnviando(false)
    }
  }

  return (
    <div className="ingresar">
      <BarraConfianza />
      <main className="ingresar__contenido">
        <section className="ingresar__tarjeta" aria-labelledby="titulo-ingresar">
          <Logotipo />
          <div className="ingresar__encabezado">
            <p className="overline">Iniciar sesión</p>
            <h1 id="titulo-ingresar" className="display-l">Ingresa a tu cuenta</h1>
            <p className="body-l">Compra productos con historia, directo de las comunidades.</p>
          </div>

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
              etiqueta="Correo"
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
              {enviando ? 'Ingresando…' : 'Ingresar'}
            </Boton>
          </form>
        </section>
        <p className="caption">© 2026 CCPIP · Prototipo académico con datos de prueba</p>
      </main>
    </div>
  )
}
