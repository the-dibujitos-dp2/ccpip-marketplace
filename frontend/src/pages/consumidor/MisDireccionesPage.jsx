import { useTranslation } from 'react-i18next'
import { listarDirecciones } from '../../api/cuenta.js'
import { useConsulta } from '../../api/useConsulta.js'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { RUTAS } from '../../router.js'
import LateralCuenta from './LateralCuenta.jsx'
import './MisPedidosPage.css'
import './MisDireccionesPage.css'

/**
 * CW-13 · Mi cuenta › Mis direcciones. CCPIP-393 (RF-285): el comprador consulta sus direcciones de entrega
 * guardadas, la ultima primero, con su distrito, provincia y departamento. Se guardan al comprar a domicilio
 * (CCPIP-65); retirarlas (CCPIP-395) y modificarlas (CCPIP-394) llegan con sus historias, cada una en su tarjeta.
 */
export default function MisDireccionesPage() {
  const { t } = useTranslation()
  const { datos: direcciones, error, recargar } = useConsulta(listarDirecciones)

  return (
    <LayoutTienda>
      <main className="contenedor mis-pedidos">
        <h1 className="mis-pedidos__titulo">{t('misPedidos.miCuenta')}</h1>
        <div className="mis-pedidos__cuerpo">
          <LateralCuenta activo="direcciones" />
          <section className="mis-pedidos__contenido" aria-labelledby="titulo-mis-direcciones" aria-busy={!direcciones && !error}>
            <h2 id="titulo-mis-direcciones" className="mis-direcciones__titulo">{t('misDirecciones.titulo')}</h2>
            {error && (
              <EstadoVacio titulo={t('misDirecciones.error')} descripcion={t('comun.errorConexion')}>
                <Boton tamano="M" onClick={recargar}>{t('carrito.reintentar')}</Boton>
              </EstadoVacio>
            )}
            {!error && !direcciones && <p className="visually-hidden">{t('comun.cargando')}</p>}
            {direcciones?.length === 0 && (
              <EstadoVacio titulo={t('misDirecciones.vacio')} descripcion={t('misDirecciones.vacioTexto')}>
                <Boton tamano="M" to={RUTAS.buscar}>{t('misPedidos.explorar')}</Boton>
              </EstadoVacio>
            )}
            {direcciones?.length > 0 && (
              <ul className="mis-direcciones__lista" aria-labelledby="titulo-mis-direcciones">
                {direcciones.map((d) => (
                  <li key={d.id} className="mis-direcciones__tarjeta">
                    <p className="mis-direcciones__alias">{d.alias ?? t('misDirecciones.sinAlias')}</p>
                    <p className="mis-direcciones__direccion">{d.direccion}</p>
                    <p className="mis-direcciones__zona">{`${d.distrito} · ${d.provincia} · ${d.departamento}`}</p>
                    {d.referencia && <p className="mis-direcciones__referencia">{t('misDirecciones.referencia', { referencia: d.referencia })}</p>}
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </main>
    </LayoutTienda>
  )
}
