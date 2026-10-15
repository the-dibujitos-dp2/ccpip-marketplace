import { useTranslation } from 'react-i18next'
import iconoCandado from '../../assets/icons/candado.svg'
import iconoProhibido from '../../assets/icons/prohibido.svg'
import iconoTablero from '../../assets/icons/tablero.svg'
import Boton from '../../components/Boton.jsx'
import Icono from '../../components/Icono.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { RUTAS } from '../../router.js'
import '../productor/productor.css'
import './admin.css'

const RESTRINGIDOS = ['tiendas', 'pedidos', 'stock']

/**
 * SA-08 (CCPIP-111): el superadministrador abrio una pantalla de productor, de punto de acopio o de
 * compra. Esos datos son de sus responsables (RNF-04); el servidor tambien le responde 403.
 * `seccion`: tiendas | pedidos | stock, lo que intento abrir.
 */
export default function AccesoRestringidoPage({ path, seccion }) {
  const { t } = useTranslation()
  return (
    <LayoutAdmin path={path} migas={t(`admin.restringido.seccion.${seccion}`)} titulo={t('admin.restringido.titulo')}>
      <section className="tarjeta-panel restringido" aria-labelledby="titulo-restringido">
        <span className="restringido__icono">
          <Icono src={iconoCandado} tamano={32} />
        </span>
        <h2 id="titulo-restringido" className="heading-h2 restringido__titulo">{t('admin.restringido.encabezado')}</h2>
        <p className="body-m restringido__texto">{t('admin.restringido.texto')}</p>
        <ul className="restringido__chips" aria-label={t('admin.restringido.fueraDeTuRol')}>
          {RESTRINGIDOS.map((clave) => (
            <li key={clave} className="restringido__chip">
              <Icono src={iconoProhibido} tamano={14} />
              {t(`admin.restringido.seccion.${clave}`)}
            </li>
          ))}
        </ul>
        <Boton tamano="M" icono={iconoTablero} to={RUTAS.admin}>
          {t('admin.restringido.volver')}
        </Boton>
      </section>
    </LayoutAdmin>
  )
}
