import { useTranslation } from 'react-i18next'
import Pestanas from '../../components/Pestanas.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { navigate, RUTAS } from '../../router.js'
import LineasMaestras from './LineasMaestras.jsx'
import ProductosMaestros from './ProductosMaestros.jsx'
import '../productor/productor.css'
import './admin.css'

const VISTAS = ['lineas', 'productos']

/**
 * Catalogo maestro de la Camara (Figma: modulo "lineas" del superadministrador). Dos pestanas: lineas
 * (CCPIP-96, 309, 310, 311) y productos de referencia (CCPIP-351, 312, 313, 314). No es la oferta
 * comercial de los productores. La pestana va en la URL (?vista=productos) para poder volver a ella.
 */
export default function CatalogoMaestroPage({ path, search = '' }) {
  const { t } = useTranslation()
  const vista = new URLSearchParams(search).get('vista') === 'productos' ? 'productos' : 'lineas'
  const cambiar = (v) => navigate(v === 'productos' ? `${RUTAS.adminCatalogo}?vista=productos` : RUTAS.adminCatalogo, { replace: true })
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.catalogo')} titulo={t('catalogoMaestro.titulo')}>
      <p className="body-s catalogo-maestro__intro">{t('catalogoMaestro.intro')}</p>
      <Pestanas
        etiqueta={t('catalogoMaestro.pestanas')}
        valor={vista}
        onCambiar={cambiar}
        opciones={VISTAS.map((v) => ({ valor: v, etiqueta: t(`catalogoMaestro.vista.${v}`) }))}
      />
      {vista === 'lineas' ? <LineasMaestras key="lineas" /> : <ProductosMaestros key="productos" />}
      <p className="productor__nota">{t('catalogoMaestro.nota')}</p>
    </LayoutAdmin>
  )
}
