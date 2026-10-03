import { useEffect, useState } from 'react'
import iconoBuscar from '../assets/icons/buscar.svg'
import iconoUsuario from '../assets/icons/usuario.svg'
import { navigate, RUTAS } from '../router.js'
import BarraConfianza from './BarraConfianza.jsx'
import Boton from './Boton.jsx'
import Enlace from './Enlace.jsx'
import Logotipo from './Logotipo.jsx'
import './Cabecera.css'

/**
 * Cabecera web (Figma): busqueda protagonista y cuenta. Con sesion siempre ofrece
 * "Cerrar sesion" (CCPIP-27); sin sesion, "Ingresar".
 */
export default function Cabecera({ user, onCerrarSesion, cerrando, termino = '' }) {
  const [busqueda, setBusqueda] = useState(termino)
  useEffect(() => setBusqueda(termino), [termino])

  function buscar(e) {
    e.preventDefault()
    const q = busqueda.trim()
    navigate(q ? `${RUTAS.catalogo}?q=${encodeURIComponent(q)}` : RUTAS.catalogo)
  }

  return (
    <header className="cabecera">
      <BarraConfianza />
      <div className="cabecera__principal">
        <Enlace to={RUTAS.catalogo} className="cabecera__marca" aria-label="Mercado CCPIP, ir al catálogo">
          <Logotipo />
        </Enlace>
        <form className="cabecera__busqueda" role="search" onSubmit={buscar}>
          <img src={iconoBuscar} width="20" height="20" alt="" />
          <label className="visually-hidden" htmlFor="busqueda">Buscar productos</label>
          <input
            id="busqueda"
            type="search"
            placeholder="Busca cacao, café, aceite…"
            value={busqueda}
            maxLength={100}
            onChange={(e) => setBusqueda(e.target.value)}
          />
        </form>
        <div className="cabecera__acciones">
          {user ? (
            <>
              <Enlace to={RUTAS.inicio} className="cabecera__usuario">
                <img src={iconoUsuario} width="22" height="22" alt="" />
                <span className="cabecera__datos">
                  <span className="cabecera__nombre">{user.fullName}</span>
                  <span className="caption">{user.roleName}</span>
                </span>
              </Enlace>
              <Boton tipo="secundario" tamano="M" onClick={onCerrarSesion} disabled={cerrando}>
                {cerrando ? 'Cerrando…' : 'Cerrar sesión'}
              </Boton>
            </>
          ) : (
            <Boton tipo="secundario" tamano="M" onClick={() => navigate(RUTAS.ingresar)}>
              Ingresar
            </Boton>
          )}
        </div>
      </div>
    </header>
  )
}
