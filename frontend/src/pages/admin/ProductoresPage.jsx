import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarProductores } from '../../api/autorizaciones.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import Paginacion from '../../components/Paginacion.jsx'
import '../productor/productor.css'
import './admin.css'

/** Estado de la tienda y de la venta de un productor (CCPIP-291, CCPIP-330, CCPIP-85). */
export function BadgesProductor({ productor }) {
  const { t } = useTranslation()
  return (
    <>
      {productor.tiendaHabilitada
        ? <Badge tono="exito">{t('productores.tienda.habilitada')}</Badge>
        : <Badge tono="aviso">{t('productores.tienda.sinHabilitar')}</Badge>}
      {productor.ventaRevocada && <Badge tono="error">{t('productores.venta.revocada')}</Badge>}
      {productor.ventaBloqueada && <Badge tono="error">{t('productores.venta.bloqueada')}</Badge>}
      {!productor.ventaRevocada && !productor.ventaBloqueada && <Badge tono="neutro">{t('productores.venta.vigente')}</Badge>}
    </>
  )
}

/**
 * CCPIP-292 (RF-148): productores incorporados por la Camara y los productos que cada uno puede vender.
 * Desde la ficha el sub administrador habilita la tienda (CCPIP-291) o revoca la venta (CCPIP-330).
 */
export default function ProductoresPage({ path }) {
  const { t } = useTranslation()
  const [productores, setProductores] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    listarProductores().then(setProductores).catch(setError)
  }, [])

  const paginas = usePaginacionLocal(productores ?? [])

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.productores')} titulo={t('productores.titulo')}>
      <p className="body-m">{t('productores.intro')}</p>
      {error && <Alerta tono="error" titulo={mensajeError(error, 'productores.error')} />}
      {!productores && !error && <Cargando />}
      {productores && productores.length === 0 && <EstadoVacio titulo={t('productores.vacio')} />}
      {productores && productores.length > 0 && (
        <div className="tabla" role="table" aria-label={t('productores.titulo')}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('autorizaciones.col.organizacion')}</span>
            <span className="tabla__celda productores__productos" role="columnheader">{t('productores.col.productos')}</span>
            <span className="tabla__celda productores__estado" role="columnheader">{t('productores.col.estado')}</span>
            <span className="tabla__celda autorizaciones__accion" role="columnheader"><span className="visually-hidden">{t('autorizaciones.col.accion')}</span></span>
          </div>
          {paginas.filas.map((p) => (
            <div key={p.id} className="tabla__fila" role="row">
              <span className="tabla__celda tabla__celda--flex" role="cell">
                <span className="label-m">{p.organizacion}</span>
                <span className="caption autorizaciones__linea">{[p.pueblo, p.region].filter(Boolean).join(' · ')}</span>
              </span>
              <span className="tabla__celda productores__productos" role="cell">
                <span>{t('productores.autorizados', { count: p.autorizados, total: p.productos })}</span>
                {p.pendientes > 0 && <Badge tono="info">{t('productores.pendientes', { count: p.pendientes })}</Badge>}
              </span>
              <span className="tabla__celda productores__estado" role="cell"><BadgesProductor productor={p} /></span>
              <span className="tabla__celda autorizaciones__accion" role="cell">
                <Boton tipo="secundario" tamano="S" to={`${RUTAS.adminProductores}/${p.id}`} aria-label={t('productores.verDe', { organizacion: p.organizacion })}>
                  {t('autorizaciones.ver')}
                </Boton>
              </span>
            </div>
          ))}
        </div>
      )}
      {productores && <Paginacion {...paginas} />}
    </LayoutAdmin>
  )
}
