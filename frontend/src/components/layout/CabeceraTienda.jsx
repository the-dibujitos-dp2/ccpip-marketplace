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
 * Cabecera web de la tienda (Figma 66:459): barra de confianza, busqueda protagonista,
 * navegacion y cuenta. Con sesion, el menu de cuenta siempre ofrece "Cerrar sesion" (CCPIP-27).
 * El boton "Carrito · N" llega con CCPIP-155; el selector de idioma, con CCPIP-265; favoritos, con su historia.
 */
export default function CabeceraTienda({ user, onCerrarSesion, cerrando, termino = '' }) {
  const { t } = useTranslation()
  const [busqueda, setBusqueda] = useState(termino)
  const [lineas, setLineas] = useState([])
  const [menu, setMenu] = useState(false)
  const menuRef = useRef(null)

  useEffect(() => setBusqueda(termino), [termino])
  useEffect(() => {
    obtenerCatalogos().then((c) => setLineas(c.lineas)).catch(() => setLineas([]))
  }, [])
  useEffect(() => {
    if (!menu) return undefined
    const cerrar = (e) => !menuRef.current?.contains(e.target) && setMenu(false)
    const escape = (e) => e.key === 'Escape' && setMenu(false)
    document.addEventListener('mousedown', cerrar)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', cerrar)
      document.removeEventListener('keydown', escape)
    }
  }, [menu])

  function buscar(e) {
    e.preventDefault()
    const q = busqueda.trim()
    navigate(q ? `${RUTAS.buscar}?q=${encodeURIComponent(q)}` : RUTAS.buscar)
  }

  // CCPIP-112: la cuenta del comprador es Mis pedidos (CW-09)
  let panel = RUTAS.inicio
  if (user?.role === 'PRODUCTOR') panel = RUTAS.productor
  else if (user?.role === 'CONSUMIDOR') panel = RUTAS.misPedidos
  // CCPIP-155: el carrito es del comprador; el visitante lo ve para ingresar y comprar
  const conCarrito = !user || user.role === 'CONSUMIDOR'
  const unidades = useUnidadesCarrito(user?.role === 'CONSUMIDOR')

  return (
    <header className="cabecera-tienda">
      <BarraConfianza />
      <div className="cabecera-tienda__principal contenedor">
        <Enlace to={RUTAS.catalogo} className="cabecera-tienda__marca" aria-label={t('marca.irAlInicio')}>
          <Logotipo />
        </Enlace>
        <form className="cabecera-tienda__busqueda" role="search" onSubmit={buscar}>
          <Icono src={iconoBuscar} tamano={20} />
          <label className="visually-hidden" htmlFor="busqueda">{t('cabecera.buscarEtiqueta')}</label>
          <input
            id="busqueda"
            type="search"
            placeholder={t('cabecera.buscarPlaceholder')}
            value={busqueda}
            maxLength={100}
            onChange={(e) => setBusqueda(e.target.value)}
          />
        </form>
        <nav className="cabecera-tienda__enlaces" aria-label={t('cabecera.navPrincipal')}>
          <Enlace to={RUTAS.buscar}>{t('cabecera.explorar')}</Enlace>
          <Enlace to={RUTAS.comunidades}>{t('cabecera.comunidades')}</Enlace>
        </nav>
        <div className="cabecera-tienda__cuenta" ref={menuRef}>
          {user ? (
            <>
              <button
                type="button"
                className="cabecera-tienda__boton-cuenta"
                aria-expanded={menu}
                aria-haspopup="menu"
                onClick={() => setMenu((v) => !v)}
              >
                <Icono src={iconoUsuario} tamano={22} />
                {t('cabecera.miCuenta')}
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
              {t('cabecera.ingresar')}
            </Enlace>
          )}
        </div>
        {conCarrito && (
          <Enlace to={RUTAS.carrito} className="cabecera-tienda__carrito">
            <Icono src={iconoBolsa} tamano={20} />
            {unidades ? t('cabecera.carritoConUnidades', { unidades }) : t('cabecera.carrito')}
          </Enlace>
        )}
      </div>
      {lineas.length > 0 && (
        <nav className="cabecera-tienda__lineas" aria-label={t('cabecera.lineas')}>
          <div className="contenedor">
            {lineas.map((l) => (
              <Enlace key={l.codigo} to={`${RUTAS.buscar}?linea=${l.codigo}`}>
                {l.nombre}
              </Enlace>
            ))}
            <Enlace to={`${RUTAS.buscar}?orden=novedades`}>{t('cabecera.novedades')}</Enlace>
          </div>
        </nav>
      )}
    </header>
  )
}
