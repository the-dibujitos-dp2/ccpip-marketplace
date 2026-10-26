import { useTranslation } from 'react-i18next'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoCamion from '../../assets/icons/camion.svg'
import iconoSalir from '../../assets/icons/salir.svg'
import iconoUsuario from '../../assets/icons/usuario.svg'
import Avatar from '../../components/Avatar.jsx'
import Enlace from '../../components/Enlace.jsx'
import Icono from '../../components/Icono.jsx'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import './MisPedidosPage.css'

/** Solo lo que ya existe del menu de CW-09/CW-13: intereses, puntos y favoritos llegan con sus historias. */
const SECCIONES = [
  { clave: 'pedidos', to: RUTAS.misPedidos, icono: iconoCaja, texto: 'misPedidos.titulo' },
  { clave: 'datos', to: RUTAS.misDatos, icono: iconoUsuario, texto: 'misDatos.titulo' },
  // CCPIP-393: las direcciones de entrega guardadas
  { clave: 'direcciones', to: RUTAS.misDirecciones, icono: iconoCamion, texto: 'misDirecciones.titulo' },
]

/** El menu lateral de "Mi cuenta" del consumidor. `activo`: la seccion abierta. */
export default function LateralCuenta({ activo }) {
  const { t } = useTranslation()
  const { user, cerrarSesion, cerrando } = useSesion()
  return (
    <aside className="mis-pedidos__lateral">
      <div className="mis-pedidos__usuario">
        <Avatar nombre={user.fullName} />
        <span className="label-m">{user.fullName}</span>
      </div>
      <nav aria-label={t('misPedidos.miCuenta')}>
        {SECCIONES.map((s) => (
          <Enlace
            key={s.clave}
            to={s.to}
            className={`mis-pedidos__nav${s.clave === activo ? ' mis-pedidos__nav--activo' : ''}`}
            aria-current={s.clave === activo ? 'page' : undefined}
          >
            <Icono src={s.icono} tamano={20} />
            {t(s.texto)}
          </Enlace>
        ))}
        <button type="button" className="mis-pedidos__nav" onClick={cerrarSesion} disabled={cerrando}>
          <Icono src={iconoSalir} tamano={20} />
          {cerrando ? t('cabecera.cerrando') : t('cabecera.cerrarSesion')}
        </button>
      </nav>
    </aside>
  )
}
