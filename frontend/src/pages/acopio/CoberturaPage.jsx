import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarCoberturasAcopio } from '../../api/acopioCobertura.js'
import { useConsulta } from '../../api/useConsulta.js'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import './CoberturaPage.css'

export default function CoberturaPage({ path }) {
  const { t } = useTranslation()
  const c = (k) => t(`acopio.cobertura401.${k}`)
  const consultar = useCallback(() => consultarCoberturasAcopio(), [])
  const { datos, error, recargar } = useConsulta(consultar)
  const cargando = !datos && !error
  let contenido
  if (cargando) contenido = <Cargando />
  else if (error?.status === 409) contenido = <EstadoVacio titulo={c('sinAsignacion')} />
  else if (error) contenido = <EstadoVacio titulo={c('error')}><Boton tipo="secundario" onClick={recargar}>{c('reintentar')}</Boton></EstadoVacio>
  else if (!datos?.length) contenido = <EstadoVacio titulo={c('sinAsignacion')} />
  else contenido = <div className="cobertura401__puntos">{datos.map((punto) => <section className="cobertura401__punto" key={punto.puntoId} aria-labelledby={`punto-${punto.puntoId}`}>
    <h2 id={`punto-${punto.puntoId}`}>{punto.punto}</h2>
    {!punto.zonas?.length ? <p className="cobertura401__vacio">{c('sinZonas')}</p> : <div className="cobertura401__tabla-scroll"><table className="cobertura401__tabla"><caption>{c('zona')} · {punto.punto}</caption><thead><tr>{['ubigeo','departamento','provincia','distrito','estado'].map((k) => <th key={k} scope="col">{c(k)}</th>)}</tr></thead><tbody>{punto.zonas.map((z) => <tr key={z.id}><td data-label={c('ubigeo')}>{z.ubigeo}</td><td data-label={c('departamento')}>{z.departamento}</td><td data-label={c('provincia')}>{z.provincia}</td><td data-label={c('distrito')}>{z.distrito}</td><td data-label={c('estado')}><Badge tono={z.activa ? 'exito' : 'neutro'}>{c(z.activa ? 'activa' : 'inactiva')}</Badge></td></tr>)}</tbody></table></div>}
  </section>)}</div>
  return <LayoutAcopio path={path} titulo={c('titulo')}><section className="cobertura401" aria-label={c('titulo')} aria-busy={cargando}>{contenido}</section></LayoutAcopio>
}
