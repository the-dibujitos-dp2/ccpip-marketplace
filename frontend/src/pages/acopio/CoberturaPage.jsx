import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarCoberturasAcopio, desactivarCoberturaAcopio } from '../../api/acopioCobertura.js'
import { useConsulta } from '../../api/useConsulta.js'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import './CoberturaPage.css'

function ZonaPunto({ punto, c, onDesactivar }) {
  const { filas, pagina, total, totalPaginas, onCambiar } = usePaginacionLocal(punto.zonas ?? [], punto.puntoId)
  return <section className="cobertura401__punto" key={punto.puntoId} aria-labelledby={`punto-${punto.puntoId}`}>
    <h2 id={`punto-${punto.puntoId}`}>{punto.punto}</h2>
    {!total ? <p className="cobertura401__vacio">{c('sinZonas')}</p> : <>
      <div className="cobertura401__tabla-scroll"><table className="cobertura401__tabla"><caption>{c('zona')} · {punto.punto}</caption><thead><tr>{['ubigeo','departamento','provincia','distrito','estado'].map((k) => <th key={k} scope="col">{c(k)}</th>)}</tr></thead><tbody>{filas.map((z) => <tr key={z.id}><td data-label={c('ubigeo')}>{z.ubigeo}</td><td data-label={c('departamento')}>{z.departamento}</td><td data-label={c('provincia')}>{z.provincia}</td><td data-label={c('distrito')}>{z.distrito}</td><td data-label={c('estado')}>{z.activa && <Boton tipo="secundario" aria-label={`${c('desactivar')} · ${z.distrito} · ${z.ubigeo}`} onClick={() => onDesactivar(z)}>{c('desactivar')}</Boton>}<Badge tono={z.activa ? 'exito' : 'neutro'}>{c(z.activa ? 'activa' : 'inactiva')}</Badge></td></tr>)}</tbody></table></div>
      <Paginacion pagina={pagina} totalPaginas={totalPaginas} total={total} onCambiar={onCambiar} />
    </>}
  </section>
}

export default function CoberturaPage({ path }) {
  const { t } = useTranslation()
  const c = (k) => t(`acopio.cobertura401.${k}`)
  const consultar = useCallback(() => consultarCoberturasAcopio(), [])
  const { datos, error, recargar } = useConsulta(consultar)
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
  else contenido = <div className="cobertura401__puntos">{datos.map((punto) => <ZonaPunto key={punto.puntoId} punto={punto} c={c} onDesactivar={(z) => { setZonaSeleccionada(z); setErrorDesactivar(false) }} />)}</div>
  return <LayoutAcopio path={path} titulo={c('titulo')}><section className="cobertura401" aria-label={c('titulo')} aria-busy={cargando}>{contenido}</section>
    <Dialogo abierto={Boolean(zonaSeleccionada)} titulo={c('tituloDialogo')} confirmar={c('confirmar')} cancelar={c('cancelar')} onConfirmar={confirmarDesactivar} onCancelar={() => !ocupado && setZonaSeleccionada(null)} ocupado={ocupado}>
      <p>{c('confirmarDesactivar')}</p>
      {zonaSeleccionada && <p>{zonaSeleccionada.distrito} · {zonaSeleccionada.ubigeo}</p>}
      {errorDesactivar && <p role="alert">{c('errorDesactivar')}</p>}
    </Dialogo>
  </LayoutAcopio>
}
