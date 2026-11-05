import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoAlmacen from '../../assets/icons/almacen.svg'
import iconoCheck from '../../assets/icons/check.svg'
import iconoUsuario from '../../assets/icons/usuario.svg'
import { consultarUbicaciones, listarMisEntregas, registrarEnCamino, registrarEntrega, retirarEntrega } from '../../api/distribucion.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import Pasos from '../../components/Pasos.jsx'
import LayoutDistribuidor from '../../components/layout/LayoutDistribuidor.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import { diaMesHora, hora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { navigate, RUTAS } from '../../router.js'
import './MisEntregasPage.css'

/** Un dato de una ubicacion; sin valor (la referencia es opcional) no se muestra. */
function Dato({ etiqueta, valor }) {
  if (!valor) return null
  return (
    <div className="ubicaciones__dato">
      <dt>{etiqueta}</dt>
      <dd>{valor}</dd>
    </div>
  )
}

/**
 * CCPIP-110: de donde sale y a donde va el pedido, en dos columnas con una flecha del origen al destino. Es un
 * <dialog> modal: se cierra con Escape, con Cerrar o pulsando fuera.
 */
function DialogoUbicaciones({ numero, onCerrar }) {
  const { t } = useTranslation()
  const u = (k) => t(`distribuidor.entregas.ubicaciones.${k}`)
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)
  const [cargando, setCargando] = useState(true)
  const cerrarRef = useRef(null)
  const dialogoRef = useRef(null)

  async function cargar() {
    setCargando(true)
    setError(null)
    setDatos(null)
    try {
      setDatos(await consultarUbicaciones(numero))
    } catch (e) {
      setError(e)
    } finally {
      setCargando(false)
    }
  }

  useEffect(() => {
    let vivo = true
    consultarUbicaciones(numero).then((d) => vivo && setDatos(d)).catch((e) => vivo && setError(e)).finally(() => vivo && setCargando(false))
    const previo = document.activeElement
    const dialogo = dialogoRef.current
    if (dialogo && !dialogo.open) {
      if (typeof dialogo.showModal === 'function') dialogo.showModal()
      else dialogo.setAttribute('open', '')
    }
    cerrarRef.current?.focus()
    return () => {
      vivo = false
      if (dialogo?.open) {
        if (typeof dialogo.close === 'function') dialogo.close()
        else dialogo.removeAttribute('open')
      }
      previo?.focus?.()
    }
  }, [numero])

  return (
    <dialog ref={dialogoRef} className="ubicaciones" role={error ? 'alertdialog' : 'dialog'} aria-labelledby="ubicaciones-titulo" aria-busy={cargando}
      onCancel={(e) => { e.preventDefault(); onCerrar() }} onClick={(e) => { if (e.target === e.currentTarget) onCerrar() }}>
      <header className="ubicaciones__cabecera">
        <h2 id="ubicaciones-titulo">{t('distribuidor.entregas.ubicaciones.titulo', { numero })}</h2>
        <Boton ref={cerrarRef} tipo="secundario" tamano="S" onClick={onCerrar}>{u('cerrar')}</Boton>
      </header>
      {cargando && <Cargando texto={u('cargando')} />}
      {error && (
        <div role="alert" className="ubicaciones__error">
          <p>{u('error')}</p>
          <p>{mensajeError(error)}</p>
          <Boton tamano="S" onClick={cargar}>{u('reintentar')}</Boton>
        </div>
      )}
      {datos && (
        <div className="ubicaciones__ruta">
          <section className="ubicaciones__lugar" aria-labelledby="ubicaciones-origen">
            <h3 id="ubicaciones-origen" className="ubicaciones__lugar-titulo"><Icono src={iconoAlmacen} tamano={18} />{u('origen')}</h3>
            <dl>
              <Dato etiqueta={u('punto')} valor={datos.origen.nombre} />
              <Dato etiqueta={u('direccion')} valor={datos.origen.direccion} />
              <Dato etiqueta={u('distrito')} valor={datos.origen.distrito} />
            </dl>
          </section>
          <span className="ubicaciones__flecha" aria-hidden="true">→</span>
          <section className="ubicaciones__lugar ubicaciones__lugar--destino" aria-labelledby="ubicaciones-destino">
            <h3 id="ubicaciones-destino" className="ubicaciones__lugar-titulo"><Icono src={iconoUsuario} tamano={18} />{u('destino')}</h3>
            <dl>
              <Dato etiqueta={u('direccion')} valor={datos.destino.direccion} />
              <Dato etiqueta={u('distrito')} valor={datos.destino.territorio.distrito} />
              <Dato etiqueta={u('provincia')} valor={datos.destino.territorio.provincia} />
              <Dato etiqueta={u('departamento')} valor={datos.destino.territorio.departamento} />
              <Dato etiqueta={u('referencia')} valor={datos.destino.referencia} />
            </dl>
          </section>
        </div>
      )}
    </dialog>
  )
}

/** Color del estado de la entrega: siempre con su texto (Badge). */
const TONOS = { ASIGNADA: 'marca', RETIRADA: 'acento', EN_CAMINO: 'info', ENTREGADA: 'exito' }

/** El paso en que va la entrega: por recoger, recogida, en camino o entregada. */
const PASO = { ASIGNADA: 0, RETIRADA: 1, EN_CAMINO: 2, ENTREGADA: 3 }

/**
 * DW-03 · CCPIP-317 (RF-199): las entregas que el distribuidor tomo, y solo las suyas. Las que estan en curso van en
 * tarjetas, con el punto donde recogerlas, el distrito de destino, su estado y los pasos (recogido, en camino,
 * entregado); las que entrego hoy, en la tabla de al lado. La direccion y el contacto de quien recibe llegan con
 * CCPIP-110.
 *
 * CCPIP-109 (RF-56): en una entrega por recoger, "Retire el pedido" registra que la retiro del punto de acopio; queda
 * recogida con su fecha. Ir en camino y entregarla llegan con CCPIP-318 y CCPIP-319.
 */
export default function MisEntregasPage({ path }) {
  const { t } = useTranslation()
  const { datos, error, recargar } = useConsulta(listarMisEntregas)
  // CCPIP-109: la entrega que se esta retirando, y lo que paso al retirarla
  const [retirando, setRetirando] = useState(null)
  const [enCaminoActualizando, setEnCaminoActualizando] = useState(null)
  const [entregaActualizando, setEntregaActualizando] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [ubicaciones, setUbicaciones] = useState(null)

  const enCurso = datos?.filter((e) => e.estado !== 'ENTREGADA') ?? []
  const entregadas = datos?.filter((e) => e.estado === 'ENTREGADA') ?? []
  const { filas: entregadasPagina, pagina, total, totalPaginas, onCambiar } = usePaginacionLocal(entregadas, 'entregadas')
  const pasos = ['recogido', 'enCamino', 'entregado'].map((p) => t(`distribuidor.entregas.pasos.${p}`))

  async function retirar(numero) {
    setRetirando(numero)
    setAviso(null)
    try {
      await retirarEntrega(numero)
      setAviso({ tono: 'exito', titulo: t('distribuidor.entregas.retirado', { numero }) })
    } catch (e) {
      // Un 401 ya lo maneja la app (vuelve a ingresar)
      if (e.status === 401) return
      // Si ya no es suya o ya no se puede retirar, la lista al dia muestra donde esta
      const conflicto = e.status === 409 || e.status === 404
      setAviso({ tono: conflicto ? 'aviso' : 'error', titulo: t('distribuidor.entregas.errorRetirar', { numero }), descripcion: mensajeError(e) })
    } finally {
      setRetirando(null)
    }
    recargar()
  }

  async function avanzarEnCamino(numero) {
    setEnCaminoActualizando(numero)
    setAviso(null)
    try {
      await registrarEnCamino(numero)
      setAviso({ tono: 'exito', titulo: t('distribuidor.entregas.enCaminoRegistrado', { numero }) })
    } catch (e) {
      if (e.status === 401) return
      setAviso({ tono: e.status === 409 ? 'aviso' : 'error', titulo: t('distribuidor.entregas.errorEnCamino', { numero }), descripcion: mensajeError(e) })
    } finally { setEnCaminoActualizando(null) }
    recargar()
  }

  async function finalizarEntrega(numero) {
    setEntregaActualizando(numero)
    setAviso(null)
    try {
      await registrarEntrega(numero)
      setAviso({ tono: 'exito', titulo: t('distribuidor.entregas.entregaRegistrada', { numero }) })
    } catch (e) {
      if (e.status === 401) return
      setAviso({ tono: e.status === 409 ? 'aviso' : 'error', titulo: t('distribuidor.entregas.errorEntrega', { numero }), descripcion: mensajeError(e) })
    } finally { setEntregaActualizando(null) }
    recargar()
  }

  const titulo = t('distribuidor.entregas.titulo')
  return (
    <LayoutDistribuidor path={path} titulo={titulo}>
      {ubicaciones && <DialogoUbicaciones key={ubicaciones} numero={ubicaciones} onCerrar={() => setUbicaciones(null)} />}
      <section className="mis-entregas" aria-busy={!datos && !error}>
        {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
        {error && (
          <EstadoVacio titulo={t('distribuidor.entregas.error')} descripcion={mensajeError(error)}>
            <Boton tamano="M" onClick={recargar}>{t('distribuidor.entregas.reintentar')}</Boton>
          </EstadoVacio>
        )}
        {!datos && !error && <Cargando texto={t('distribuidor.entregas.cargando')} />}

        {datos && (
          <div className="mis-entregas__columnas">
            <div className="mis-entregas__en-curso">
              <h2 className="mis-entregas__subtitulo">{t('distribuidor.entregas.enCurso', { count: enCurso.length })}</h2>
              {enCurso.length === 0 && (
                <EstadoVacio titulo={t('distribuidor.entregas.vacio')} descripcion={t('distribuidor.entregas.vacioTexto')}>
                  <Boton tamano="M" icono={iconoCaja} onClick={() => navigate(RUTAS.distribuidorPedidos)}>
                    {t('distribuidor.entregas.verDisponibles')}
                  </Boton>
                </EstadoVacio>
              )}
              {enCurso.map((e) => (
                <article key={e.numero} className="mis-entregas__tarjeta" aria-labelledby={`entrega-${e.numero}`}>
                  <header className="mis-entregas__cabecera">
                    <h3 id={`entrega-${e.numero}`}>{`${e.numero} · ${e.destino.distrito}`}</h3>
                    <Badge tono={TONOS[e.estado] ?? 'neutro'}>{t(`distribuidor.entregas.estado.${e.estado}`, e.estado)}</Badge>
                  </header>
                  <p className="caption">
                    {t('distribuidor.entregas.recogerEn', { punto: e.origen.nombre })}
                    {' · '}
                    {[e.origen.direccion, e.origen.distrito].filter(Boolean).join(', ')}
                  </p>
                  <p className="caption">{t('distribuidor.entregas.destino', { provincia: e.destino.provincia, departamento: e.destino.departamento })}</p>
                  <div className="mis-entregas__acciones"><Boton tamano="S" tipo="secundario" onClick={() => setUbicaciones(e.numero)}>{t('distribuidor.entregas.ubicaciones.accion')}</Boton></div>
                  <Pasos pasos={pasos} actual={PASO[e.estado] ?? 0} etiqueta={t('distribuidor.entregas.pasos.etiqueta', { numero: e.numero })} />
                  <Momentos entrega={e} />
                  {e.estado === 'ASIGNADA' && (
                    <div className="mis-entregas__acciones">
                      <Boton
                        tamano="M"
                        icono={iconoCheck}
                        cargando={retirando === e.numero}
                        disabled={retirando !== null || enCaminoActualizando !== null || entregaActualizando !== null}
                        onClick={() => void retirar(e.numero)}
                      >
                        {retirando === e.numero ? t('distribuidor.entregas.retirando') : t('distribuidor.entregas.retirar')}
                      </Boton>
                    </div>
                  )}
                  {e.estado === 'RETIRADA' && (
                    <div className="mis-entregas__acciones">
                      <Boton tamano="M" cargando={enCaminoActualizando === e.numero} disabled={retirando !== null || enCaminoActualizando !== null || entregaActualizando !== null} onClick={() => void avanzarEnCamino(e.numero)}>{enCaminoActualizando === e.numero ? t('distribuidor.entregas.registrandoEnCamino') : t('distribuidor.entregas.registrarEnCamino')}</Boton>
                    </div>
                  )}
                  {e.estado === 'EN_CAMINO' && (
                    <div className="mis-entregas__acciones">
                      <Boton tamano="M" cargando={entregaActualizando === e.numero} disabled={retirando !== null || enCaminoActualizando !== null || entregaActualizando !== null} onClick={() => void finalizarEntrega(e.numero)}>{entregaActualizando === e.numero ? t('distribuidor.entregas.registrandoEntrega') : t('distribuidor.entregas.registrarEntrega')}</Boton>
                    </div>
                  )}
                </article>
              ))}
            </div>

            <div className="mis-entregas__hoy">
              <h2 className="mis-entregas__subtitulo">{t('distribuidor.entregas.entregadosHoy')}</h2>
              {entregadas.length === 0 ? (
                <p className="caption mis-entregas__nada">{t('distribuidor.entregas.sinEntregadosHoy')}</p>
              ) : (
                <div className="mis-entregas__tabla-scroll" role="region" aria-label={t('distribuidor.entregas.entregadosHoy')} tabIndex={0}>
                <table className="mis-entregas__tabla">
                  <caption className="visually-hidden">{t('distribuidor.entregas.entregadosHoy')}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('distribuidor.entregas.col.pedido')}</th>
                      <th scope="col">{t('distribuidor.entregas.col.destino')}</th>
                      <th scope="col">{t('distribuidor.entregas.col.hora')}</th>
                      <th scope="col">{t('distribuidor.entregas.col.estado')}</th>
                      <th scope="col">{t('distribuidor.entregas.ubicaciones.accion')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entregadasPagina.map((e) => (
                      <tr key={e.numero}>
                        <th scope="row" className="mis-entregas__numero">{e.numero}</th>
                        <td>{e.destino.distrito}</td>
                        <td>{hora(e.entregadaEn)}</td>
                        <td><Badge tono={TONOS[e.estado]}>{t(`distribuidor.entregas.estado.${e.estado}`)}</Badge></td>
                        <td><Boton tamano="S" tipo="secundario" onClick={() => setUbicaciones(e.numero)}>{t('distribuidor.entregas.ubicaciones.accion')}</Boton></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                </div>
              )}
              {entregadas.length > 0 && <Paginacion pagina={pagina} totalPaginas={totalPaginas} total={total} onCambiar={onCambiar} />}
            </div>
          </div>
        )}
      </section>
    </LayoutDistribuidor>
  )
}

/** Los momentos por los que ya paso la entrega, cada uno con su dia y hora, como quedan en la base de datos. */
const MOMENTOS = ['asignadaEn', 'retiradaEn', 'enCaminoEn', 'entregadaEn']

function Momentos({ entrega }) {
  const { t } = useTranslation()
  return (
    <ul className="mis-entregas__momentos" aria-label={t('distribuidor.entregas.momentos', { numero: entrega.numero })}>
      {MOMENTOS.filter((campo) => entrega[campo]).map((campo) => (
        <li key={campo} className="caption">{t(`distribuidor.entregas.${campo}`, { fecha: diaMesHora(entrega[campo]) })}</li>
      ))}
    </ul>
  )
}
