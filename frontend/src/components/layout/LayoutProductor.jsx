import { useTranslation } from 'react-i18next'
import iconoAviso from '../../assets/icons/alerta-aviso.svg'
import iconoAlmacen from '../../assets/icons/almacen.svg'
import iconoBillete from '../../assets/icons/billete.svg'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoEtiqueta from '../../assets/icons/etiqueta.svg'
import iconoPaquete from '../../assets/icons/paquete.svg'
import iconoTablero from '../../assets/icons/tablero.svg'
import iconoTienda from '../../assets/icons/tienda.svg'
import { navigate, RUTAS } from '../../router.js'
import Buscador from '../Buscador.jsx'
import LayoutPanel from './LayoutPanel.jsx'

/** Solo los modulos que el productor ya puede usar, en el orden del Figma (transferencias, etc. llegan con sus historias). */
const NAV = [
  { to: RUTAS.productor, clave: 'inicio', icono: iconoTablero, activo: (p) => p === RUTAS.productor },
  { to: RUTAS.productorTienda, clave: 'tienda', icono: iconoTienda, activo: (p) => p.startsWith(RUTAS.productorTienda) },
  { to: RUTAS.productorProductos, clave: 'productos', icono: iconoEtiqueta, activo: (p) => p.startsWith(RUTAS.productorProductos) },
  { to: RUTAS.productorAlmacen, clave: 'almacen', icono: iconoAlmacen, activo: (p) => p.startsWith(RUTAS.productorAlmacen) },
  { to: RUTAS.productorLotes, clave: 'lotes', icono: iconoCaja, activo: (p) => p.startsWith(RUTAS.productorLotes) },
  { to: RUTAS.productorPedidos, clave: 'pedidos', icono: iconoPaquete, activo: (p) => p.startsWith(RUTAS.productorPedidos) },
  { to: RUTAS.productorDenuncias, clave: 'denuncias', icono: iconoAviso, activo: (p) => p.startsWith(RUTAS.productorDenuncias) },
  { to: RUTAS.productorCuenta, clave: 'cuenta', icono: iconoBillete, activo: (p) => p.startsWith(RUTAS.productorCuenta) },
]

/** Estructura web del productor (PW): su menu y un buscador de sus productos en la barra superior. */
export default function LayoutProductor({ path, user, tienda, migas, titulo, acciones, onCerrarSesion, cerrando, children }) {
  const { t } = useTranslation()
  const nav = NAV.map((n) => ({ ...n, texto: t(`productor.nav.${n.clave}`) }))
  function buscar(e) {
    e.preventDefault()
    const q = new FormData(e.currentTarget).get('q').trim()
    navigate(q ? `${RUTAS.productorProductos}?q=${encodeURIComponent(q)}` : RUTAS.productorProductos)
  }
  return (
    <LayoutPanel
      path={path}
      rol={t('productor.rol')}
      nav={nav}
      user={user}
      subtitulo={tienda}
      migas={migas}
      titulo={titulo}
      onCerrarSesion={onCerrarSesion}
      cerrando={cerrando}
      barra={
        <>
          <Buscador id="buscar-productor" name="q" etiqueta={t('productor.buscarEtiqueta')} placeholder={t('productor.buscarPlaceholder')} onBuscar={buscar} className="layout-productor__buscar" />
          {acciones}
        </>
      }
    >
      {children}
    </LayoutPanel>
  )
}
