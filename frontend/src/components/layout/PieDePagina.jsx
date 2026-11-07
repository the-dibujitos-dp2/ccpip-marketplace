import { useTranslation } from 'react-i18next'
import { RUTAS } from '../../router.js'
import Enlace from '../Enlace.jsx'
import MarcaEditorial from './MarcaEditorial.jsx'
import './PieDePagina.css'

/** Pie de pagina web (Figma 66:513). Solo enlaces a paginas que ya existen. */
export default function PieDePagina() {
  const { t } = useTranslation()
  return (
    <footer className="pie">
      <div className="contenedor">
        <div className="pie__columnas">
          <div className="pie__marca">
            <MarcaEditorial className="consumer-editorial-logo consumer-editorial-logo--inverse" />
            <p>{t('pie.descripcion')}</p>
          </div>
          <nav aria-label={t('pie.comprar')}>
            <p className="pie__titulo">{t('pie.comprar')}</p>
            <Enlace to={RUTAS.buscar}>{t('pie.explorarProductos')}</Enlace>
            <Enlace to={RUTAS.comunidades}>{t('pie.comunidades')}</Enlace>
            <Enlace to={`${RUTAS.buscar}?orden=novedades`}>{t('pie.novedades')}</Enlace>
          </nav>
        </div>
        <details className="consumer-editorial-credits">
          <summary>Créditos editoriales</summary>
          <p>Fotografías documentales: Infobae, “Cacao, café, arte textil y más” (27-11-2024). Referencias visuales; no identifican a productores CCPIP. Permisos para uso comercial pendientes.</p>
          <p>Tipografías DM Sans y Fraunces: Google Fonts. Marca CCPIP: logotipo publicado por Amanatari.</p>
        </details>
        <p className="pie__legal">{t('pie.legal')}</p>
      </div>
    </footer>
  )
}
