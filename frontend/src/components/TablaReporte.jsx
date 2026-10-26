import { useTranslation } from 'react-i18next'
import { capital, fecha, mesAnio } from '../formato.js'
import EstadoVacio from './EstadoVacio.jsx'
import TarjetaKpi from './TarjetaKpi.jsx'
import './TablaReporte.css'

/**
 * Una tabla de reporte (punto de acopio, superadministrador). `columnas`: [{ clave, etiqueta, valor(fila),
 * periodo, texto, fuerte, barra }]. El periodo tiene ancho fijo; las columnas de texto (producto, productor)
 * se estiran; las de cifras van a la derecha. `barra` es una columna decorativa con la barra apilada de la
 * fila (las cifras estan en la fila). Sin filas muestra el estado vacio `vacio` con `vacioTexto`.
 */
export default function TablaReporte({ id, titulo, columnas, filas, clave, vacio, vacioTexto, nota, estrecha }) {
  const clase = (c) => {
    if (c.periodo) return 'tabla__celda tabla-reporte__periodo'
    if (c.texto) return 'tabla__celda tabla-reporte__texto'
    if (c.barra) return 'tabla__celda tabla__celda--flex tabla-reporte__barra-celda'
    return `tabla__celda tabla-reporte__num${estrecha ? ' tabla-reporte__num--estrecha' : ''}`
  }
  return (
    <section className="productor__seccion" aria-labelledby={id}>
      <h2 id={id} className="heading-h3">{titulo}</h2>
      {filas.length === 0 ? (
        <EstadoVacio titulo={vacio} descripcion={vacioTexto} />
      ) : (
        <div className="tabla" role="table" aria-labelledby={id}>
          <div className="tabla__fila tabla__fila--encabezado" role="row">
            {columnas.map((c) => (
              <span key={c.clave} className={clase(c)} role="columnheader">{c.etiqueta}</span>
            ))}
          </div>
          {filas.map((f) => (
            <div key={clave(f)} className="tabla__fila" role="row">
              {columnas.map((c) => (
                <span key={c.clave} className={`${clase(c)}${c.fuerte || c.periodo ? ' label-m' : ''}`} role="cell">{c.valor(f)}</span>
              ))}
            </div>
          ))}
        </div>
      )}
      {nota && <p className="caption">{nota}</p>}
    </section>
  )
}

/**
 * Nombre de un periodo de reporte: el mes, la semana desde su lunes (recortada al rango, con el texto
 * `semana`) o el dia.
 */
export function nombrePeriodo(p, agrupacion, t, semana) {
  if (agrupacion === 'MES') return capital(mesAnio(p.inicio))
  if (agrupacion === 'SEMANA') return t(semana, { inicio: fecha(p.inicio), fin: fecha(p.fin) })
  return fecha(p.inicio)
}

/**
 * Columnas de cifras de una tabla de reporte: `fabricaColumnas(t, prefijo)(formato, opciones)(clave, mas)` es
 * la columna `clave`, con el encabezado `{prefijo}.{clave}` y el valor `formato(fila[clave])`.
 */
export const fabricaColumnas = (t, prefijo) => (formato, opciones = {}) => (clave, mas = {}) =>
  ({ clave, etiqueta: t(`${prefijo}.${clave}`), valor: (f) => formato(f[clave]), ...opciones, ...mas })

/**
 * Las tarjetas de un reporte. Cada una es `{ clave, valor, icono, tono, vars, detalle }`: el titulo es
 * `{textos}.{clave}` y el detalle `{textos}.{detalle}` (por defecto `{clave}Detalle`), con `vars`.
 */
export function Kpis({ textos, tarjetas }) {
  const { t } = useTranslation()
  return (
    <div className="productor__kpis">
      {tarjetas.map(({ clave, valor, icono, tono = 'neutro', vars, detalle = `${clave}Detalle` }) => (
        <TarjetaKpi key={clave} titulo={t(`${textos}.${clave}`)} valor={valor} icono={icono} tono={tono}
          detalle={t(`${textos}.${detalle}`, vars)} />
      ))}
    </div>
  )
}

/**
 * Barra apilada de una fila, a escala de la fila mayor: marca y acento, como los reportes de la plataforma
 * (SA-05). Decorativa: las cifras estan en la fila.
 */
export function Barra({ partes, mayor }) {
  if (!mayor) return null
  return (
    <span className="tabla-reporte__barra" aria-hidden="true">
      {partes.map((valor, i) => (
        <span key={i} className={`tabla-reporte__barra-${i}`} style={{ width: `${(valor / mayor) * 100}%` }} />
      ))}
    </span>
  )
}
