import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarTransferencias, obtenerTransferencia, recibirTransferencia } from '../../api/acopio.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import DetalleTransferencia, { EstadoTransferencia, nombreLugar } from '../../components/DetalleTransferencia.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { fechaHora, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import '../productor/TransferenciasPage.css'

/**
 * A-03 · Transferencias por recibir y su historial (CCPIP-278, RF-178). El servidor envia solo las
 * que entran o salen de los puntos de la sesion (RNF-05). Solo el punto de destino registra la
 * recepcion: recien ahi las unidades entran a un lote del punto y son vendibles.
 */
export default function TransferenciasPage({ path }) {
  const { t } = useTranslation()
  const { datos, setDatos, error, recargar } = useConsulta(listarTransferencias)
  const [abierta, setAbierta] = useState(null)
  const [recibiendo, setRecibiendo] = useState(null)
  const [ocupado, setOcupado] = useState(false)
  const [aviso, setAviso] = useState(null)

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

      {!error && datos && (
        <>
          <section className="productor__seccion" aria-labelledby="titulo-por-recibir">
            <h2 id="titulo-por-recibir" className="heading-h3">{t('acopio.transferencias.porRecibir', { total: datos.porRecibir.length })}</h2>
            {datos.porRecibir.length === 0 ? (
              <EstadoVacio titulo={t('acopio.transferencias.nadaPorRecibir')} descripcion={t('acopio.transferencias.nadaPorRecibirTexto')} />
            ) : (
              <Lista
                etiqueta={t('acopio.transferencias.listaPorRecibir')}
                items={datos.porRecibir}
                abierta={abierta}
                onDetalle={verDetalle}
                onRecibir={setRecibiendo}
              />
            )}
          </section>

          <section className="productor__seccion" aria-labelledby="titulo-todas">
            <h2 id="titulo-todas" className="heading-h3">{t('acopio.transferencias.todas')}</h2>
            {datos.items.length === 0 ? (
              <EstadoVacio titulo={t('acopio.transferencias.vacio')} descripcion={t('acopio.transferencias.vacioTexto')} />
            ) : (
              <Lista etiqueta={t('acopio.transferencias.listaTodas')} items={datos.items} abierta={abierta} onDetalle={verDetalle} />
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
