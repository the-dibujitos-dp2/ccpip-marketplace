import Icono from './Icono.jsx'
import './TarjetaKpi.css'

/** Tarjeta KPI del panel (PW-01). */
export default function TarjetaKpi({ titulo, valor, detalle, icono }) {
  return (
    <section className="tarjeta-kpi" aria-label={titulo}>
      <div className="tarjeta-kpi__encabezado">
        <p className="label-m">{titulo}</p>
        {icono && (
          <span className="tarjeta-kpi__icono">
            <Icono src={icono} tamano={18} />
          </span>
        )}
      </div>
      <p className="tarjeta-kpi__valor">{valor}</p>
      {detalle && <p className="tarjeta-kpi__detalle">{detalle}</p>}
    </section>
  )
}
