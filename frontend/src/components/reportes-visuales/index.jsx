import { useId, useMemo, useState } from 'react'
import './ReportesVisuales.css'

const numero = (v) => Number.isFinite(Number(v)) && v !== null ? Number(v) : null
const fmt = (v, unidad = '') => v == null || !Number.isFinite(Number(v)) ? 'Sin dato' : `${unidad ? `${unidad} ` : ''}${new Intl.NumberFormat('es-PE', { maximumFractionDigits: 2 }).format(Number(v))}`
const token = (i) => `var(--rv-color-${(i % 6) + 1})`

function contenedor(el, props) { return <section className="rv" aria-label={props.titulo} {...props}>{el}</section> }

/** series: [{id,nombre,unidad?,puntos:[{etiqueta,valor}]}]. onSelect recibirá {serieId,serie,etiqueta,valor,unidad}. */
export function SerieTemporal({ titulo, series = [], unidad, onSelect, seleccionInicial = null, className = '' }) {
  const uid = useId()
  const normal = useMemo(() => {
    const us = new Set(series.map(s => s.unidad ?? unidad ?? ''))
    if (us.size > 1) throw new Error('SerieTemporal no admite series con distinta unidad')
    return series.map((s, si) => ({ ...s, puntos: (s.puntos ?? []).map((p, i) => ({ ...p, valor: numero(p.valor), _i: i, _serie: si })) }))
  }, [series, unidad])
  const [sel, setSel] = useState(seleccionInicial)
  const puntos = normal.flatMap(s => s.puntos)
  const maxAbs = Math.max(1, ...puntos.map(p => Math.abs(p.valor ?? 0)))
  const w = 760, h = 228, pad = 28
  const escoger = (s, p) => { const detail = { serieId: s.id, serie: s.nombre, etiqueta: p.etiqueta, valor: p.valor, unidad: s.unidad ?? unidad ?? '' }; setSel(detail); onSelect?.(detail) }
  return <section className={`rv rv-temporal ${className}`} aria-labelledby={`${uid}-title`}>
    <h3 id={`${uid}-title`}>{titulo}</h3>
    {!puntos.length ? <p className="rv-empty">Sin datos para mostrar.</p> : <>
      <svg className="rv-chart" viewBox={`0 0 ${w} ${h}`} role="img" aria-label={titulo} preserveAspectRatio="xMidYMid meet">
        <line x1={pad} x2={w-pad} y1={h/2} y2={h/2} className="rv-axis" />
        {normal.map((s, si) => {
          const valid = s.puntos.filter(p => p.valor != null)
          const coords = valid.map(p => ({ x: pad + (s.puntos.length < 2 ? (w-pad*2)/2 : p._i*(w-pad*2)/(s.puntos.length-1)), y: h/2 - p.valor/maxAbs*(h/2-pad) }))
          return <g key={s.id} className="rv-series"><polyline points={coords.map(c => `${c.x},${c.y}`).join(' ')} fill="none" stroke={token(si)} strokeWidth="3" />
            {valid.map((p,j) => { const c=coords[j]; return <circle key={`${s.id}-${p._i}`} cx={c.x} cy={c.y} r="5" fill={token(si)} /> })}</g>
        })}
      </svg>
      <div className="rv-legend" aria-label="Series"><ul>{normal.map((s,i)=><li key={s.id}><span className="rv-swatch" style={{'--rv-swatch':token(i)}} />{s.nombre}</li>)}</ul></div>
      <div className="rv-points" aria-label="Puntos de la serie">{normal.flatMap(s => s.puntos.map((p) => <button key={`${s.id}-${p._i}`} type="button" aria-pressed={sel?.serieId===s.id && sel?.etiqueta===p.etiqueta} onClick={() => escoger(s,p)}>
        <span>{p.etiqueta}</span><span>{s.nombre}</span><strong>{fmt(p.valor,s.unidad ?? unidad)}</strong></button>))}</div>
      {sel && <p className="rv-detail" aria-live="polite">{sel.serie}: {sel.etiqueta} — {fmt(sel.valor,sel.unidad)}</p>}
    </>}
  </section>
}

/** items: [{id,etiqueta,valor}], valor numérico real; onSelect({id,etiqueta,valor,unidad}). */
export function BarrasComparativas({ titulo, items = [], unidad = '', onSelect, className = '' }) {
  const uid=useId(), [sel,setSel]=useState(null), max=Math.max(1,...items.map(x=>Math.abs(numero(x.valor) ?? 0)))
  return <section className={`rv rv-bars ${className}`} aria-labelledby={`${uid}-title`}><h3 id={`${uid}-title`}>{titulo}</h3>
    {!items.length ? <p className="rv-empty">Sin datos para mostrar.</p> : <ul>{items.map((x,i)=>{const v=numero(x.valor), d={id:x.id,etiqueta:x.etiqueta,valor:v,unidad};return <li key={x.id ?? x.etiqueta}><button type="button" aria-pressed={sel?.id===x.id} onClick={()=>{setSel(d);onSelect?.(d)}}><span>{x.etiqueta}</span><span className="rv-bar-track"><span className="rv-bar-fill" style={{'--rv-width':`${v===null?0:Math.abs(v)/max*100}%`,'--rv-color':token(i)}} /></span><strong>{fmt(v,unidad)}</strong></button></li>})}</ul>}
    {sel && <p className="rv-detail" aria-live="polite">{sel.etiqueta}: {fmt(sel.valor,unidad)}</p>}
  </section>
}

/** partes: [{id,etiqueta,valor}], valores individuales conservados; sin total inferido. */
export function Composicion({ titulo, partes = [], unidad = '', className = '' }) {
  const uid=useId()
  return <section className={`rv rv-composition ${className}`} aria-labelledby={`${uid}-title`}><h3 id={`${uid}-title`}>{titulo}</h3>
    {!partes.length ? <p className="rv-empty">Sin datos para mostrar.</p> : <>
      <div className="rv-composition__strip" aria-hidden="true">{partes.map((p,i)=>{const v=numero(p.valor);return <span key={p.id ?? i} style={{'--rv-color':token(i),'--rv-share':`${v===null?0:Math.max(0,v)}fr`}} />})}</div>
      <ul className="rv-legend">{partes.map((p,i)=><li key={p.id ?? p.etiqueta}><span className="rv-swatch" style={{'--rv-swatch':token(i)}} /><span>{p.etiqueta}</span><strong>{fmt(numero(p.valor),unidad)}</strong></li>)}</ul>
    </>}
  </section>
}
