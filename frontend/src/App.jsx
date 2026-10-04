import { useCallback, useEffect, useRef, useState } from 'react'
import { consultarSesion, fetchMe, logout } from './api/auth.js'
import { armIdleTimer, disarmIdleTimer, onSessionLost } from './api/http.js'
import Cabecera from './components/Cabecera.jsx'
import CatalogoPage from './pages/CatalogoPage.jsx'
import IngresarPage from './pages/IngresarPage.jsx'
import InicioPage from './pages/InicioPage.jsx'
import ProductoPage from './pages/ProductoPage.jsx'
import ProductorPage from './pages/ProductorPage.jsx'
import { navigate, RUTAS, rutaInterna, useLocation } from './router.js'

const RUTA_PRODUCTO = /^\/productos\/(\d+)$/
const RUTA_PRODUCTOR = /^\/productores\/(\d+)$/

export default function App() {
  const { path, search } = useLocation()
  const params = new URLSearchParams(search)
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
      setSesion(await consultarSesion())
    } catch {
      setSesion(null)
    } finally {
      setCargando(false)
    }
  }, [])

  // Si el servidor rechaza la sesion en una peticion que la exige, se vuelve al inicio de sesion
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

  // Rutas que dependen de la sesion
  const volver = rutaInterna(params.get('volver'), RUTAS.inicio)
  useEffect(() => {
    if (cargando) return
    if (sesion && path === RUTAS.ingresar) navigate(volver, { replace: true })
    if (!sesion && path === RUTAS.inicio) navigate(RUTAS.ingresar, { replace: true })
  }, [cargando, sesion, path, volver])

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

  const user = sesion?.user ?? null
  const cabecera = (
    <Cabecera user={user} onCerrarSesion={handleCerrarSesion} cerrando={cerrando} termino={params.get('q') ?? ''} />
  )

  if (path === RUTAS.ingresar) {
    if (sesion) return null
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

  if (path === RUTAS.inicio) {
    if (!sesion) return null
    return (
      <>
        {cabecera}
        <InicioPage user={user} minutosInactividad={Math.round(sesion.expiresInSeconds / 60)} />
      </>
    )
  }

  const producto = path.match(RUTA_PRODUCTO)
  if (producto) {
    return (
      <>
        {cabecera}
        <ProductoPage id={producto[1]} user={user} />
      </>
    )
  }

  if (RUTA_PRODUCTOR.test(path)) {
    return (
      <>
        {cabecera}
        <ProductorPage />
      </>
    )
  }

  if (path !== RUTAS.catalogo) {
    navigate(RUTAS.catalogo, { replace: true })
    return null
  }

  return (
    <>
      {cabecera}
      <CatalogoPage q={(params.get('q') ?? '').trim()} pagina={Math.max(0, Number(params.get('pagina')) || 0)} />
    </>
  )
}
