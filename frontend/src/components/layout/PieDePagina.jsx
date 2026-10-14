import { useTranslation } from 'react-i18next'
import { RUTAS } from '../../router.js'
import Enlace from '../Enlace.jsx'
import Logotipo from '../Logotipo.jsx'
import './PieDePagina.css'

/** Pie de pagina web (Figma 66:513). Solo enlaces a paginas que ya existen. */
export default function PieDePagina() {
  const { t } = useTranslation()
  return (
    <footer className="pie">
      <div className="contenedor">
        <div className="pie__columnas">
          <div className="pie__marca">
            <Logotipo inverso />
            <p>{t('pie.descripcion')}</p>
          </div>
          <nav aria-label={t('pie.comprar')}>
            <p className="pie__titulo">{t('pie.comprar')}</p>
            <Enlace to={RUTAS.buscar}>{t('pie.explorarProductos')}</Enlace>
            <Enlace to={RUTAS.comunidades}>{t('pie.comunidades')}</Enlace>
            <Enlace to={`${RUTAS.buscar}?orden=novedades`}>{t('pie.novedades')}</Enlace>
          </nav>
        </div>
        <p className="pie__legal">{t('pie.legal')}</p>
      </div>
    </footer>
  )
}
