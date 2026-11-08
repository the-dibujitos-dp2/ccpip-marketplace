import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { precio, unidades } from '../../formato.js'
import './ReportesVisuales.css'

const numero = (value) => {
  if (value == null || typeof value === 'boolean' || String(value).trim() === '') return null
  return Number.isFinite(Number(value)) ? Number(value) : null
}
const color = (index) => `var(--rv-color-${index % 6 + 1})`

function useLocale(explicit) {
  const { i18n } = useTranslation()
  return explicit || ((i18n.resolvedLanguage || i18n.language || 'es').startsWith('en') ? 'en-GB' : 'es-PE')
}

function textos(locale) {
  return locale.startsWith('en') ? {
    empty: 'No data to display.', missing: 'No data', points: 'Data points',
    keyboard: 'Select a point for its exact value. Use the arrow keys to explore the chart.',
    negative: 'Negative values are shown separately; they are not parts of a whole.',
    chart: 'Interactive chart', units: 'units', series: 'Series',
  } : {
    empty: 'Sin datos para mostrar.', missing: 'Sin dato', points: 'Puntos de la serie',
    keyboard: 'Selecciona un punto para ver su valor exacto. Usa las flechas para recorrer el gráfico.',
    negative: 'Los valores negativos se muestran por separado; no son partes de un total.',
    chart: 'Gráfico interactivo', units: 'und', series: 'Series',
  }
}

function formato(value, unit, locale) {
  if (value == null) return textos(locale).missing
  if (unit === 'S/') return precio(value).replace(/\u00a0/g, ' ')
  const label = ['und', 'ud'].includes(unit) ? textos(locale).units : unit
  return `${label ? `${label} ` : ''}${unidades(value)}`
}

function useChartWidth() {
  const ref = useRef(null)
  const [width, setWidth] = useState(760)
  useEffect(() => {
    const element = ref.current
    if (!element) return undefined
    const measure = () => {
      const next = element.getBoundingClientRect().width
      if (next > 0) setWidth(Math.max(260, Math.round(next)))
    }
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  return [ref, width]
}

/** Series keep their own units; selecting a mark never changes the report filters. */
export function SerieTemporal({ titulo, series = [], unidad, onSelect, seleccionInicial = null, className = '', locale: explicitLocale }) {
  const locale = useLocale(explicitLocale)
  const copy = textos(locale)
  const uid = useId()
  const [chartRef, width] = useChartWidth()
  const [selection, setSelection] = useState(seleccionInicial)
  const normal = useMemo(() => {
    const units = new Set(series.map((serie) => serie.unidad ?? unidad ?? ''))
    if (units.size > 1) throw new Error('SerieTemporal no admite series con distinta unidad')
    return series.map((serie, index) => ({
      ...serie, color: color(index), unidad: serie.unidad ?? unidad ?? '',
      puntos: (serie.puntos ?? []).map((point) => ({ ...point, valor: numero(point.valor) })),
    }))
  }, [series, unidad])
  const labels = [...new Set(normal.flatMap((serie) => serie.puntos.map((point) => point.etiqueta)))]
  if (labels.every((label) => /^\d{4}-\d{2}-\d{2}/.test(label))) labels.sort((a, b) => {
    const first = String(a)
    const second = String(b)
    if (first === second) return 0
    return first < second ? -1 : 1
  })
  const marks = normal.flatMap((serie) => serie.puntos.filter((point) => point.valor !== null).map((point) => ({
    serieId: serie.id, serie: serie.nombre, etiqueta: point.etiqueta, valor: point.valor, unidad: serie.unidad,
  })))
  // Store identity, not an old report's numeric payload. A refresh always displays current values.
  const selected = marks.find((mark) => mark.serieId === selection?.serieId && mark.etiqueta === selection?.etiqueta)
  const select = (detail) => {
    setSelection({ serieId: detail.serieId, etiqueta: detail.etiqueta })
    onSelect?.(detail)
  }
  const navigate = (event) => {
    const current = marks.indexOf(selected)
    let next
    if (['ArrowRight', 'ArrowDown'].includes(event.key)) next = (current + 1) % marks.length
    if (['ArrowLeft', 'ArrowUp'].includes(event.key)) next = (current < 0 ? marks.length - 1 : current - 1 + marks.length) % marks.length
    if (event.key === 'Home') next = 0
    if (event.key === 'End') next = marks.length - 1
    if (next === undefined || !marks.length) return
    event.preventDefault()
    select(marks[next])
  }
  const values = marks.map((mark) => mark.valor)
  const lower = Math.min(0, ...values)
  const upper = Math.max(0, ...values) || (lower < 0 ? 0 : 1)
  const height = 240, left = 60, right = width - 16, top = 20, bottom = height - 45
  const xFor = (label) => labels.length < 2 ? (left + right) / 2 : left + labels.indexOf(label) * (right - left) / (labels.length - 1)
  const yFor = (value) => top + (upper - value) / (upper - lower) * (bottom - top)
  const ticks = [...new Set([lower, (lower + upper) / 2, upper])]
  const visibleLabels = labels.filter((_, index) => index === 0 || index === labels.length - 1 || index === Math.floor(labels.length / 2))
  const unit = normal[0]?.unidad

  return <section className={`rv rv-temporal ${className}`} aria-labelledby={`${uid}-title`}>
    <h3 id={`${uid}-title`}>{titulo}</h3>
    <div ref={chartRef} className="rv-plot">
      {!marks.length ? <p className="rv-empty">{copy.empty}</p> : <>
        <p id={`${uid}-help`} className="rv-help">{copy.keyboard}</p>
        {unit && <span className="rv-unit">{['ud', 'und'].includes(unit) ? copy.units : unit}</span>}
        <svg className="rv-chart" viewBox={`0 0 ${width} ${height}`} role="group" aria-label={`${titulo} — ${copy.chart}`} aria-describedby={`${uid}-help`} tabIndex={0} onKeyDown={navigate}>
          {ticks.map((tick) => <g key={tick}>
            <line x1={left} x2={right} y1={yFor(tick)} y2={yFor(tick)} className={`rv-axis${tick === 0 ? ' rv-axis--zero' : ''}`} />
            <text x={left - 9} y={yFor(tick) + 4} textAnchor="end" className="rv-label">{new Intl.NumberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 }).format(tick)}</text>
          </g>)}
          {normal.map((serie) => {
            const lookup = new Map(serie.puntos.map((point) => [point.etiqueta, point]))
            const segments = []
            let segment = []
            labels.forEach((label) => {
              const point = lookup.get(label)
              if (!point || point.valor === null) {
                if (segment.length) segments.push(segment)
                segment = []
              } else segment.push(point)
            })
            if (segment.length) segments.push(segment)
            return <g key={serie.id} className="rv-series">
              {segments.map((part, index) => <polyline key={index} points={part.map((point) => `${xFor(point.etiqueta)},${yFor(point.valor)}`).join(' ')} fill="none" stroke={serie.color} strokeWidth="2.5" />)}
              {marks.filter((mark) => mark.serieId === serie.id).map((mark) => <circle key={mark.etiqueta} cx={xFor(mark.etiqueta)} cy={yFor(mark.valor)} r={selected === mark ? 6 : 4.5} fill={serie.color} className="rv-mark" role="button" tabIndex={-1} aria-pressed={selected === mark} aria-label={`${mark.serie}, ${mark.etiqueta}: ${formato(mark.valor, mark.unidad, locale)}`} onClick={() => select(mark)} onKeyDown={(event) => {
                if (['Enter', ' '].includes(event.key)) { event.preventDefault(); select(mark) }
              }}><title>{`${mark.serie}, ${mark.etiqueta}: ${formato(mark.valor, mark.unidad, locale)}`}</title></circle>)}
            </g>
          })}
          {visibleLabels.map((label, index) => <text key={label} x={xFor(label)} y={height - 17} textAnchor={labels.length === 1 ? 'middle' : index === 0 ? 'start' : index === visibleLabels.length - 1 ? 'end' : 'middle'} className="rv-label">{label}</text>)}
        </svg>
      </>}
    </div>
    {normal.length > 0 && <div className="rv-legend" aria-label={copy.series}><ul>{normal.map((serie) => <li key={serie.id}><span className="rv-swatch" style={{ '--rv-swatch': serie.color }} />{serie.nombre}</li>)}</ul></div>}
    {labels.length > 0 && <details className="rv-points" aria-label={copy.points}>
      <summary>{copy.points}</summary>
      <div>{normal.flatMap((serie) => serie.puntos.map((point) => <button key={`${serie.id}-${point.etiqueta}`} type="button" disabled={point.valor === null} aria-pressed={selected?.serieId === serie.id && selected?.etiqueta === point.etiqueta} onClick={() => select({ serieId: serie.id, serie: serie.nombre, etiqueta: point.etiqueta, valor: point.valor, unidad: serie.unidad })}>
        <span>{point.etiqueta}</span><span>{serie.nombre}</span><strong>{formato(point.valor, serie.unidad, locale)}</strong>
      </button>))}</div>
    </details>}
    {selected && <p className="rv-detail" aria-live="polite">{selected.serie}: {selected.etiqueta} — {formato(selected.valor, selected.unidad, locale)}</p>}
  </section>
}

/** Signed values share a zero baseline; labels and exact values never depend on bar width. */
export function BarrasComparativas({ titulo, items = [], unidad = '', onSelect, className = '', locale: explicitLocale }) {
  const locale = useLocale(explicitLocale)
  const uid = useId()
  const [selection, setSelection] = useState(null)
  const normal = items.map((item) => {
    const valor = numero(item.valor)
    const segmentos = Array.isArray(item.segmentos) && item.segmentos.length ? item.segmentos.map((segmento) => ({ ...segmento, valor: numero(segmento.valor) })) : null
    const suma = segmentos?.reduce((total, segmento) => total + (segmento.valor ?? 0), 0)
    const apilable = segmentos?.length > 0 && valor !== null && valor >= 0 && segmentos.every((segmento) => segmento.valor !== null && segmento.valor >= 0) && Math.abs(suma - valor) < 0.000001
    return { ...item, valor, segmentos, apilable }
  })
  const keyFor = (item) => item.id ?? item.etiqueta
  const selected = normal.find((item) => keyFor(item) === selection)
  const lower = Math.min(0, ...normal.map((item) => item.valor ?? 0))
  const upper = Math.max(0, ...normal.map((item) => item.valor ?? 0)) || (lower < 0 ? 0 : 1)
  const position = (value) => (value - lower) / (upper - lower) * 100
  const zero = position(0)
  const leyenda = new Map(normal.filter((item) => item.apilable).flatMap((item) => item.segmentos.map((segmento, index) => [
    segmento.id ?? segmento.etiqueta, { ...segmento, color: color(index) },
  ])))
  return <section className={`rv rv-bars ${className}`} aria-labelledby={`${uid}-title`}>
    <h3 id={`${uid}-title`}>{titulo}</h3>
    {!items.length ? <p className="rv-empty">{textos(locale).empty}</p> : <ul>{normal.map((item, index) => <li key={keyFor(item)}>
      <button type="button" aria-label={`${item.etiqueta}: ${formato(item.valor, unidad, locale)}${item.segmentos ? `, ${item.segmentos.map((segmento) => `${segmento.etiqueta}: ${formato(segmento.valor, unidad, locale)}`).join(', ')}` : ''}`} disabled={item.valor === null} aria-pressed={keyFor(selected || {}) === keyFor(item)} onClick={() => {
        setSelection(keyFor(item))
        onSelect?.({ id: item.id, etiqueta: item.etiqueta, valor: item.valor, unidad, ...(item.segmentos ? { segmentos: item.segmentos } : {}) })
      }}>
        <span>{item.etiqueta}</span>
        <span className="rv-bar-track" aria-hidden="true" style={{ '--rv-zero': `${zero}%` }}>{item.apilable
          ? item.segmentos.map((segmento, segmentIndex) => <span key={segmento.id ?? segmentIndex} className="rv-bar-fill" style={{ left: `${zero + item.segmentos.slice(0, segmentIndex).reduce((sum, part) => sum + part.valor, 0) / (upper - lower) * 100}%`, width: `${segmento.valor / (upper - lower) * 100}%`, background: color(segmentIndex) }} />)
          : <span className="rv-bar-fill" style={{ left: `${Math.min(zero, position(item.valor ?? 0))}%`, width: `${Math.abs(position(item.valor ?? 0) - zero)}%`, background: color(index) }} />}</span>
        <strong>{formato(item.valor, unidad, locale)}</strong>
      </button>
    </li>)}</ul>}
    {leyenda.size > 0 && <ul className="rv-legend rv-bars__legend" aria-label={textos(locale).series}>{[...leyenda.values()].map((segmento) => <li key={segmento.id ?? segmento.etiqueta}><span className="rv-swatch" style={{ '--rv-swatch': segmento.color }} />{segmento.etiqueta}</li>)}</ul>}
    {selected && <p className="rv-detail" aria-live="polite">{selected.etiqueta}: {formato(selected.valor, unidad, locale)}{selected.segmentos?.map((segmento) => ` · ${segmento.etiqueta}: ${formato(segmento.valor, unidad, locale)}`)}</p>}
  </section>
}

/** Positive parts only: negative signed metrics must not be normalized into a whole. */
export function Composicion({ titulo, partes = [], unidad = '', className = '', locale: explicitLocale }) {
  const locale = useLocale(explicitLocale)
  const uid = useId()
  const hasNegative = partes.some((part) => (numero(part.valor) ?? 0) < 0)
  return <section className={`rv rv-composition ${className}`} aria-labelledby={`${uid}-title`}>
    <h3 id={`${uid}-title`}>{titulo}</h3>
    {!partes.length ? <p className="rv-empty">{textos(locale).empty}</p> : <>
      {hasNegative ? <p className="rv-help">{textos(locale).negative}</p> : <div className="rv-composition__strip" aria-hidden="true">{partes.map((part, index) => <span key={part.id ?? index} style={{ '--rv-color': color(index), flexGrow: numero(part.valor) ?? 0 }} />)}</div>}
      <ul className="rv-legend">{partes.map((part, index) => <li key={part.id ?? part.etiqueta}><span className="rv-swatch" style={{ '--rv-swatch': color(index) }} /><span>{part.etiqueta}</span><strong>{formato(numero(part.valor), unidad, locale)}</strong></li>)}</ul>
    </>}
  </section>
}
