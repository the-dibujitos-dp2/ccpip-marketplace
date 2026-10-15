import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarPedidos, marcarListo } from '../../api/acopio.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { fechaCorta, fechaHora, precio, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
import DialogoRecojo from './DialogoRecojo.jsx'
import './PedidosPage.css'

/** Columnas de A-05 en Sprint 0: preparacion, reparto y recogido por el distribuidor llegan con sus historias. */
const COLUMNAS = [
  { estado: 'CONFIRMADO', accion: 'LISTO' },
  { estado: 'LISTO_PARA_RECOJO', accion: 'RECOJO' },
  { estado: 'ENTREGADO' },
]

/** "Lote L-0247 · vence 02/11/2027 · 2 und": de donde sale lo que se entrega (CCPIP-74). */
function textoLote(lote) {
  const t = i18n.t.bind(i18n)
  const vence = lote.fechaVencimiento ? t('acopio.pedidos.vence', { fecha: fechaCorta(lote.fechaVencimiento) }) : t('productor.lote.noVence').toLowerCase()
  return t('acopio.pedidos.lote', { codigo: lote.codigo, vence, cantidad: unidades(lote.cantidad) })
}

/**
 * A-05 · CCPIP-159 (RF-128): los pedidos del punto por estado. El administrador marca un pedido
 * confirmado como listo para recojo; el servidor guarda quien y cuando, y rechaza lo que ya cambio.
 * CCPIP-74: cada linea muestra los lotes asignados, el que vence antes primero, para saber que
 * entregar. CCPIP-71: el pedido listo se entrega con "Registrar recojo", que pide y valida en el
 * servidor quien recoge y su celular.
 */
export default function PedidosPage({ path }) {
  const { t } = useTranslation()
  const { datos, setDatos, error, recargar } = useConsulta(listarPedidos)
  const [aviso, setAviso] = useState(null)
  const [marcando, setMarcando] = useState(null)
  const [recojo, setRecojo] = useState(null)

  /** Pone el pedido que devolvio el servidor en su lugar: cambia de columna sin volver a pedir la lista. */
  function reemplazar(pedido) {
    setDatos((d) => ({ ...d, items: d.items.map((p) => (p.numero === pedido.numero ? pedido : p)) }))
  }

  async function marcar(numero) {
    setMarcando(numero)
    setAviso(null)
    try {
      const pedido = await marcarListo(numero)
      reemplazar(pedido)
      setAviso({ tono: 'exito', titulo: t('acopio.pedidos.marcado', { numero }) })
    } catch (e) {
      // Un 401 ya lo maneja la app; aqui se explican las reglas de negocio
      if (e.status === 401) return
      setAviso({ tono: 'error', titulo: t('acopio.pedidos.errorMarcar'), descripcion: mensajeError(e) })
      // Si otra persona ya lo cambio, la pantalla estaba desactualizada
      if (e.status === 404 || e.status === 409) recargar()
    } finally {
      setMarcando(null)
    }
  }

  function recogido(pedido) {
    reemplazar(pedido)
    setRecojo(null)
    setAviso({ tono: 'exito', titulo: t('acopio.pedidos.entregado', { numero: pedido.numero, persona: pedido.recogidoPor }) })
  }

  /** El pedido cambio mientras se llenaba el dialogo (otra persona lo entrego) o ya no es de este punto. */
  function desactualizado(e) {
    setRecojo(null)
    setAviso({ tono: 'error', titulo: t('acopio.recojo.error'), descripcion: mensajeError(e) })
    recargar()
  }

  return (
    <LayoutAcopio path={path} punto={datos?.ubicacion} titulo={t('acopio.nav.pedidos')}>
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}

      {error && (
        <EstadoVacio titulo={t('acopio.pedidos.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}

      {!error && datos?.items.length === 0 && (
        <EstadoVacio titulo={t('acopio.pedidos.vacio')} descripcion={t('acopio.pedidos.vacioTexto')} />
      )}

      {!error && datos?.items.length > 0 && (
        <div className="pedidos__columnas">
          {COLUMNAS.map((c) => {
            const pedidos = datos.items.filter((p) => p.estado === c.estado)
            return (
              <section key={c.estado} className="pedidos__columna" aria-label={t(`acopio.pedidos.columna.${c.estado}`)}>
                <h2 className="heading-h3">{t('acopio.pedidos.columnaConteo', { titulo: t(`acopio.pedidos.columna.${c.estado}`), total: pedidos.length })}</h2>
                {pedidos.length === 0 && <p className="caption">{t(`acopio.pedidos.columnaVacia.${c.estado}`)}</p>}
                <ul className="pedidos__lista">
                  {pedidos.map((p) => (
                    <TarjetaPedido key={p.numero} pedido={p} accion={c.accion} ocupado={marcando === p.numero} onMarcar={marcar} onRecojo={setRecojo} />
                  ))}
                </ul>
              </section>
            )
          })}
        </div>
      )}

      {recojo && (
        <DialogoRecojo key={recojo.numero} pedido={recojo} onCancelar={() => setRecojo(null)} onRegistrado={recogido} onDesactualizado={desactualizado} />
      )}
    </LayoutAcopio>
  )
}

function TarjetaPedido({ pedido, accion, ocupado, onMarcar, onRecojo }) {
  const { t } = useTranslation()
  const cambio = [...pedido.historial].reverse().find((c) => c.estado === pedido.estado)
  const cuando = fechaHora(cambio?.registradoEn ?? pedido.confirmadoEn)
  return (
    <li className="pedidos__tarjeta">
      <p className="pedidos__numero">{pedido.numero}</p>
      <ul className="pedidos__lineas" aria-label={t('acopio.pedidos.productosDe', { numero: pedido.numero })}>
        {pedido.lineas.map((l) => (
          <li key={`${l.comunidad}-${l.producto}`}>
            <span className="caption">{`${l.comunidad} · ${l.producto} × ${unidades(l.cantidad)}`}</span>
            <ul className="pedidos__lotes" aria-label={t('acopio.pedidos.lotesDe', { producto: l.producto })}>
              {l.lotes.length === 0 && <li className="caption">{t('acopio.pedidos.sinLotes')}</li>}
              {l.lotes.map((lote) => (
                <li key={lote.codigo} className="caption">{textoLote(lote)}</li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
      {pedido.recogidoPor && <p className="caption pedidos__recogio">{t('acopio.pedidos.recogio', { persona: pedido.recogidoPor })}</p>}
      <p className="caption pedidos__pie">
        {`${t(`acopio.pedidos.pie.${pedido.estado}`, { defaultValue: pedido.estado })} ${cuando}${cambio?.responsable ? ` · ${cambio.responsable}` : ''} · ${precio(pedido.total)}`}
      </p>
      {accion === 'LISTO' && (
        <Boton
          tipo="secundario"
          tamano="M"
          anchoCompleto
          disabled={ocupado}
          aria-label={t('acopio.pedidos.marcarEtiqueta', { numero: pedido.numero })}
          onClick={() => onMarcar(pedido.numero)}
        >
          {ocupado ? t('acopio.pedidos.marcando') : t('acopio.pedidos.columna.LISTO_PARA_RECOJO')}
        </Boton>
      )}
      {accion === 'RECOJO' && (
        <Boton
          tipo="secundario"
          tamano="M"
          anchoCompleto
          aria-label={t('acopio.pedidos.recojoEtiqueta', { numero: pedido.numero })}
          onClick={() => onRecojo(pedido)}
        >
          {t('acopio.recojo.registrar')}
        </Boton>
      )}
    </li>
  )
}
