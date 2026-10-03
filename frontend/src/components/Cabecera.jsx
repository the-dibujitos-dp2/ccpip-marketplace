import iconoUsuario from '../assets/icons/usuario.svg'
import BarraConfianza from './BarraConfianza.jsx'
import Boton from './Boton.jsx'
import Logotipo from './Logotipo.jsx'
import './Cabecera.css'

/** Cabecera de toda pantalla con sesion: siempre permite cerrar sesion (CCPIP-27). */
export default function Cabecera({ user, onCerrarSesion, cerrando }) {
  return (
    <header className="cabecera">
      <BarraConfianza />
      <div className="cabecera__principal">
        <Logotipo />
        <div className="cabecera__acciones">
          <div className="cabecera__usuario">
            <img src={iconoUsuario} width="22" height="22" alt="" />
            <span className="cabecera__datos">
              <span className="cabecera__nombre">{user.fullName}</span>
              <span className="caption">{user.roleName}</span>
            </span>
          </div>
          <Boton tipo="secundario" tamano="M" onClick={onCerrarSesion} disabled={cerrando}>
            {cerrando ? 'Cerrando…' : 'Cerrar sesión'}
          </Boton>
        </div>
      </div>
    </header>
  )
}
