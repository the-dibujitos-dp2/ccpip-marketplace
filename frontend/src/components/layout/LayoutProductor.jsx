import { useState } from 'react'
import iconoBuscar from '../../assets/icons/buscar.svg'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoEtiqueta from '../../assets/icons/etiqueta.svg'
import iconoSalir from '../../assets/icons/salir.svg'
import iconoTablero from '../../assets/icons/tablero.svg'
import iconoTienda from '../../assets/icons/tienda.svg'
import { navigate, RUTAS } from '../../router.js'
import Avatar from '../Avatar.jsx'
import Enlace from '../Enlace.jsx'
import Icono from '../Icono.jsx'
import Logotipo from '../Logotipo.jsx'
import './LayoutProductor.css'

/** Solo los modulos que el productor ya puede usar (almacen, pedidos, etc. llegan con sus historias). */
const NAV = [
  { to: RUTAS.productor, texto: 'Inicio', icono: iconoTablero, activo: (p) => p === RUTAS.productor },
  { to: RUTAS.productorTienda, texto: 'Mi tienda', icono: iconoTienda, activo: (p) => p.startsWith(RUTAS.productorTienda) },
  { to: RUTAS.productorProductos, texto: 'Productos', icono: iconoEtiqueta, activo: (p) => p.startsWith(RUTAS.productorProductos) },
  { to: RUTAS.productorLotes, texto: 'Lotes', icono: iconoCaja, activo: (p) => p.startsWith(RUTAS.productorLotes) },
]

/** Estructura web del productor (PW): sidebar de 264 px y barra superior de 72 px. */
export default function LayoutProductor({ path, user, tienda, migas, titulo, acciones, onCerrarSesion, cerrando, children }) {
  const [abierto, setAbierto] = useState(false)

  function buscar(e) {
    e.preventDefault()
    const q = new FormData(e.currentTarget).get('q').trim()
    navigate(q ? `${RUTAS.productorProductos}?q=${encodeURIComponent(q)}` : RUTAS.productorProductos)
  }
  return (
    <div className="layout-productor">
      <aside className={`layout-productor__sidebar${abierto ? ' layout-productor__sidebar--abierto' : ''}`}>
        <div className="layout-productor__sidebar-fijo">
        <Logotipo />
        <p className="layout-productor__rol">Productor</p>
        <nav aria-label="Productor">
          {NAV.map((n) => (
            <Enlace
              key={n.to}
              to={n.to}
              className={`layout-productor__nav${n.activo(path) ? ' layout-productor__nav--activo' : ''}`}
              aria-current={n.activo(path) ? 'page' : undefined}
              onClick={() => setAbierto(false)}
            >
              <Icono src={n.icono} tamano={20} />
              {n.texto}
            </Enlace>
          ))}
        </nav>
        <div className="layout-productor__usuario">
          <Avatar nombre={user.fullName} />
          <span className="layout-productor__datos">
            <span className="label-m">{user.fullName}</span>
            <span className="caption">{tienda ?? user.roleName}</span>
          </span>
          <button type="button" className="layout-productor__salir" onClick={onCerrarSesion} disabled={cerrando} aria-label="Cerrar sesión">
            <Icono src={iconoSalir} tamano={20} />
          </button>
        </div>
        </div>
      </aside>
      <div className="layout-productor__principal">
        <header className="layout-productor__barra">
          <button type="button" className="layout-productor__menu" aria-label="Abrir menú" aria-expanded={abierto} onClick={() => setAbierto((v) => !v)}>
            ☰
          </button>
          <div className="layout-productor__titulos">
            {migas && <p className="caption">{migas}</p>}
            <h1 className="heading-h2">{titulo}</h1>
          </div>
          <form className="layout-productor__buscar" role="search" onSubmit={buscar}>
            <Icono src={iconoBuscar} tamano={20} />
            <label htmlFor="buscar-productor" className="visually-hidden">Buscar en mis productos</label>
            <input id="buscar-productor" name="q" type="search" placeholder="Buscar…" />
          </form>
          {acciones}
        </header>
        <main className="layout-productor__contenido">{children}</main>
      </div>
    </div>
  )
}
