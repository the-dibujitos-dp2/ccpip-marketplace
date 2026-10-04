import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { consultarSesion, fetchMe, logout } from './api/auth.js'
import { armIdleTimer, disarmIdleTimer, onSessionLost } from './api/http.js'
import { invalidarTiendaActual } from './api/tiendaActual.js'
import LayoutTienda from './components/layout/LayoutTienda.jsx'
import CuentaPage from './pages/CuentaPage.jsx'
import BuscarPage from './pages/consumidor/BuscarPage.jsx'
import ComunidadesPage from './pages/consumidor/ComunidadesPage.jsx'
import IngresarPage from './pages/consumidor/IngresarPage.jsx'
import InicioPage from './pages/consumidor/InicioPage.jsx'
import ProductoPage from './pages/consumidor/ProductoPage.jsx'
import TiendaPage from './pages/consumidor/TiendaPage.jsx'
import EditorProductoPage from './pages/productor/EditorProductoPage.jsx'
import LotesPage from './pages/productor/LotesPage.jsx'
import PanelPage from './pages/productor/PanelPage.jsx'
import ProductosPage from './pages/productor/ProductosPage.jsx'
import TiendaEditorPage from './pages/productor/TiendaEditorPage.jsx'
import { navigate, RUTAS, rutaInterna, useLocation } from './router.js'
import { SesionContexto } from './sesion.js'

const RUTA_PRODUCTO = /^\/productos\/(\d+)$/
const RUTA_TIENDA = /^\/tiendas\/(\d+)$/
const RUTA_EDITOR = /^\/productor\/productos\/(\d+|nuevo)$/

/** A donde va cada rol al ingresar si no venia de ninguna pagina. */
function destinoPorRol(user) {
  if (user?.role === 'PRODUCTOR') return RUTAS.productor
  if (user?.role === 'CONSUMIDOR') return RUTAS.catalogo
  return RUTAS.inicio
}

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
    invalidarTiendaActual()
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

  // "/productores/1" (enlace antiguo) no es del panel: se compara por segmento
  const delProductor = path === RUTAS.productor || path.startsWith(`${RUTAS.productor}/`)
  const protegida = path === RUTAS.inicio || delProductor

  // Al cargar y al volver a una pantalla protegida con "atras", se vuelve a preguntar al servidor
  useEffect(() => {
    if (cargando || protegida) verificar()
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

  const user = sesion?.user ?? null
  const volver = rutaInterna(params.get('volver'), destinoPorRol(user))

  // Reglas de acceso de cada ruta. El servidor valida igual (CCPIP-151); esto solo evita pantallas vacias.
  useEffect(() => {
    if (cargando) return
    if (sesion && path === RUTAS.ingresar) navigate(volver, { replace: true })
    else if (!sesion && protegida) navigate(`${RUTAS.ingresar}?volver=${encodeURIComponent(path + search)}`, { replace: true })
    else if (sesion && delProductor && user.role !== 'PRODUCTOR') navigate(RUTAS.inicio, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cargando, sesion, path])

  const handleCerrarSesion = useCallback(async () => {
    setCerrando(true)
    try {
      await logout()
    } catch {
      // Aunque falle la red, la pantalla se cierra; la cookie expira sola en el servidor
    }
    setCerrando(false)
    terminarSesion({ tono: 'exito', titulo: 'Cerraste sesión', descripcion: 'Vuelve cuando quieras.' })
  }, [terminarSesion])

  const contexto = useMemo(() => ({ user, cerrarSesion: handleCerrarSesion, cerrando }), [user, handleCerrarSesion, cerrando])

  if (cargando) return <p className="visually-hidden">Cargando…</p>

  let pagina
  if (path === RUTAS.ingresar) {
    pagina = sesion ? null : (
      <IngresarPage
        aviso={aviso}
        onCerrarAviso={() => setAviso(null)}
        onIngreso={(nueva) => {
          setAviso(null)
          setSesion(nueva)
        }}
      />
    )
  } else if (protegida && !sesion) {
    pagina = null
  } else if (path === RUTAS.inicio) {
    pagina = (
      <LayoutTienda>
        <CuentaPage user={user} minutosInactividad={Math.round(sesion.expiresInSeconds / 60)} />
      </LayoutTienda>
    )
  } else if (delProductor) {
    const editor = path.match(RUTA_EDITOR)
    if (user.role !== 'PRODUCTOR') pagina = null
    else if (path === RUTAS.productor) pagina = <PanelPage path={path} />
    else if (path === RUTAS.productorTienda) pagina = <TiendaEditorPage path={path} />
    else if (path === RUTAS.productorProductos) pagina = <ProductosPage path={path} search={search} />
    else if (path === RUTAS.productorLotes) pagina = <LotesPage path={path} search={search} />
    else if (editor) {
      // Sin key por producto: al subir la primera foto el borrador nace y la URL pasa de /nuevo a
      // /:id; si el editor se montara de nuevo, perderia las fotos que se estan subiendo
      pagina = <EditorProductoPage path={RUTAS.productorProductos} id={editor[1] === 'nuevo' ? null : editor[1]} search={search} />
    }
    else pagina = <PanelPage path={RUTAS.productor} />
  } else if (path === RUTAS.catalogo) {
    pagina = <InicioPage />
  } else if (path === RUTAS.buscar) {
    pagina = <BuscarPage search={search} />
  } else if (path === RUTAS.comunidades) {
    pagina = <ComunidadesPage />
  } else if (RUTA_PRODUCTO.test(path)) {
    pagina = <ProductoPage id={path.match(RUTA_PRODUCTO)[1]} />
  } else if (RUTA_TIENDA.test(path)) {
    pagina = <TiendaPage id={path.match(RUTA_TIENDA)[1]} />
  } else if (/^\/productores\/(\d+)$/.test(path)) {
    // Enlace antiguo al perfil del productor: ahora es la tienda de la comunidad
    navigate(path.replace('/productores/', '/tiendas/'), { replace: true })
    pagina = null
  } else {
    navigate(RUTAS.catalogo, { replace: true })
    pagina = null
  }

  return <SesionContexto.Provider value={contexto}>{pagina}</SesionContexto.Provider>
}
