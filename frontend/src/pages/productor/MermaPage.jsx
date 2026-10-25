import { useTranslation } from 'react-i18next'
import { listarMermaProductor, urlReporteMermaProductor } from '../../api/merma.js'
import { RUTAS } from '../../router.js'
import ListaMerma from '../merma/ListaMerma.jsx'
import PaginaProductor from './PaginaProductor.jsx'

/**
 * CCPIP-75 y CCPIP-76: la merma de sus lotes, en su almacen y en los puntos, manual y automatica por
 * vencimiento. La de su almacen la marca desde Mi almacen; la de un punto, el punto.
 */
export default function MermaPage({ path }) {
  const { t } = useTranslation()
  return (
    <PaginaProductor path={path} seccion={t('productor.nav.merma')} titulo={t('merma.lista.tituloProductor')}>
      <ListaMerma
        consultar={listarMermaProductor}
        exportar={urlReporteMermaProductor}
        conUbicacion
        irTexto={t('merma.lista.comoMarcarProductor')}
        ir={{ ruta: RUTAS.productorAlmacen, texto: t('merma.lista.irAlmacen') }}
      />
    </PaginaProductor>
  )
}
