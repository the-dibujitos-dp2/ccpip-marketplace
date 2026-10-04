import { useEffect, useRef, useState } from 'react'
import iconoBuscar from '../../assets/icons/buscar.svg'
import iconoUsuario from '../../assets/icons/usuario.svg'
import { obtenerCatalogos } from '../../api/catalogo.js'
import { navigate, RUTAS } from '../../router.js'
import BarraConfianza from '../BarraConfianza.jsx'
import Enlace from '../Enlace.jsx'
import Icono from '../Icono.jsx'
import Logotipo from '../Logotipo.jsx'
import './CabeceraTienda.css'

/**
 * Cabecera web de la tienda (Figma 66:459): barra de confianza, busqueda protagonista,
 * navegacion y cuenta. Con sesion, el menu de cuenta siempre ofrece "Cerrar sesion" (CCPIP-27).
 * Carrito, favoritos e idioma aparecen cuando existan sus historias.
 */
export default function CabeceraTienda({ user, onCerrarSesion, cerrando, termino = '' }) {
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

  const panel = user?.role === 'PRODUCTOR' ? RUTAS.productor : RUTAS.inicio

  return (
    <header className="cabecera-tienda">
      <BarraConfianza />
      <div className="cabecera-tienda__principal contenedor">
        <Enlace to={RUTAS.catalogo} className="cabecera-tienda__marca" aria-label="Mercado CCPIP, ir al inicio">
          <Logotipo />
        </Enlace>
        <form className="cabecera-tienda__busqueda" role="search" onSubmit={buscar}>
          <Icono src={iconoBuscar} tamano={20} />
          <label className="visually-hidden" htmlFor="busqueda">Buscar productos</label>
          <input
            id="busqueda"
            type="search"
            placeholder="Busca cacao, café, una comunidad…"
            value={busqueda}
            maxLength={100}
            onChange={(e) => setBusqueda(e.target.value)}
          />
        </form>
        <nav className="cabecera-tienda__enlaces" aria-label="Principal">
          <Enlace to={RUTAS.buscar}>Explorar</Enlace>
          <Enlace to={RUTAS.comunidades}>Comunidades</Enlace>
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
                Mi cuenta
              </button>
              {menu && (
                <div className="cabecera-tienda__menu" role="menu">
                  <p className="cabecera-tienda__menu-nombre">{user.fullName}</p>
                  <p className="caption">{user.roleName}</p>
                  <Enlace to={panel} role="menuitem" onClick={() => setMenu(false)}>
                    {user.role === 'PRODUCTOR' ? 'Panel del productor' : 'Mi cuenta'}
                  </Enlace>
                  <button type="button" role="menuitem" onClick={onCerrarSesion} disabled={cerrando}>
                    {cerrando ? 'Cerrando…' : 'Cerrar sesión'}
                  </button>
                </div>
              )}
            </>
          ) : (
            <Enlace to={RUTAS.ingresar} className="cabecera-tienda__boton-cuenta">
              <Icono src={iconoUsuario} tamano={22} />
              Ingresar
            </Enlace>
          )}
        </div>
      </div>
      {lineas.length > 0 && (
        <nav className="cabecera-tienda__lineas" aria-label="Líneas de producto">
          <div className="contenedor">
            {lineas.map((l) => (
              <Enlace key={l.codigo} to={`${RUTAS.buscar}?linea=${l.codigo}`}>
                {l.nombre}
              </Enlace>
            ))}
            <Enlace to={`${RUTAS.buscar}?orden=novedades`}>Novedades</Enlace>
          </div>
        </nav>
      )}
    </header>
  )
}
