import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchMe, logout } from './api/auth.js'
import { armIdleTimer, disarmIdleTimer, onSessionLost } from './api/http.js'
import IngresarPage from './pages/IngresarPage.jsx'
import InicioPage from './pages/InicioPage.jsx'
import { navigate, RUTAS, usePath } from './router.js'

export default function App() {
  const path = usePath()
  const [sesion, setSesion] = useState(null) // { user, expiresInSeconds }
  const [cargando, setCargando] = useState(true)
  const [aviso, setAviso] = useState(null)
  const [cerrando, setCerrando] = useState(false)
  const minutos = useRef(30)

  const terminarSesion = useCallback((nuevoAviso) => {
    disarmIdleTimer()
    setSesion(null)
    setAviso(nuevoAviso)
    navigate(RUTAS.ingresar, { replace: true })
  }, [])

  // La sesion se confirma siempre con el servidor, nunca con datos guardados en el navegador
  const verificar = useCallback(async () => {
    try {
      setSesion(await fetchMe())
    } catch {
      setSesion(null)
    } finally {
      setCargando(false)
    }
  }, [])

  // Si el servidor rechaza la sesion en cualquier peticion, se vuelve al inicio de sesion
  useEffect(
    () =>
      onSessionLost((code) => {
        if (code === 'SESION_EXPIRADA') {
          terminarSesion({
            tono: 'aviso',
            titulo: 'Tu sesión expiró',
            descripcion: `La cerramos tras ${minutos.current} minutos sin actividad. Vuelve a ingresar para continuar.`,
          })
        } else {
          terminarSesion(null)
        }
      }),
    [terminarSesion],
  )

  // Al cargar y al volver a una pantalla protegida con "atras", se vuelve a preguntar al servidor
  useEffect(() => {
    if (cargando || path === RUTAS.inicio) verificar()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path])

  // Pagina restaurada desde la cache de atras/adelante del navegador (bfcache)
  useEffect(() => {
    const onPageShow = (e) => e.persisted && verificar()
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [verificar])

  // Expiracion por inactividad: sin peticiones durante el plazo, se consulta /me y el
  // servidor responde SESION_EXPIRADA
  useEffect(() => {
    if (!sesion) return
    minutos.current = Math.round(sesion.expiresInSeconds / 60)
    armIdleTimer(sesion.expiresInSeconds, () => fetchMe())
  }, [sesion])

  // Cada estado tiene su ruta
  useEffect(() => {
    if (cargando) return
    if (sesion && path !== RUTAS.inicio) navigate(RUTAS.inicio, { replace: true })
    if (!sesion && path !== RUTAS.ingresar) navigate(RUTAS.ingresar, { replace: true })
  }, [cargando, sesion, path])

  async function handleCerrarSesion() {
    setCerrando(true)
    try {
      await logout()
    } catch {
      // Aunque falle la red, la pantalla se cierra; la cookie expira sola en el servidor
    }
    setCerrando(false)
    terminarSesion({ tono: 'exito', titulo: 'Cerraste sesión', descripcion: 'Vuelve cuando quieras.' })
  }

  if (cargando) return <p className="visually-hidden">Cargando…</p>

  if (sesion) {
    return (
      <InicioPage
        user={sesion.user}
        minutosInactividad={Math.round(sesion.expiresInSeconds / 60)}
        onCerrarSesion={handleCerrarSesion}
        cerrando={cerrando}
      />
    )
  }

  return (
    <IngresarPage
      aviso={aviso}
      onCerrarAviso={() => setAviso(null)}
      onIngreso={(nueva) => {
        setAviso(null)
        setSesion(nueva)
      }}
    />
  )
}
