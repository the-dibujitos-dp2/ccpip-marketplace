import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoBolsa from '../../assets/icons/bolsa-marca.svg'
import iconoBuscar from '../../assets/icons/buscar.svg'
import iconoUsuario from '../../assets/icons/usuario.svg'
import { useUnidadesCarrito } from '../../api/carrito.js'
import { obtenerCatalogos } from '../../api/catalogo.js'
import { navigate, RUTAS } from '../../router.js'
import BarraConfianza from '../BarraConfianza.jsx'
import Enlace from '../Enlace.jsx'
import Giro from '../Giro.jsx'
import Icono from '../Icono.jsx'
import Logotipo from '../Logotipo.jsx'
import './CabeceraTienda.css'

/**
 * Cabecera de la tienda y del acceso (CCPIP-127, v2): barra de confianza, marca, navegacion, busqueda,
 * cuenta y carrito. Las lineas de producto se abren en un panel. Con sesion, el menu de cuenta siempre
 * ofrece "Cerrar sesion" (CCPIP-27). El carrito llega con CCPIP-155; el selector de idioma, con CCPIP-265.
 */
export default function CabeceraTienda({ user, onCerrarSesion, cerrando, termino = '' }) {
  const { t } = useTranslation()
  const [busqueda, setBusqueda] = useState(termino)
  const [lineas, setLineas] = useState([])
  const [menu, setMenu] = useState(false)
  const [lineasAbiertas, setLineasAbiertas] = useState(false)
  const [busquedaAbierta, setBusquedaAbierta] = useState(false)
  const raizRef = useRef(null)
  const menuRef = useRef(null)
  const entradaRef = useRef(null)
  const buscarRef = useRef(null)
  const lineasRef = useRef(null)

  useEffect(() => setBusqueda(termino), [termino])
  useEffect(() => {
    let vigente = true
    obtenerCatalogos()
      .then((c) => { if (vigente) setLineas(c.lineas) })
      .catch(() => { if (vigente) setLineas([]) })
    return () => { vigente = false }
  }, [])
  useEffect(() => {
    if (busquedaAbierta) entradaRef.current?.focus()
  }, [busquedaAbierta])
  useEffect(() => {
    if (!menu && !lineasAbiertas && !busquedaAbierta) return undefined
    const cerrarTodo = () => {
      setMenu(false)
      setLineasAbiertas(false)
      setBusquedaAbierta(false)
    }
    const fuera = (e) => !raizRef.current?.contains(e.target) && cerrarTodo()
    // Escape cierra lo abierto y devuelve el foco al boton que lo abrio
    const escape = (e) => {
      if (e.key !== 'Escape') return
      if (busquedaAbierta) buscarRef.current?.focus()
      else if (lineasAbiertas) lineasRef.current?.focus()
      else menuRef.current?.querySelector('button')?.focus()
      cerrarTodo()
    }
    document.addEventListener('mousedown', fuera)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', fuera)
      document.removeEventListener('keydown', escape)
    }
  }, [menu, lineasAbiertas, busquedaAbierta])

  function buscar(e) {
    e.preventDefault()
    setBusquedaAbierta(false)
    const q = busqueda.trim()
    navigate(q ? `${RUTAS.buscar}?q=${encodeURIComponent(q)}` : RUTAS.buscar)
  }

  function alternarLineas() {
    setLineasAbiertas((v) => !v)
    setBusquedaAbierta(false)
    setMenu(false)
  }

  // CCPIP-112: la cuenta del comprador es Mis pedidos (CW-09)
  let panel = RUTAS.inicio
  if (user?.role === 'PRODUCTOR') panel = RUTAS.productor
  else if (user?.role === 'CONSUMIDOR') panel = RUTAS.misPedidos
  // CCPIP-155: el carrito es del comprador; el visitante lo ve para ingresar y comprar
  const conCarrito = !user || user.role === 'CONSUMIDOR'
  const unidades = useUnidadesCarrito(user?.role === 'CONSUMIDOR')
  const nombreCarrito = unidades ? t('cabecera.carritoConUnidades', { unidades }) : t('cabecera.carrito')
  const cerrarLineas = () => setLineasAbiertas(false)

  return (
    <header className="cabecera-tienda" ref={raizRef}>
      <BarraConfianza />
      <div className="cabecera-tienda__principal contenedor">
        <Enlace to={RUTAS.catalogo} className="cabecera-tienda__marca" aria-label={t('marca.irAlInicio')}>
          <Logotipo />
        </Enlace>
        <nav className="cabecera-tienda__enlaces" aria-label={t('cabecera.navPrincipal')}>
          <Enlace to={RUTAS.buscar}>{t('cabecera.explorar')}</Enlace>
          <Enlace to={RUTAS.comunidades}>{t('cabecera.comunidades')}</Enlace>
          <button ref={lineasRef} type="button" aria-expanded={lineasAbiertas} aria-controls="lineas-mercado" onClick={alternarLineas}>
            {t('cabecera.lineas')}
          </button>
        </nav>
        <form
          className={`cabecera-tienda__busqueda${busquedaAbierta ? ' cabecera-tienda__busqueda--abierta' : ''}`}
          role="search"
          onSubmit={buscar}
        >
          <button
            ref={buscarRef}
            type="button"
            className="cabecera-tienda__buscar-toggle"
            aria-label={t('cabecera.buscarEtiqueta')}
            aria-expanded={busquedaAbierta}
            aria-controls="busqueda"
            onClick={() => {
              setBusquedaAbierta((v) => !v)
              setLineasAbiertas(false)
            }}
          >
            <Icono src={iconoBuscar} tamano={20} />
          </button>
          <label className="visually-hidden" htmlFor="busqueda">{t('cabecera.buscarEtiqueta')}</label>
          <input
            ref={entradaRef}
            id="busqueda"
            type="search"
            placeholder={t('cabecera.buscarPlaceholder')}
            value={busqueda}
            maxLength={100}
            onChange={(e) => setBusqueda(e.target.value)}
          />
        </form>
        <div className="cabecera-tienda__cuenta" ref={menuRef}>
          {user ? (
            <>
              <button
                type="button"
                className="cabecera-tienda__boton-cuenta"
                aria-expanded={menu}
                aria-haspopup="menu"
                onClick={() => {
                  setMenu((v) => !v)
                  setLineasAbiertas(false)
                }}
              >
                <Icono src={iconoUsuario} tamano={22} />
                <span className="cabecera-tienda__texto">{t('cabecera.miCuenta')}</span>
              </button>
              {menu && (
                <div className="cabecera-tienda__menu" role="menu">
                  <p className="cabecera-tienda__menu-nombre">{user.fullName}</p>
                  <p className="caption">{user.roleName}</p>
                  <Enlace to={panel} role="menuitem" onClick={() => setMenu(false)}>
                    {user.role === 'PRODUCTOR' ? t('cabecera.panelProductor') : t('cabecera.miCuenta')}
                  </Enlace>
                  <button type="button" role="menuitem" onClick={onCerrarSesion} disabled={cerrando} aria-busy={cerrando || undefined}>
                    {cerrando && <Giro tamano={16} />}
                    {cerrando ? t('cabecera.cerrando') : t('cabecera.cerrarSesion')}
                  </button>
                </div>
              )}
            </>
          ) : (
            <Enlace to={RUTAS.ingresar} className="cabecera-tienda__boton-cuenta">
              <Icono src={iconoUsuario} tamano={22} />
              <span className="cabecera-tienda__texto">{t('cabecera.ingresar')}</span>
            </Enlace>
          )}
        </div>
        {conCarrito && (
          <Enlace to={RUTAS.carrito} className="cabecera-tienda__carrito" aria-label={nombreCarrito}>
            <Icono src={iconoBolsa} tamano={21} />
            <span className="cabecera-tienda__texto" aria-hidden="true">{t('cabecera.carrito')}</span>
            {unidades > 0 && <span className="cabecera-tienda__unidades" aria-hidden="true">{unidades}</span>}
          </Enlace>
        )}
        <button
          type="button"
          className="cabecera-tienda__menu-movil"
          aria-label={t('panel.abrirMenu')}
          aria-expanded={lineasAbiertas}
          aria-controls="lineas-mercado"
          onClick={alternarLineas}
        >
          <span aria-hidden="true" />
        </button>
      </div>
      <nav id="lineas-mercado" className="cabecera-tienda__lineas" aria-label={t('cabecera.lineas')} hidden={!lineasAbiertas}>
        <div className="contenedor">
          <Enlace to={RUTAS.buscar} onClick={cerrarLineas}>{t('cabecera.explorar')}</Enlace>
          <Enlace to={RUTAS.comunidades} onClick={cerrarLineas}>{t('cabecera.comunidades')}</Enlace>
          {lineas.map((l) => (
            <Enlace key={l.codigo} to={`${RUTAS.buscar}?linea=${l.codigo}`} onClick={cerrarLineas}>
              {l.nombre}
            </Enlace>
          ))}
          <Enlace to={`${RUTAS.buscar}?orden=novedades`} onClick={cerrarLineas}>{t('cabecera.novedades')}</Enlace>
        </div>
      </nav>
    </header>
  )
}
