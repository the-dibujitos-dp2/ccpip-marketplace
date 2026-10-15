import Icono from './Icono.jsx'
import './TarjetaKpi.css'

/** Tarjeta KPI del panel (PW-01, SA-01). `tono` del detalle: exito (por defecto), error o neutro. */
export default function TarjetaKpi({ titulo, valor, detalle, icono, tono = 'exito' }) {
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
      {detalle && <p className={`tarjeta-kpi__detalle tarjeta-kpi__detalle--${tono}`}>{detalle}</p>}
    </section>
  )
}
