import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarCoberturasAcopio, desactivarCoberturaAcopio } from '../../api/acopioCobertura.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import DeclararCobertura from './DeclararCobertura.jsx'
import './CoberturaPage.css'

function ZonaPunto({ punto, c, onDesactivar, onDeclarar }) {
  const { filas, pagina, total, totalPaginas, onCambiar } = usePaginacionLocal(punto.zonas ?? [], punto.puntoId)
  return <section className="cobertura401__punto" key={punto.puntoId} aria-labelledby={`punto-${punto.puntoId}`}>
    <div className="cobertura401__cabecera">
      <h2 id={`punto-${punto.puntoId}`}>{punto.punto}</h2>
      <Boton tipo="secundario" tamano="S" onClick={() => onDeclarar(punto)} aria-label={`${c('declarar.abrir')} · ${punto.punto}`}>{c('declarar.abrir')}</Boton>
    </div>
    {!total ? <p className="cobertura401__vacio">{c('sinZonas')}</p> : <>
      <div className="cobertura401__tabla-scroll"><table className="cobertura401__tabla"><caption>{c('zona')} · {punto.punto}</caption><thead><tr>{['ubigeo','departamento','provincia','distrito','estado'].map((k) => <th key={k} scope="col">{c(k)}</th>)}</tr></thead><tbody>{filas.map((z) => <tr key={z.id}><td data-label={c('ubigeo')}>{z.ubigeo}</td><td data-label={c('departamento')}>{z.departamento}</td><td data-label={c('provincia')}>{z.provincia}</td><td data-label={c('distrito')}>{z.distrito}</td><td data-label={c('estado')}>{z.activa && <Boton tipo="secundario" aria-label={`${c('desactivar')} · ${z.distrito} · ${z.ubigeo}`} onClick={() => onDesactivar(z)}>{c('desactivar')}</Boton>}<Badge tono={z.activa ? 'exito' : 'neutro'}>{c(z.activa ? 'activa' : 'inactiva')}</Badge></td></tr>)}</tbody></table></div>
      <Paginacion pagina={pagina} totalPaginas={totalPaginas} total={total} onCambiar={onCambiar} />
    </>}
  </section>
}

export default function CoberturaPage({ path }) {
  const { t } = useTranslation()
  const c = (k, v) => t(`acopio.cobertura401.${k}`, v)
  const consultar = useCallback(() => consultarCoberturasAcopio(), [])
  const { datos, setDatos, error, recargar } = useConsulta(consultar)
  const [declarando, setDeclarando] = useState(null)
  const [declarado, setDeclarado] = useState(null)
  const alDeclarar = (actualizado, cuantos) => {
    setDatos((actual) => actual.map((p) => (p.puntoId === actualizado.puntoId ? actualizado : p)))
    setDeclarado({ punto: actualizado.punto, count: cuantos })
    setDeclarando(null)
  }
  const [zonaSeleccionada, setZonaSeleccionada] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [errorDesactivar, setErrorDesactivar] = useState(false)
  const cargando = !datos && !error
  const confirmarDesactivar = async () => {
    if (ocupado || !zonaSeleccionada) return
    setOcupado(true)
    setErrorDesactivar(false)
    try {
      await desactivarCoberturaAcopio(zonaSeleccionada.id)
      await recargar()
      setZonaSeleccionada(null)
    } catch {
      setErrorDesactivar(true)
    } finally {
      setOcupado(false)
    }
  }
  let contenido
  if (cargando) contenido = <Cargando />
  else if (error?.status === 409) contenido = <EstadoVacio titulo={c('sinAsignacion')} />
  else if (error) contenido = <EstadoVacio titulo={c('error')}><Boton tipo="secundario" onClick={recargar}>{c('reintentar')}</Boton></EstadoVacio>
  else if (!datos?.length) contenido = <EstadoVacio titulo={c('sinAsignacion')} />
  else contenido = <div className="cobertura401__puntos">{datos.map((punto) => <ZonaPunto key={punto.puntoId} punto={punto} c={c} onDesactivar={(z) => { setZonaSeleccionada(z); setErrorDesactivar(false) }} onDeclarar={(p) => { setDeclarando(p); setDeclarado(null) }} />)}</div>
  return <LayoutAcopio path={path} titulo={c('titulo')}><section className="cobertura401" aria-label={c('titulo')} aria-busy={cargando}>
    {declarado && <Alerta tono="exito" titulo={c('declarar.hecho', declarado)} />}
    {contenido}</section>
    {declarando && <DeclararCobertura punto={declarando} onDeclarado={alDeclarar} onCancelar={() => setDeclarando(null)} />}
    <Dialogo abierto={Boolean(zonaSeleccionada)} titulo={c('tituloDialogo')} confirmar={c('confirmar')} cancelar={c('cancelar')} onConfirmar={confirmarDesactivar} onCancelar={() => !ocupado && setZonaSeleccionada(null)} ocupado={ocupado}>
      <p>{c('confirmarDesactivar')}</p>
      {zonaSeleccionada && <p>{zonaSeleccionada.distrito} · {zonaSeleccionada.ubigeo}</p>}
      {errorDesactivar && <p role="alert">{c('errorDesactivar')}</p>}
    </Dialogo>
  </LayoutAcopio>
}
