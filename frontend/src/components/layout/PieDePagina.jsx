import { useTranslation } from 'react-i18next'
import { RUTAS } from '../../router.js'
import Enlace from '../Enlace.jsx'
import Logotipo from '../Logotipo.jsx'
import './PieDePagina.css'

/** Pie de pagina de la tienda (CCPIP-127, v2). Solo enlaces a paginas que ya existen. */
export default function PieDePagina() {
  const { t } = useTranslation()
  const { t: te } = useTranslation('editorial')
  return (
    <footer className="pie">
      <div className="contenedor">
        <div className="pie__columnas">
          <div className="pie__marca">
            <Logotipo inverso />
            <p className="pie__descripcion">{t('pie.descripcion')}</p>
          </div>
          <nav className="pie__enlaces" aria-label={t('pie.comprar')}>
            <Enlace to={RUTAS.buscar}>{t('pie.explorarProductos')}</Enlace>
            <Enlace to={RUTAS.comunidades}>{t('pie.comunidades')}</Enlace>
            <Enlace to={`${RUTAS.buscar}?orden=novedades`}>{t('pie.novedades')}</Enlace>
          </nav>
        </div>
        <div className="pie__inferior">
          <p className="pie__legal">{t('pie.legal')}</p>
          <details className="pie__creditos">
            <summary>{te('pie.creditos')}</summary>
            <p>{te('pie.fotosDocumentales')}</p>
            <p>{te('pie.fotosReferencia')}</p>
            <p>{te('pie.tipografias')}</p>
          </details>
        </div>
      </div>
    </footer>
  )
}
