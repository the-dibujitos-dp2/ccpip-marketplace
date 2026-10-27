import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarSesion, fetchMe, logout } from './api/auth.js'
import { olvidarUnidadesCarrito } from './api/carrito.js'
import { armIdleTimer, disarmIdleTimer, onSessionLost } from './api/http.js'
import { invalidarTiendaActual } from './api/tiendaActual.js'
import { aplicarIdiomaDeSesion, olvidarIdiomaDeSesion } from './i18n/preferencia.js'
import LayoutTienda from './components/layout/LayoutTienda.jsx'
import CuentaPage from './pages/CuentaPage.jsx'
import AccesoRestringidoPage from './pages/admin/AccesoRestringidoPage.jsx'
import AutorizacionesPage from './pages/admin/AutorizacionesPage.jsx'
import CatalogoMaestroPage from './pages/admin/CatalogoMaestroPage.jsx'
import ComisionesPage from './pages/admin/ComisionesPage.jsx'
import ComisionPage from './pages/admin/ComisionPage.jsx'
import DenunciasAdminPage from './pages/admin/DenunciasPage.jsx'
import ParametrosPage from './pages/admin/ParametrosPage.jsx'
import ReportesPage from './pages/admin/ReportesPage.jsx'
import MiHistorialPage from './pages/admin/MiHistorialPage.jsx'
import ModeracionPage from './pages/admin/ModeracionPage.jsx'
import DetalleCuentaPage from './pages/admin/DetalleCuentaPage.jsx'
import ExistenciasPage from './pages/admin/ExistenciasPage.jsx'
import CuentasPage from './pages/admin/CuentasPage.jsx'
import NuevaCuentaPage from './pages/admin/NuevaCuentaPage.jsx'
import MotivosMermaPage from './pages/admin/MotivosMermaPage.jsx'
import ExpedienteAutorizacionPage from './pages/admin/ExpedienteAutorizacionPage.jsx'
import ExpedienteModeracionPage from './pages/admin/ExpedienteModeracionPage.jsx'
import ExpedienteVideoPage from './pages/admin/ExpedienteVideoPage.jsx'
import FichaProductorPage from './pages/admin/FichaProductorPage.jsx'
import ProductoresPage from './pages/admin/ProductoresPage.jsx'
import CertificadosPage from './pages/admin/CertificadosPage.jsx'
import ExpedienteDenunciasPage from './pages/admin/ExpedienteDenunciasPage.jsx'
import TableroPage from './pages/admin/TableroPage.jsx'
import PermisosPage from './pages/admin/PermisosPage.jsx'
import InventarioPage from './pages/acopio/InventarioPage.jsx'
import MovimientosPage from './pages/acopio/MovimientosPage.jsx'
import PedidosPage from './pages/acopio/PedidosPage.jsx'
import TransferenciasPuntoPage from './pages/acopio/TransferenciasPage.jsx'
import AlertasPuntoPage from './pages/acopio/AlertasPage.jsx'
import MermaPuntoPage from './pages/acopio/MermaPage.jsx'
import MisEntregasPage from './pages/distribuidor/MisEntregasPage.jsx'
import PedidosDisponiblesPage from './pages/distribuidor/PedidosDisponiblesPage.jsx'
import ReportesPuntoPage from './pages/acopio/ReportesPage.jsx'
import BuscarPage from './pages/consumidor/BuscarPage.jsx'
import CarritoPage from './pages/consumidor/CarritoPage.jsx'
import CompraPage from './pages/consumidor/CompraPage.jsx'
import ComunidadesPage from './pages/consumidor/ComunidadesPage.jsx'
import IngresarPage from './pages/consumidor/IngresarPage.jsx'
import InicioPage from './pages/consumidor/InicioPage.jsx'
import MisPedidosPage from './pages/consumidor/MisPedidosPage.jsx'
import MisDatosPage from './pages/consumidor/MisDatosPage.jsx'
import MisDireccionesPage from './pages/consumidor/MisDireccionesPage.jsx'
import PedidoPage from './pages/consumidor/PedidoPage.jsx'
import RecuperarContrasenaPage from './pages/consumidor/RecuperarContrasenaPage.jsx'
import RestablecerContrasenaPage from './pages/consumidor/RestablecerContrasenaPage.jsx'
import ProductoPage from './pages/consumidor/ProductoPage.jsx'
import TiendaPage from './pages/consumidor/TiendaPage.jsx'
import VerificarCorreoPage from './pages/consumidor/VerificarCorreoPage.jsx'
import CuentaBancariaPage from './pages/productor/CuentaBancariaPage.jsx'
import DenunciasProductorPage from './pages/productor/DenunciasPage.jsx'
import AlmacenPage from './pages/productor/AlmacenPage.jsx'
import EditorProductoPage from './pages/productor/EditorProductoPage.jsx'
import EvidenciasPage from './pages/productor/EvidenciasPage.jsx'
import PremiosPage from './pages/productor/PremiosPage.jsx'
import LiquidacionesPage from './pages/productor/LiquidacionesPage.jsx'
import LotesPage from './pages/productor/LotesPage.jsx'
import PanelPage from './pages/productor/PanelPage.jsx'
import PedidosProductorPage from './pages/productor/PedidosPage.jsx'
import NuevaTransferenciaPage from './pages/productor/NuevaTransferenciaPage.jsx'
import ProductosPage from './pages/productor/ProductosPage.jsx'
import TiendaEditorPage from './pages/productor/TiendaEditorPage.jsx'
import TransferenciasPage from './pages/productor/TransferenciasPage.jsx'
import AlertasProductorPage from './pages/productor/AlertasPage.jsx'
import MermaProductorPage from './pages/productor/MermaPage.jsx'
import ReportesProductorPage from './pages/productor/ReportesPage.jsx'
import { navigate, RUTAS, rutaInterna, useLocation } from './router.js'
import { SesionContexto } from './sesion.js'
import Cargando from './components/Cargando.jsx'

const RUTA_PRODUCTO = /^\/productos\/(\d+)$/
const RUTA_TIENDA = /^\/tiendas\/(\d+)$/
const RUTA_EDITOR = /^\/productor\/productos\/(\d+|nuevo)$/
const RUTA_PEDIDO = /^\/pedidos\/(\d+)$/
const RUTA_EVIDENCIAS = /^\/productor\/productos\/(\d+)\/evidencias$/
const RUTA_PREMIOS = /^\/productor\/productos\/(\d+)\/premios$/
const RUTA_EXPEDIENTE = /^\/admin\/autorizaciones\/(\d+)$/
const RUTA_MODERACION = /^\/admin\/moderacion\/(\d+)$/
const RUTA_VIDEO = /^\/admin\/moderacion\/videos\/(\d+)$/
const RUTA_FICHA_PRODUCTOR = /^\/admin\/productores\/(\d+)$/
const RUTA_DENUNCIAS_PRODUCTOR = /^\/admin\/denuncias\/productores\/(\d+)$/
const RUTA_CUENTA = /^\/admin\/cuentas\/(\d+)$/

/**
 * Rutas del modulo del sub administrador: autorizaciones de productos (S-01, S-02) y su expediente, la
 * moderacion de publicaciones y videos (CCPIP-46, CCPIP-118), los productores con los productos que pueden vender (CCPIP-292,
 * CCPIP-291, CCPIP-330), la vigencia de los certificados (CCPIP-184) y el historial de sus propias acciones (CCPIP-126).
 */
const delModuloSub = (path) =>
  path === RUTAS.adminAutorizaciones || RUTA_EXPEDIENTE.test(path) || path === RUTAS.adminProductores || RUTA_FICHA_PRODUCTOR.test(path)
  || path === RUTAS.adminMiHistorial || path === RUTAS.adminModeracion || RUTA_MODERACION.test(path)
  || path === RUTAS.adminModeracionVideos || RUTA_VIDEO.test(path) || path === RUTAS.adminCertificados

/** CCPIP-84: denuncias contra productores; el sub administrador las gestiona y el super las consulta. */
const deDenuncias = (path) => path === RUTAS.adminDenuncias || RUTA_DENUNCIAS_PRODUCTOR.test(path)

/**
 * Administracion de la Camara: el superadministrador supervisa todo menos la revision de
 * autorizaciones, que es una accion de gobierno del sub administrador (CCPIP-174, CCPIP-284); el sub
 * administrador ve esa revision y la comision por pedido (CCPIP-91).
 */
function puedeVerAdmin(user, path) {
  if (user?.role === 'SUPER_ADMIN') return !delModuloSub(path)
  // CCPIP-95, 307, 308: el sub administrador busca, suspende y reactiva cuentas de la operacion; no crea cuentas
  const cuentas = path === RUTAS.adminCuentas || RUTA_CUENTA.test(path)
  return user?.role === 'SUB_ADMIN' && (path === RUTAS.adminComisiones || cuentas || delModuloSub(path) || deDenuncias(path))
}

/** A donde va cada rol al ingresar si no venia de ninguna pagina. */
function destinoPorRol(user) {
  if (user?.role === 'PRODUCTOR') return RUTAS.productor
  if (user?.role === 'CONSUMIDOR') return RUTAS.catalogo
  if (user?.role === 'SUPER_ADMIN') return RUTAS.admin
  if (user?.role === 'SUB_ADMIN') return RUTAS.adminAutorizaciones
  return RUTAS.inicio
}

export default function App() {
  const { t, i18n } = useTranslation()
  const { path, search } = useLocation()
  const params = new URLSearchParams(search)
  const [sesion, setSesion] = useState(null) // { user, expiresInSeconds }
  const [cargando, setCargando] = useState(true)
  const [aviso, setAviso] = useState(null)
  const [cerrando, setCerrando] = useState(false)
  // CCPIP-26 y CCPIP-328: correo de la cuenta recien creada o verificada, para no volver a escribirlo
  const [correoConocido, setCorreoConocido] = useState('')
  const minutos = useRef(30)

  const terminarSesion = useCallback((nuevoAviso) => {
    disarmIdleTimer()
    invalidarTiendaActual()
    olvidarIdiomaDeSesion()
    olvidarUnidadesCarrito()
    setSesion(null)
    setAviso(nuevoAviso)
    navigate(RUTAS.ingresar, { replace: true })
  }, [])

  // La sesion se confirma siempre con el servidor, nunca con datos guardados en el navegador
  const verificar = useCallback(async () => {
    try {
      const nueva = await consultarSesion()
      // CCPIP-265: con sesion, el idioma guardado en la cuenta (o el que eligio antes de ingresar)
      await aplicarIdiomaDeSesion(nueva?.user)
      setSesion(nueva)
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
            titulo: t('app.sesionExpirada.titulo'),
            descripcion: t('app.sesionExpirada.descripcion', { minutos: minutos.current }),
          })
        } else {
          terminarSesion(null)
        }
      }),
    [terminarSesion, t],
  )

  // "/productores/1" (enlace antiguo) no es del panel: se compara por segmento
  const delProductor = path === RUTAS.productor || path.startsWith(`${RUTAS.productor}/`)
  const delComprador = path === RUTAS.carrito || path === RUTAS.compra || path === RUTAS.misPedidos || path === RUTAS.misDatos || path === RUTAS.misDirecciones || RUTA_PEDIDO.test(path)
  const delAcopio = path === RUTAS.acopio || path.startsWith(`${RUTAS.acopio}/`)
  const delDistribuidor = path === RUTAS.distribuidor || path.startsWith(`${RUTAS.distribuidor}/`)
  const delAdmin = path === RUTAS.admin || path.startsWith(`${RUTAS.admin}/`)
  const protegida = path === RUTAS.inicio || delProductor || delComprador || delAcopio || delDistribuidor || delAdmin

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
  // CCPIP-111 (SA-08): el superadministrador no entra a tiendas, pedidos ni stock; ve por que, en vez de
  // volver al inicio sin explicacion. Los demas roles siguen yendo a /inicio.
  let restringida = null
  if (user?.role === 'SUPER_ADMIN') {
    if (delProductor) restringida = 'tiendas'
    else if (delComprador) restringida = 'pedidos'
    else if (delAcopio) restringida = 'stock'
  }

  // Reglas de acceso de cada ruta. El servidor valida igual (CCPIP-151); esto solo evita pantallas vacias.
  useEffect(() => {
    if (cargando) return
    if (sesion && (path === RUTAS.ingresar || path === RUTAS.registro)) navigate(volver, { replace: true })
    else if (!sesion && protegida) navigate(`${RUTAS.ingresar}?volver=${encodeURIComponent(path + search)}`, { replace: true })
    else if (restringida) return
    else if (sesion && delProductor && user.role !== 'PRODUCTOR') navigate(RUTAS.inicio, { replace: true })
    else if (sesion && delComprador && user.role !== 'CONSUMIDOR') navigate(RUTAS.inicio, { replace: true })
    else if (sesion && delAcopio && user.role !== 'ADMIN_PUNTO') navigate(RUTAS.inicio, { replace: true })
    else if (sesion && delDistribuidor && user.role !== 'DISTRIBUIDOR') navigate(RUTAS.inicio, { replace: true })
    else if (sesion && delAdmin && user.role === 'SUB_ADMIN' && !puedeVerAdmin(user, path)) navigate(RUTAS.adminAutorizaciones, { replace: true })
    else if (sesion && delAdmin && user.role === 'SUPER_ADMIN' && !puedeVerAdmin(user, path)) navigate(RUTAS.admin, { replace: true })
    else if (sesion && delAdmin && !puedeVerAdmin(user, path)) navigate(RUTAS.inicio, { replace: true })
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
    terminarSesion({ tono: 'exito', titulo: t('app.sesionCerrada.titulo'), descripcion: t('app.sesionCerrada.descripcion') })
  }, [terminarSesion, t])

  // CCPIP-113: Mis datos cambia el nombre de la sesion sin volver a pedirla
  const actualizarUsuario = useCallback((cambios) => setSesion((s) => (s ? { ...s, user: { ...s.user, ...cambios } } : s)), [])
  const contexto = useMemo(() => ({ user, cerrarSesion: handleCerrarSesion, cerrando, actualizarUsuario }), [user, handleCerrarSesion, cerrando, actualizarUsuario])

  if (cargando) return <Cargando />

  let pagina
  if (path === RUTAS.ingresar || path === RUTAS.registro) {
    pagina = sesion ? null : (
      <IngresarPage
        modo={path === RUTAS.registro ? 'registro' : 'ingresar'}
        search={search}
        correoInicial={correoConocido}
        onCorreoConocido={setCorreoConocido}
        aviso={aviso}
        onCerrarAviso={() => setAviso(null)}
        onIngreso={(nueva) => {
          setAviso(null)
          void aplicarIdiomaDeSesion(nueva?.user)
          setSesion(nueva)
        }}
      />
    )
  } else if (path === RUTAS.recuperarContrasena) {
    pagina = <RecuperarContrasenaPage correoInicial={correoConocido} />
  } else if (path === RUTAS.restablecerContrasena) {
    pagina = <RestablecerContrasenaPage search={search} onRestablecida={setCorreoConocido} />
  } else if (path === RUTAS.verificarCorreo) {
    pagina = <VerificarCorreoPage search={search} onVerificado={setCorreoConocido} />
  } else if (protegida && !sesion) {
    pagina = null
  } else if (path === RUTAS.inicio) {
    pagina = (
      <LayoutTienda>
        <CuentaPage user={user} minutosInactividad={Math.round(sesion.expiresInSeconds / 60)} />
      </LayoutTienda>
    )
  } else if (restringida) {
    pagina = <AccesoRestringidoPage path={path} seccion={restringida} />
  } else if (delAdmin) {
    // Tablero (SA-01), comision y parametros (SA-02), comision por pedido y autorizaciones (S-01, S-02); los otros modulos llegan con sus historias
    if (!puedeVerAdmin(user, path)) pagina = null
    else if (path === RUTAS.admin) pagina = <TableroPage path={path} />
    else if (path === RUTAS.adminPermisos) pagina = <PermisosPage path={path} />
    else if (path === RUTAS.adminComision) pagina = <ComisionPage path={path} />
    else if (path === RUTAS.adminComisiones) pagina = <ComisionesPage path={path} />
    else if (path === RUTAS.adminReportes) pagina = <ReportesPage path={path} />
    else if (path === RUTAS.adminParametros) pagina = <ParametrosPage path={path} />
    else if (path === RUTAS.adminMotivosMerma) pagina = <MotivosMermaPage path={path} />
    else if (path === RUTAS.adminAutorizaciones) pagina = <AutorizacionesPage path={path} />
    else if (path === RUTAS.adminCatalogo) pagina = <CatalogoMaestroPage path={path} search={search} />
    else if (path === RUTAS.adminExistencias) pagina = <ExistenciasPage path={path} />
    else if (RUTA_EXPEDIENTE.test(path)) pagina = <ExpedienteAutorizacionPage path={RUTAS.adminAutorizaciones} id={path.match(RUTA_EXPEDIENTE)[1]} />
    else if (path === RUTAS.adminModeracion) pagina = <ModeracionPage path={path} />
    else if (RUTA_MODERACION.test(path)) pagina = <ExpedienteModeracionPage path={RUTAS.adminModeracion} id={path.match(RUTA_MODERACION)[1]} />
    else if (path === RUTAS.adminModeracionVideos) pagina = <ModeracionPage path={RUTAS.adminModeracion} videos />
    else if (RUTA_VIDEO.test(path)) pagina = <ExpedienteVideoPage path={RUTAS.adminModeracion} id={path.match(RUTA_VIDEO)[1]} />
    else if (path === RUTAS.adminProductores) pagina = <ProductoresPage path={path} />
    else if (path === RUTAS.adminCertificados) pagina = <CertificadosPage path={path} />
    else if (RUTA_FICHA_PRODUCTOR.test(path)) pagina = <FichaProductorPage path={RUTAS.adminProductores} id={path.match(RUTA_FICHA_PRODUCTOR)[1]} />
    else if (path === RUTAS.adminDenuncias) pagina = <DenunciasAdminPage path={path} />
    else if (path === RUTAS.adminMiHistorial) pagina = <MiHistorialPage path={path} />
    else if (RUTA_DENUNCIAS_PRODUCTOR.test(path)) pagina = <ExpedienteDenunciasPage path={RUTAS.adminDenuncias} productorId={path.match(RUTA_DENUNCIAS_PRODUCTOR)[1]} />
    else if (path === RUTAS.adminCuentas) pagina = <CuentasPage path={path} />
    else if (path === RUTAS.adminCuentaNueva) pagina = <NuevaCuentaPage path={RUTAS.adminCuentas} />
    else if (RUTA_CUENTA.test(path)) pagina = <DetalleCuentaPage path={RUTAS.adminCuentas} id={path.match(RUTA_CUENTA)[1]} search={search} />
    else {
      navigate(RUTAS.admin, { replace: true })
      pagina = null
    }
  } else if (delProductor) {
    const editor = path.match(RUTA_EDITOR)
    if (user.role !== 'PRODUCTOR') pagina = null
    else if (path === RUTAS.productor) pagina = <PanelPage path={path} />
    else if (path === RUTAS.productorTienda) pagina = <TiendaEditorPage path={path} />
    else if (path === RUTAS.productorProductos) pagina = <ProductosPage path={path} search={search} />
    else if (path === RUTAS.productorAlmacen) pagina = <AlmacenPage path={path} />
    else if (path === RUTAS.productorLotes) pagina = <LotesPage path={path} search={search} />
    else if (path === RUTAS.productorCuenta) pagina = <CuentaBancariaPage path={path} />
    else if (path === RUTAS.productorPedidos) pagina = <PedidosProductorPage path={path} />
    else if (path === RUTAS.productorLiquidaciones) pagina = <LiquidacionesPage path={path} />
    else if (path === RUTAS.productorDenuncias) pagina = <DenunciasProductorPage path={path} />
    else if (path === RUTAS.productorTransferencias) pagina = <TransferenciasPage key={search} path={path} search={search} />
    else if (path === RUTAS.productorAlertas) pagina = <AlertasProductorPage path={path} />
    else if (path === RUTAS.productorMerma) pagina = <MermaProductorPage path={path} />
    else if (path === RUTAS.productorReportes) pagina = <ReportesProductorPage path={path} />
    else if (path === RUTAS.productorTransferenciaNueva) pagina = <NuevaTransferenciaPage />
    else if (RUTA_EVIDENCIAS.test(path)) pagina = <EvidenciasPage path={RUTAS.productorProductos} productoId={path.match(RUTA_EVIDENCIAS)[1]} />
    else if (RUTA_PREMIOS.test(path)) pagina = <PremiosPage key={path} productoId={path.match(RUTA_PREMIOS)[1]} />
    else if (editor) {
      // Sin key por producto: al subir la primera foto el borrador nace y la URL pasa de /nuevo a
      // /:id; si el editor se montara de nuevo, perderia las fotos que se estan subiendo
      pagina = <EditorProductoPage path={RUTAS.productorProductos} id={editor[1] === 'nuevo' ? null : editor[1]} search={search} />
    }
    else pagina = <PanelPage path={RUTAS.productor} />
  } else if (delComprador) {
    if (user.role !== 'CONSUMIDOR') pagina = null
    else if (path === RUTAS.compra) pagina = <CompraPage />
    else if (path === RUTAS.misPedidos) pagina = <MisPedidosPage />
    else if (path === RUTAS.misDatos) pagina = <MisDatosPage />
    else if (path === RUTAS.misDirecciones) pagina = <MisDireccionesPage />
    else if (RUTA_PEDIDO.test(path)) pagina = <PedidoPage id={path.match(RUTA_PEDIDO)[1]} />
    else pagina = <CarritoPage />
  } else if (delAcopio) {
    // Por ahora el punto tiene Pedidos (A-05, CCPIP-159), Movimientos (A-03, CCPIP-158), Inventario (A-02, CCPIP-162)
    // y Transferencias (CCPIP-278)
    if (user.role !== 'ADMIN_PUNTO') pagina = null
    else if (path === RUTAS.acopioPedidos) pagina = <PedidosPage path={path} />
    else if (path === RUTAS.acopioMovimientos) pagina = <MovimientosPage path={path} />
    else if (path === RUTAS.acopioInventario) pagina = <InventarioPage path={path} />
    else if (path === RUTAS.acopioTransferencias) pagina = <TransferenciasPuntoPage path={path} />
    else if (path === RUTAS.acopioAlertas) pagina = <AlertasPuntoPage path={path} />
    else if (path === RUTAS.acopioMerma) pagina = <MermaPuntoPage path={path} />
    else if (path === RUTAS.acopioReportes) pagina = <ReportesPuntoPage path={path} />
    else {
      navigate(RUTAS.acopioPedidos, { replace: true })
      pagina = null
    }
  } else if (delDistribuidor) {
    // Por ahora el distribuidor tiene Pedidos disponibles (DW-01, CCPIP-107) y Mis entregas (DW-03, CCPIP-317);
    // el mapa y el historial llegan con CCPIP-110 y CCPIP-319
    if (user.role !== 'DISTRIBUIDOR') pagina = null
    else if (path === RUTAS.distribuidorPedidos) pagina = <PedidosDisponiblesPage path={path} />
    else if (path === RUTAS.distribuidorEntregas) pagina = <MisEntregasPage path={path} />
    else {
      navigate(RUTAS.distribuidorPedidos, { replace: true })
      pagina = null
    }
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

  // CCPIP-166: las paginas del catalogo muestran contenido del servidor en el idioma elegido. Al
  // cambiar de idioma se vuelven a montar para pedirlo otra vez; las demas conservan lo escrito.
  const delCatalogo = path === RUTAS.catalogo || path === RUTAS.buscar || path === RUTAS.comunidades
    || RUTA_PRODUCTO.test(path) || RUTA_TIENDA.test(path)
  return (
    <SesionContexto.Provider value={contexto}>
      <Fragment key={delCatalogo ? i18n.resolvedLanguage : 'pagina'}>{pagina}</Fragment>
    </SesionContexto.Provider>
  )
}
