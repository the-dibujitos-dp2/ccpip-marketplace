import { useTranslation } from 'react-i18next'
import iconoMano from '../../assets/icons/mano-ayuda.svg'
import Boton from '../../components/Boton.jsx'
import { precio } from '../../formato.js'
import './OfertasMaestro.css'

/**
 * CCPIP-42 (RF-18), Figma CW-03 "También lo ofrecen": las ofertas de otros productores para el mismo
 * producto del catalogo maestro. Cada una conserva su productor, precio y disponibilidad. El stock
 * no se muestra en cantidades (CCPIP-53): solo disponible, agotado o en venta pausada.
 */
export default function OfertasMaestro({ maestro, ofertas }) {
  const { t } = useTranslation()
  const producto = maestro.nombre.charAt(0).toLowerCase() + maestro.nombre.slice(1)
  return (
    <section id="otras-ofertas" className="otras-ofertas" aria-labelledby="titulo-otras-ofertas">
      <h2 id="titulo-otras-ofertas" className="heading-h3">{t('producto.otrasOfertas.titulo', { producto })}</h2>
      <p className="body-s">{t('producto.otrasOfertas.texto')}</p>
      <ul className="otras-ofertas__lista">
        {ofertas.map((o) => (
          <li key={o.id} className="otras-ofertas__fila">
            <Oferta oferta={o} />
          </li>
        ))}
      </ul>
    </section>
  )
}

function Oferta({ oferta }) {
  const { t } = useTranslation()
  const { tienda } = oferta
  const monto = oferta.precio ?? oferta.precioMayorista
  const pausada = oferta.comprable === false
  const agotada = !pausada && !oferta.disponible
  const estado = pausada ? t('producto.otrasOfertas.pausada') : agotada ? t('producto.otrasOfertas.agotado') : t('producto.otrasOfertas.disponible')
  const ruta = `/productos/${oferta.id}`
  return (
    <>
      <span className="otras-ofertas__datos">
        <span className="label-m">{[tienda.nombre, tienda.pueblo].filter(Boolean).join(' · ')}</span>
        <span className="caption">{[monto != null && precio(monto), estado].filter(Boolean).join(' · ')}</span>
      </span>
      {/* "Me interesa" registra el interes en la ficha de esa oferta (CW-11, CCPIP-105) */}
      {agotada ? (
        <Boton tipo="secundario" tamano="S" icono={iconoMano} to={ruta} aria-label={t('producto.otrasOfertas.meInteresaAria', { tienda: tienda.nombre })}>
          {t('producto.otrasOfertas.meInteresa')}
        </Boton>
      ) : (
        <Boton tipo="terciario" tamano="S" to={ruta} aria-label={t('producto.otrasOfertas.verAria', { tienda: tienda.nombre })}>
          {t('producto.otrasOfertas.ver')}
        </Boton>
      )}
    </>
  )
}
