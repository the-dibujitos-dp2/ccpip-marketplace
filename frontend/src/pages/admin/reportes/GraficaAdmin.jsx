import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BarrasComparativas, Composicion, SerieTemporal } from '../../../components/reportes-visuales/index.jsx'
import './GraficaAdmin.css'

const money = (v) => v == null ? null : Number(v)
const display = (v) => v == null || !Number.isFinite(Number(v)) ? '—' : new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(Number(v))

/** Presentación interactiva de las respuestas agregadas ya cargadas; no solicita ni deriva métricas. */
export function GraficaAdmin({ tipo, datos, titulo }) {
  const { t } = useTranslation()
  const [magnitud, setMagnitud] = useState(tipo === 'finanzas' ? 'total' : 'ventas')
  const [modo, setModo] = useState('periodo')
  const [seleccion, setSeleccion] = useState(null)
  const dataset = datos?.aplicado ? JSON.stringify([datos.clave, datos.aplicado]) : JSON.stringify([datos?.clave, datos?.desde, datos?.hasta, datos?.agrupacion])
  const periodos = datos?.periodos ?? []
  const lista = useMemo(() => {
    if (tipo === 'finanzas') return periodos
    if (tipo === 'indicadores') return periodos
    return []
  }, [datos, tipo])
  if (tipo === 'finanzas') {
    const nombres = { b2c: t('admin.reportes.col.b2c'), b2b: t('admin.reportes.col.b2b'), total: t('admin.reportes.col.total'), acumulado: t('admin.reportes.col.acumulado') }
    const campo = magnitud === 'acumulado' ? 'acumulado' : magnitud
    const series = campo === 'total' || campo === 'acumulado'
      ? [{ id: campo, nombre: nombres[campo], unidad: 'S/', puntos: periodos.map(p => ({ etiqueta: p.inicio, valor: money(p[campo]) })) }]
      : [{ id: campo, nombre: nombres[campo], unidad: 'S/', puntos: periodos.map(p => ({ etiqueta: p.inicio, valor: money(p[campo]) })) }]
    return <section className="reportes-visual" key={dataset} aria-label={titulo}>
      <div className="reportes-visual__controls" role="group" aria-label={titulo}>{['b2c','b2b','total','acumulado'].map(x => <button type="button" aria-pressed={magnitud===x} key={x} onClick={() => { setMagnitud(x); setSeleccion(null) }}>{nombres[x]}</button>)}
        <button type="button" aria-pressed={modo==='acumulado'} onClick={() => { setModo(modo==='acumulado'?'periodo':'acumulado'); setMagnitud(modo==='acumulado'?'total':'acumulado') }}>{t('admin.reportes.col.acumulado')}</button></div>
      <SerieTemporal key={`chart-${dataset}-${magnitud}`} titulo={`${titulo} — gráfico`} series={series} onSelect={setSeleccion} />
      {seleccion && <p className="reportes-visual__detail" aria-live="polite">{seleccion.etiqueta}: {display(seleccion.valor)} S/</p>}
    </section>
  }
  if (tipo === 'indicadores') {
    const campos = { ventas: t('admin.reportes.col.total'), pedidos: t('admin.reportes.col.pedidos'), productores: t('admin.reportes.col.productoresConVentas'), productos: t('admin.reportes.col.productosNuevos'), compradores: t('admin.reportes.col.compradores') }
    const campo = magnitud in campos ? magnitud : 'ventas'
    const puntos = lista.map(p => ({ etiqueta: p.inicio, valor: money(p[campo]) }))
    return <section className="reportes-visual" key={dataset} aria-label={titulo}><div className="reportes-visual__controls" role="group" aria-label={titulo}>{Object.entries(campos).map(([k,v])=><button type="button" key={k} aria-pressed={campo===k} onClick={()=>{setMagnitud(k);setSeleccion(null)}}>{v}</button>)}</div><SerieTemporal key={`chart-${dataset}-${campo}`} titulo={`${titulo} — gráfico`} series={[{ id: campo, nombre: campos[campo], unidad: campo==='ventas'?'S/':'', puntos }]} onSelect={setSeleccion}/>{seleccion && <p className="reportes-visual__detail" aria-live="polite">{seleccion.etiqueta}: {display(seleccion.valor)}</p>}</section>
  }
  if (tipo === 'productores' || tipo === 'puntos') {
    const rows = tipo === 'productores' ? datos.productores ?? [] : datos.puntos ?? []
    const metric = magnitud === 'pedidos' ? 'pedidos' : magnitud === 'unidades' ? 'unidades' : 'total'
    const unidad = metric === 'total' ? 'S/' : ''
    const items = rows.map((r, i) => ({ id: tipo === 'productores' ? r.productorId : r.puntoId, etiqueta: tipo === 'productores' ? r.productor : r.punto, valor: money(r[metric]), index: i }))
    const selected = items.find(x => x.id === seleccion?.id)
    return <section className="reportes-visual" key={dataset} aria-label={titulo}><div className="reportes-visual__controls" role="group" aria-label={titulo}>{(tipo==='productores'?['total','pedidos','unidades']:['total','pedidos']).map(k=><button type="button" key={k} aria-pressed={metric===k} onClick={()=>{setMagnitud(k);setSeleccion(null)}}>{t(`admin.reportes.col.${k}`)}</button>)}</div><BarrasComparativas key={`chart-${dataset}-${metric}`} titulo={`${titulo} — gráfico`} items={items} unidad={unidad} onSelect={setSeleccion}/>{selected && <p className="reportes-visual__detail" aria-live="polite">{selected.etiqueta}: {display(selected.valor)}{unidad ? ` ${unidad}` : ''}</p>}</section>
  }
  if (tipo === 'calificaciones') {
    const items = (datos.resumen?.distribucion ?? []).map(x=>({id:x.estrellas,etiqueta:t('admin.reportes.calificaciones.estrellas',{count:x.estrellas}),valor:money(x.cantidad)}))
    const series = [{id:'promedio',nombre:t('admin.reportes.calificaciones.promedio'),unidad:'',puntos:(datos.productos ?? []).map((x,i)=>({etiqueta:x.producto ?? String(i+1),valor:x.promedio == null ? null : money(x.promedio)}))}]
    return <section className="reportes-visual" key={dataset} aria-label={titulo}><Composicion titulo={t('admin.reportes.calificaciones.distribucion')} partes={items} unidad=""/><BarrasComparativas key={`chart-${dataset}-stars`} titulo={`${titulo} — gráfico`} items={items} onSelect={setSeleccion}/><SerieTemporal key={`chart-${dataset}-ratings`} titulo={t('admin.reportes.calificaciones.productos')} series={series}/></section>
  }
  if (tipo === 'origen-destino') {
    const dims = { destino: datos.porDestino ?? [], punto: datos.porPunto ?? [], productor: datos.porProductor ?? [], ruta: datos.rutas ?? [] }
    const dimension = magnitud in dims ? magnitud : 'destino'
    const rows = dims[dimension]
    const items = rows.map((x,i)=>({id:`${dimension}-${i}`,etiqueta:dimension==='destino'?(x.departamento ?? t('admin.reportes.origen-destino.sinDireccion')):dimension==='ruta'?`${x.punto} — ${x.destino}`:x.nombre,valor:money(x.total)}))
    return <section className="reportes-visual" key={dataset} aria-label={titulo}><div className="reportes-visual__controls" role="group" aria-label={titulo}>{Object.keys(dims).map(k=><button type="button" key={k} aria-pressed={dimension===k} onClick={()=>{setMagnitud(k);setSeleccion(null)}}>{t(`admin.reportes.origen-destino.${k==='ruta'?'rutas':k==='destino'?'porDestino':k==='punto'?'porPunto':'porProductor'}`)}</button>)}</div><BarrasComparativas key={`chart-${dataset}-${dimension}`} titulo={`${titulo} — gráfico`} items={items} unidad="S/" onSelect={setSeleccion}/>{seleccion && <p className="reportes-visual__detail" aria-live="polite">{seleccion.etiqueta}: {display(seleccion.valor)} S/</p>}</section>
  }
  return null
}
