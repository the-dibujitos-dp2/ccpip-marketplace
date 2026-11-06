import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { activarCoberturaAcopio, consultarCoberturasAcopio, desactivarCoberturaAcopio } from '../../api/acopioCobertura.js'
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

function ZonaPunto({ punto, c, onCambiar: onCambiarZona, onDeclarar }) {
  const { t } = useTranslation()
  const { filas, pagina, total, totalPaginas, onCambiar } = usePaginacionLocal(punto.zonas ?? [], punto.puntoId)
  return <section className="cobertura401__punto" key={punto.puntoId} aria-labelledby={`punto-${punto.puntoId}`}>
    <div className="cobertura401__cabecera">
      <h2 id={`punto-${punto.puntoId}`}>{punto.punto}</h2>
      <Boton tipo="secundario" tamano="S" onClick={() => onDeclarar(punto)} aria-label={`${c('declarar.abrir')} · ${punto.punto}`}>{c('declarar.abrir')}</Boton>
    </div>
    {/* CCPIP-484: la capacidad diaria la fija la Camara; aqui solo se consulta */}
    {punto.capacidad && <p className="cobertura401__capacidad body-s">
      {punto.capacidad.maximoDiario == null
        ? t('acopio.cobertura401.capacidadSinLimite', { count: punto.capacidad.usadosHoy })
        : t('acopio.cobertura401.capacidad', { usados: punto.capacidad.usadosHoy, maximo: punto.capacidad.maximoDiario })}
    </p>}
    {!total ? <p className="cobertura401__vacio">{c('sinZonas')}</p> : <>
      {/* Columnas del mismo ancho; primero el estado y despues la accion, en su propia columna y en rojo */}
      <div className="cobertura401__tabla-scroll">
        <table className="cobertura401__tabla">
          <caption>{c('zona')} · {punto.punto}</caption>
          <thead>
            <tr>
              {['ubigeo', 'departamento', 'provincia', 'distrito', 'estado'].map((k) => <th key={k} scope="col">{c(k)}</th>)}
              <th scope="col"><span className="visually-hidden">{c('accion')}</span></th>
            </tr>
          </thead>
          <tbody>
            {filas.map((z) => (
              <tr key={z.id}>
                <td data-label={c('ubigeo')}>{z.ubigeo}</td>
                <td data-label={c('departamento')}>{z.departamento}</td>
                <td data-label={c('provincia')}>{z.provincia}</td>
                <td data-label={c('distrito')}>{z.distrito}</td>
                <td data-label={c('estado')}><Badge tono={z.activa ? 'exito' : 'neutro'}>{c(z.activa ? 'activa' : 'inactiva')}</Badge></td>
                <td className="cobertura401__accion">
                  {/* CCPIP-496, 498: una zona inactiva se reactiva sin declararla otra vez */}
                  {z.activa ? (
                    <Boton tipo="secundario" tamano="S" peligro aria-label={`${c('desactivar')} · ${z.distrito} · ${z.ubigeo}`} onClick={() => onCambiarZona(z, false)}>
                      {c('desactivar')}
                    </Boton>
                  ) : (
                    <Boton tipo="secundario" tamano="S" aria-label={`${c('reactivar')} · ${z.distrito} · ${z.ubigeo}`} onClick={() => onCambiarZona(z, true)}>
                      {c('reactivar')}
                    </Boton>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
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
  // { zona, activar }: la zona a desactivar (CCPIP-403) o a reactivar (CCPIP-496, 498)
  const [cambio, setCambio] = useState(null)
  const [cambiado, setCambiado] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [errorCambio, setErrorCambio] = useState(false)
  const cargando = !datos && !error
  const confirmarCambio = async () => {
    if (ocupado || !cambio) return
    setOcupado(true)
    setErrorCambio(false)
    try {
      await (cambio.activar ? activarCoberturaAcopio : desactivarCoberturaAcopio)(cambio.zona.id)
      await recargar()
      setCambiado(cambio)
      setCambio(null)
    } catch {
      setErrorCambio(true)
    } finally {
      setOcupado(false)
    }
  }
  let contenido
  if (cargando) contenido = <Cargando />
  else if (error?.status === 409) contenido = <EstadoVacio titulo={c('sinAsignacion')} />
  else if (error) contenido = <EstadoVacio titulo={c('error')}><Boton tipo="secundario" onClick={recargar}>{c('reintentar')}</Boton></EstadoVacio>
  else if (!datos?.length) contenido = <EstadoVacio titulo={c('sinAsignacion')} />
  else contenido = <div className="cobertura401__puntos">{datos.map((punto) => <ZonaPunto key={punto.puntoId} punto={punto} c={c} onCambiar={(zona, activar) => { setCambio({ zona, activar }); setErrorCambio(false); setCambiado(null) }} onDeclarar={(p) => { setDeclarando(p); setDeclarado(null); setCambiado(null) }} />)}</div>
  return <LayoutAcopio path={path} titulo={c('titulo')}><section className="cobertura401" aria-label={c('titulo')} aria-busy={cargando}>
    {declarado && <Alerta tono="exito" titulo={c('declarar.hecho', declarado)} />}
    {cambiado && <Alerta tono="exito" titulo={c(cambiado.activar ? 'reactivada' : 'desactivada', { distrito: cambiado.zona.distrito })} />}
    {contenido}</section>
    {declarando && <DeclararCobertura punto={declarando} onDeclarado={alDeclarar} onCancelar={() => setDeclarando(null)} />}
    <Dialogo abierto={Boolean(cambio)} titulo={c(cambio?.activar ? 'tituloReactivar' : 'tituloDialogo')}
      confirmar={c(cambio?.activar ? 'confirmarReactivacion' : 'confirmar')} cancelar={c('cancelar')}
      onConfirmar={confirmarCambio} onCancelar={() => !ocupado && setCambio(null)} ocupado={ocupado}>
      <p>{c(cambio?.activar ? 'confirmarReactivar' : 'confirmarDesactivar')}</p>
      {cambio && <p>{cambio.zona.distrito} · {cambio.zona.ubigeo}</p>}
      {errorCambio && <p role="alert">{c(cambio?.activar ? 'errorReactivar' : 'errorDesactivar')}</p>}
    </Dialogo>
  </LayoutAcopio>
}
