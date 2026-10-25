import { useTranslation } from 'react-i18next'
import EstadoVacio from '../../../components/EstadoVacio.jsx'
import { capital, fecha, mesAnio } from '../../../formato.js'

/** Nombre del periodo: el mes, la semana desde su lunes (recortada al rango) o el dia. */
export function nombrePeriodo(p, agrupacion, t) {
  if (agrupacion === 'MES') return capital(mesAnio(p.inicio))
  if (agrupacion === 'SEMANA') return t('acopio.reportes.semana', { inicio: fecha(p.inicio), fin: fecha(p.fin) })
  return fecha(p.inicio)
}

/**
 * Una tabla del reporte. `columnas`: [{ clave, etiqueta, valor(fila), periodo, texto, fuerte, barra }]. El
 * periodo tiene ancho fijo; las columnas de texto (producto, productor) se estiran; las de cifras van a la
 * derecha. `barra` es una columna decorativa con la barra apilada del periodo (las cifras estan en la fila).
 */
export function TablaReporte({ id, titulo, columnas, filas, clave, vacio, nota, estrecha }) {
  const { t } = useTranslation()
  const clase = (c) => {
    if (c.periodo) return 'tabla__celda reportes-acopio__periodo'
    if (c.texto) return 'tabla__celda reportes-acopio__texto'
    if (c.barra) return 'tabla__celda tabla__celda--flex reportes-acopio__barra-celda'
    return `tabla__celda reportes-acopio__num${estrecha ? ' reportes-acopio__num--estrecha' : ''}`
  }
  return (
    <section className="productor__seccion" aria-labelledby={id}>
      <h2 id={id} className="heading-h3">{titulo}</h2>
      {filas.length === 0 ? (
        <EstadoVacio titulo={vacio} descripcion={t('acopio.reportes.vacioTexto')} />
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
 * Barra apilada del periodo, a escala del periodo mayor: marca y acento, como los reportes de la
 * plataforma (SA-05). Decorativa: las cifras estan en la fila.
 */
export function Barra({ partes, mayor }) {
  if (!mayor) return null
  return (
    <span className="reportes-acopio__barra" aria-hidden="true">
      {partes.map((valor, i) => (
        <span key={i} className={`reportes-acopio__barra-${i}`} style={{ width: `${(valor / mayor) * 100}%` }} />
      ))}
    </span>
  )
}
