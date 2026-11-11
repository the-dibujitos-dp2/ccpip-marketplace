import { useTranslation } from 'react-i18next'
import Enlace from '../../components/Enlace.jsx'
import CabeceraTienda from '../../components/layout/CabeceraTienda.jsx'
import { useSesion } from '../../sesion.js'
import { RUTAS } from '../../router.js'
import './IngresarPage.css'
import './ConsumerEditorial.css'
import './AccesoEditorial.css'
import retrato from '../../assets/editorial/retrato.jpg'

/**
 * Marco de acceso (CW-08, CW-14): foto documental y formulario real; registro, verificacion y recuperacion intactos.
 * CCPIP-510: ocupa justo la pantalla (100vh) y sin pie de pagina; si el formulario no entra, se desplaza el formulario.
 */
export default function LayoutAcceso({ bajada, etiqueta, children }) {
  const { t } = useTranslation('accessEditorial')
  const { user, cerrarSesion, cerrando } = useSesion()
  return (
    <div className="ingresar-shell">
      <CabeceraTienda user={user} onCerrarSesion={cerrarSesion} cerrando={cerrando} />
      <div className="ingresar">
        <aside className="ingresar__marca">
          <img className="ingresar__retrato" src={retrato} alt={t('altRetrato')} />
          <div className="ingresar__mensaje">
            <p className="ingresar__titular">{t('titularFoto')}</p>
            <p className="ingresar__bajada">{t('bajadaFoto')}</p>
            <a className="ingresar__credito" href="https://www.infobae.com/peru/2024/11/27/cacao-cafe-arte-textil-y-mas-emprendedores-indigenas-ofreceran-mas-de-100-productos-libres-de-deforestacion/" target="_blank" rel="noopener noreferrer">{t('creditoFoto')}</a>
          </div>
        </aside>
        <main className="ingresar__formulario-zona">
          <div className="ingresar__contenido">
            <Enlace to={RUTAS.catalogo} className="ingresar__volver">{t('volver')}</Enlace>
            <section className="ingresar__tarjeta" aria-labelledby={etiqueta}>
              <p className="ingresar__introduccion">{bajada}</p>
              {children}
            </section>
          </div>
        </main>
      </div>
    </div>
  )
}
