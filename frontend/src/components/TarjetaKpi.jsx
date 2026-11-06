import { useTranslation } from 'react-i18next'
import iconoChevron from '../assets/icons/chevron-abajo.svg'
import { claseSigno } from '../formato.js'
import Enlace from './Enlace.jsx'
import Icono from './Icono.jsx'
import './TarjetaKpi.css'

/**
 * Tarjeta KPI del panel (PW-01, SA-01). `tono` del detalle: exito (por defecto), error, neutro o aviso
 * (CCPIP-426: amarillo de advertencia, tambien en el icono).
 *
 * CCPIP-456: por defecto es informativa y no aparenta poder pulsarse (plana, sin sombra ni cursor). Con
 * `destino` (ruta interna) toda la tarjeta lleva ahi: se eleva al pasar, tiene el foco visible y dice a
 * donde va (`accion`, "Ver detalle" si no se indica).
 */
export default function TarjetaKpi({ titulo, valor, detalle, icono, tono = 'exito', destino, accion }) {
  const { t } = useTranslation()
  return (
    <section className={`tarjeta-kpi tarjeta-kpi--${tono}${destino ? ' tarjeta-kpi--pulsable' : ''}`} aria-label={titulo}>
      <div className="tarjeta-kpi__encabezado">
        <p className="label-m">{titulo}</p>
        {icono && (
          <span className="tarjeta-kpi__icono">
            <Icono src={icono} tamano={18} />
          </span>
        )}
      </div>
      <p className={`tarjeta-kpi__valor${claseSigno(valor)}`}>{valor}</p>
      {detalle && <p className={`tarjeta-kpi__detalle tarjeta-kpi__detalle--${tono}`}>{detalle}</p>}
      {destino && (
        <Enlace to={destino} className="tarjeta-kpi__enlace" aria-label={`${accion ?? t('kpi.verDetalle')}: ${titulo}`}>
          {accion ?? t('kpi.verDetalle')}
          <Icono src={iconoChevron} tamano={16} className="tarjeta-kpi__flecha" />
        </Enlace>
      )}
    </section>
  )
}
