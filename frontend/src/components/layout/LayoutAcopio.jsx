import { useState } from 'react'
import iconoMovimientos from '../../assets/icons/movimientos.svg'
import iconoSalir from '../../assets/icons/salir.svg'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import Avatar from '../Avatar.jsx'
import Enlace from '../Enlace.jsx'
import Icono from '../Icono.jsx'
import Logotipo from '../Logotipo.jsx'
// Misma estructura que el panel del productor: sidebar de 264 px y barra superior de 72 px
import './LayoutProductor.css'

/**
 * Solo los modulos que el punto ya puede usar: el tablero (A-01), inventario, merma, pedidos, etc.
 * llegan con sus historias. Mientras tanto, su inicio sigue siendo /inicio.
 */
const NAV = [
  { to: RUTAS.acopioMovimientos, texto: 'Movimientos', icono: iconoMovimientos, activo: (p) => p.startsWith(RUTAS.acopioMovimientos) },
]

/** Estructura web del administrador de punto de acopio (Figma A-01 a A-08). */
export default function LayoutAcopio({ path, punto, titulo, children }) {
  const { user, cerrarSesion, cerrando } = useSesion()
  const [abierto, setAbierto] = useState(false)
  return (
    <div className="layout-productor">
      <aside className={`layout-productor__sidebar${abierto ? ' layout-productor__sidebar--abierto' : ''}`}>
        <div className="layout-productor__sidebar-fijo">
          <Logotipo />
          <p className="layout-productor__rol">Punto de acopio</p>
          <nav aria-label="Punto de acopio">
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
              <span className="caption">{punto ?? user.roleName}</span>
            </span>
            <button type="button" className="layout-productor__salir" onClick={cerrarSesion} disabled={cerrando} aria-label="Cerrar sesión">
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
            <p className="caption">{punto ? `${punto} / ${titulo}` : titulo}</p>
            <h1 className="heading-h2">{titulo}</h1>
          </div>
        </header>
        <main className="layout-productor__contenido">{children}</main>
      </div>
    </div>
  )
}
