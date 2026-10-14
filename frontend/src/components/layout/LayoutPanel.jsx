import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoSalir from '../../assets/icons/salir.svg'
import Avatar from '../Avatar.jsx'
import Enlace from '../Enlace.jsx'
import Icono from '../Icono.jsx'
import Logotipo from '../Logotipo.jsx'
import './LayoutProductor.css'

/**
 * Estructura web de los paneles de gestion (productor PW, punto de acopio A): sidebar de 264 px
 * con el rol, su menu y el usuario, y barra superior de 72 px con migas, titulo y `barra` a la derecha.
 * `nav`: [{ to, texto, icono, activo(path) }]
 */
export default function LayoutPanel({ path, rol, nav, user, subtitulo, migas, titulo, barra, onCerrarSesion, cerrando, children }) {
  const { t } = useTranslation()
  const [abierto, setAbierto] = useState(false)
  return (
    <div className="layout-productor">
      <aside className={`layout-productor__sidebar${abierto ? ' layout-productor__sidebar--abierto' : ''}`}>
        <div className="layout-productor__sidebar-fijo">
          <Logotipo />
          <p className="layout-productor__rol">{rol}</p>
          <nav aria-label={rol}>
            {nav.map((n) => (
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
              <span className="caption">{subtitulo ?? user.roleName}</span>
            </span>
            <button type="button" className="layout-productor__salir" onClick={onCerrarSesion} disabled={cerrando} aria-label={t('panel.cerrarSesion')}>
              <Icono src={iconoSalir} tamano={20} />
            </button>
          </div>
        </div>
      </aside>
      <div className="layout-productor__principal">
        <header className="layout-productor__barra">
          <button type="button" className="layout-productor__menu" aria-label={t('panel.abrirMenu')} aria-expanded={abierto} onClick={() => setAbierto((v) => !v)}>
            ☰
          </button>
          <div className="layout-productor__titulos">
            {migas && <p className="caption">{migas}</p>}
            <h1 className="heading-h2">{titulo}</h1>
          </div>
          {barra}
        </header>
        <main className="layout-productor__contenido">{children}</main>
      </div>
    </div>
  )
}
