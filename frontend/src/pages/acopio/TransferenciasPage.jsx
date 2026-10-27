import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarTransferencias, obtenerTransferencia, recibirTransferencia } from '../../api/acopio.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Buscador from '../../components/Buscador.jsx'
import Campo from '../../components/Campo.jsx'
import DetalleTransferencia, { EstadoTransferencia, nombreLugar } from '../../components/DetalleTransferencia.jsx'
import Cargando from '../../components/Cargando.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LimpiarFiltros, { SinCoincidencias } from '../../components/LimpiarFiltros.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { coincide, fechaHora, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import '../productor/TransferenciasPage.css'

const SENTIDOS = ['ENTRANTE', 'SALIENTE']
const ESTADOS = ['INICIADA', 'SOLICITADA', 'PREPARADA', 'DESPACHADA', 'RECIBIDA']

/**
 * A-03 · Transferencias por recibir y su historial (CCPIP-278, RF-178). El servidor envia solo las
 * que entran o salen de los puntos de la sesion (RNF-05). Solo el punto de destino registra la
 * recepcion: recien ahi las unidades entran a un lote del punto y son vendibles. CCPIP-417: ambas
 * listas se buscan por texto y se filtran por sentido y estado.
 */
export default function TransferenciasPage({ path }) {
  const { t } = useTranslation()
  const { datos, setDatos, error, recargar } = useConsulta(listarTransferencias)
  const [abierta, setAbierta] = useState(null)
  const [recibiendo, setRecibiendo] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [aviso, setAviso] = useState(null)
  const [texto, setTexto] = useState('')
  const [sentido, setSentido] = useState('')
  const [estado, setEstado] = useState('')

  const filtrando = Boolean(texto.trim() || sentido || estado)
  const pasa = (tr) => (!sentido || tr.sentido === sentido) && (!estado || tr.estado === estado)
    && coincide(texto, tr.codigo, tr.productor, nombreLugar(tr.origen, t), nombreLugar(tr.destino, t), ...(tr.items ?? []).map((i) => i.producto))
  const porRecibir = datos?.porRecibir.filter(pasa) ?? []
  const todas = datos?.items.filter(pasa) ?? []

  function limpiar() {
    setTexto('')
    setSentido('')
    setEstado('')
  }

  async function verDetalle(tr) {
    if (abierta?.id === tr.id) {
      setAbierta(null)
      return
    }
    try {
      setAbierta(await obtenerTransferencia(tr.id))
    } catch (e) {
      setAviso({ tono: 'error', titulo: t('acopio.transferencias.errorDetalle'), descripcion: mensajeError(e, 'comun.errorConexion') })
    }
  }

  async function recibir() {
    setOcupado(true)
    try {
      const nueva = await recibirTransferencia(recibiendo.id)
      setDatos((d) => d && {
        ...d,
        porRecibir: d.porRecibir.filter((x) => x.id !== nueva.id),
        items: d.items.map((x) => (x.id === nueva.id ? { ...nueva, sentido: x.sentido } : x)),
      })
      if (abierta?.id === nueva.id) setAbierta(nueva)
      setAviso({ tono: 'exito', titulo: t('acopio.transferencias.recibida', { codigo: nueva.codigo }), descripcion: t('acopio.transferencias.recibidaTexto') })
    } catch (e) {
      setAviso({ tono: 'error', titulo: t('acopio.transferencias.errorRecibir'), descripcion: mensajeError(e, 'comun.errorConexion') })
    } finally {
      setOcupado(false)
      setRecibiendo(null)
    }
  }

  return (
    <LayoutAcopio path={path} punto={datos?.ubicacion} titulo={t('acopio.nav.transferencias')}>
      {aviso && <Alerta tono={aviso.tono} titulo={aviso.titulo} descripcion={aviso.descripcion} onCerrar={() => setAviso(null)} />}

      {error && (
        <EstadoVacio titulo={t('acopio.transferencias.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}

      {!datos && !error && <Cargando />}

      {!error && datos && (
        <>
          <div className="barra-filtros">
            <Buscador
              id="buscar-transferencia"
              etiqueta={t('acopio.transferencias.buscar')}
              placeholder={t('acopio.transferencias.buscar')}
              maxLength={100}
              value={texto}
              onChange={(e) => setTexto(e.target.value)}
              onBuscar={(e) => e.preventDefault()}
            />
            <Campo
              tipo="selector"
              etiqueta={t('acopio.transferencias.filtroSentido')}
              placeholder={t('acopio.transferencias.todos')}
              value={sentido}
              onChange={(e) => setSentido(e.target.value)}
              opciones={SENTIDOS.map((s) => ({ valor: s, etiqueta: t(`acopio.transferencias.sentido.${s}`) }))}
            />
            <Campo
              tipo="selector"
              etiqueta={t('acopio.transferencias.filtroEstado')}
              placeholder={t('acopio.transferencias.todos')}
              value={estado}
              onChange={(e) => setEstado(e.target.value)}
              opciones={ESTADOS.map((e) => ({ valor: e, etiqueta: t(`transferencias.estado.${e}`) }))}
            />
            <LimpiarFiltros activos={filtrando} onLimpiar={limpiar} />
          </div>

          <section className="productor__seccion" aria-labelledby="titulo-por-recibir">
            <h2 id="titulo-por-recibir" className="heading-h3">{t('acopio.transferencias.porRecibir', { total: porRecibir.length })}</h2>
            {porRecibir.length === 0 && (filtrando ? <SinCoincidencias /> : (
              <EstadoVacio titulo={t('acopio.transferencias.nadaPorRecibir')} descripcion={t('acopio.transferencias.nadaPorRecibirTexto')} />
            ))}
            {porRecibir.length > 0 && (
              <Lista
                etiqueta={t('acopio.transferencias.listaPorRecibir')}
                items={porRecibir}
                abierta={abierta}
                onDetalle={verDetalle}
                onRecibir={setRecibiendo}
              />
            )}
          </section>

          <section className="productor__seccion" aria-labelledby="titulo-todas">
            <h2 id="titulo-todas" className="heading-h3">{t('acopio.transferencias.todas')}</h2>
            {todas.length === 0 && (filtrando ? <SinCoincidencias /> : (
              <EstadoVacio titulo={t('acopio.transferencias.vacio')} descripcion={t('acopio.transferencias.vacioTexto')} />
            ))}
            {todas.length > 0 && (
              <Lista etiqueta={t('acopio.transferencias.listaTodas')} items={todas} abierta={abierta} onDetalle={verDetalle} />
            )}
          </section>
        </>
      )}

      <Dialogo
        abierto={!!recibiendo}
        titulo={recibiendo ? t('acopio.transferencias.confirmarTitulo', { codigo: recibiendo.codigo }) : ''}
        confirmar={t('acopio.transferencias.recibir')}
        onConfirmar={recibir}
        onCancelar={() => setRecibiendo(null)}
        ocupado={ocupado}
      >
        {recibiendo && (
          <p>{t('acopio.transferencias.confirmarTexto', { cantidad: unidades(recibiendo.cantidad), productor: recibiendo.productor, origen: nombreLugar(recibiendo.origen, t) })}</p>
        )}
      </Dialogo>
    </LayoutAcopio>
  )
}

function Lista({ etiqueta, items, abierta, onDetalle, onRecibir }) {
  const { t } = useTranslation()
  return (
    <ul className="transferencias__lista" aria-label={etiqueta}>
      {items.map((tr) => (
        <li key={tr.id} className="transferencias__item">
          <div className="transferencias__fila">
            <span className="transferencias__codigo label-m">{tr.codigo}</span>
            <span className="transferencias__ruta">
              <span className="caption">{tr.productor}</span>
              <span className="body-s">{t('transferencias.ruta', { origen: nombreLugar(tr.origen, t), destino: nombreLugar(tr.destino, t) })}</span>
            </span>
            <span className="transferencias__cantidad">{t('comun.und', { cantidad: unidades(tr.cantidad) })}</span>
            <span className="transferencias__fecha caption">{fechaHora(tr.actualizadaEn)}</span>
            {tr.sentido && <Badge tono={tr.sentido === 'ENTRANTE' ? 'marca' : 'neutro'} conPunto={false}>{t(`acopio.transferencias.sentido.${tr.sentido}`)}</Badge>}
            <EstadoTransferencia estado={tr.estado} />
            <span className="transferencias__acciones">
              <Boton tipo="terciario" tamano="S" aria-expanded={abierta?.id === tr.id} onClick={() => onDetalle(tr)}>
                {t(abierta?.id === tr.id ? 'transferencias.ocultarDetalle' : 'transferencias.verDetalle')}
              </Boton>
              {onRecibir && (
                <Boton tipo="primario" tamano="S" aria-label={t('acopio.transferencias.recibirEtiqueta', { codigo: tr.codigo })} onClick={() => onRecibir(tr)}>
                  {t('acopio.transferencias.recibir')}
                </Boton>
              )}
            </span>
          </div>
          {tr.estado === 'SOLICITADA' && <p className="productor__nota">{t('transferencias.pendienteAprobador')}</p>}
          {abierta?.id === tr.id && <DetalleTransferencia transferencia={abierta} />}
        </li>
      ))}
    </ul>
  )
}
