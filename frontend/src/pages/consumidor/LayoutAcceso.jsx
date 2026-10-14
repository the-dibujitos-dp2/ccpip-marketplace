import { useTranslation } from 'react-i18next'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import Enlace from '../../components/Enlace.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Logotipo from '../../components/Logotipo.jsx'
import SelectorIdioma from '../../components/SelectorIdioma.jsx'
import { RUTAS } from '../../router.js'
import './IngresarPage.css'

/**
 * Marco de las pantallas de acceso (CW-08 y CW-14): la marca a la izquierda y el formulario a
 * la derecha, con el selector de idioma arriba. `etiqueta`: id del titulo de la tarjeta.
 */
export default function LayoutAcceso({ bajada, etiqueta, children }) {
  const { t } = useTranslation()
  return (
    <div className="ingresar">
      <aside className="ingresar__marca">
        <Enlace to={RUTAS.catalogo} aria-label={t('marca.irAlInicio')} className="ingresar__logo">
          <Logotipo inverso />
        </Enlace>
        <div className="ingresar__mensaje">
          <p className="ingresar__titular">{t('ingresar.titular')}</p>
          <p className="ingresar__bajada">{bajada}</p>
          <div className="ingresar__fotos" aria-hidden="true">
            {['CACAO', 'SUPERFRUTOS', 'ACEITES', 'CAFE'].map((l) => (
              <ImagenProducto key={l} linea={l} className="ingresar__foto" />
            ))}
          </div>
        </div>
        <p className="ingresar__confianza">
          <img src={iconoEscudo} width="16" height="16" alt="" />
          {t('ingresar.confianza')}
        </p>
      </aside>

      <main className="ingresar__formulario-zona">
        <SelectorIdioma className="ingresar__idioma" />
        <section className="ingresar__tarjeta" aria-labelledby={etiqueta}>
          {children}
        </section>
      </main>
    </div>
  )
}
