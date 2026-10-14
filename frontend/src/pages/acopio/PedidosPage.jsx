import { useState } from 'react'
import { listarPedidos, marcarListo } from '../../api/acopio.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { fechaCorta, fechaHora, precio } from '../../formato.js'
import DialogoRecojo from './DialogoRecojo.jsx'
import './PedidosPage.css'

/** Columnas de A-05 en Sprint 0: preparacion, reparto y recogido por el distribuidor llegan con sus historias. */
const COLUMNAS = [
  { estado: 'CONFIRMADO', titulo: 'Confirmado', accion: 'LISTO', vacio: 'No hay pedidos por preparar.' },
  { estado: 'LISTO_PARA_RECOJO', titulo: 'Listo para recojo', accion: 'RECOJO', vacio: 'Ningún pedido espera a su comprador.' },
  { estado: 'ENTREGADO', titulo: 'Entregado hoy', vacio: 'Aún no se entregó ningún pedido hoy.' },
]

/** Lo que cuenta el pie de cada tarjeta: cuando paso a su estado actual y, si fue el personal del punto, quien. */
const PIE = { CONFIRMADO: 'Confirmado', LISTO_PARA_RECOJO: 'Listo', ENTREGADO: 'Entregado' }

const unidades = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 3 })

/** "Lote L-0247 · vence 02/11/2027 · 2 und": de donde sale lo que se entrega (CCPIP-74). */
function textoLote(lote) {
  const vence = lote.fechaVencimiento ? `vence ${fechaCorta(lote.fechaVencimiento)}` : 'no vence'
  return `Lote ${lote.codigo} · ${vence} · ${unidades.format(lote.cantidad)} und`
}

/**
 * A-05 · CCPIP-159 (RF-128): los pedidos del punto por estado. El administrador marca un pedido
 * confirmado como listo para recojo; el servidor guarda quien y cuando, y rechaza lo que ya cambio.
 * CCPIP-74: cada linea muestra los lotes asignados, el que vence antes primero, para saber que
 * entregar. CCPIP-71: el pedido listo se entrega con "Registrar recojo", que pide y valida en el
 * servidor quien recoge y su celular.
 */
export default function PedidosPage({ path }) {
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
      setAviso({ tono: 'exito', titulo: `El pedido ${numero} está listo para recojo` })
    } catch (e) {
      // Un 401 ya lo maneja la app; aqui se explican las reglas de negocio
      if (e.status === 401) return
      setAviso({ tono: 'error', titulo: 'No pudimos marcar el pedido', descripcion: e.message })
      // Si otra persona ya lo cambio, la pantalla estaba desactualizada
      if (e.status === 404 || e.status === 409) recargar()
    } finally {
      setMarcando(null)
    }
  }

  function recogido(pedido) {
    reemplazar(pedido)
    setRecojo(null)
    setAviso({ tono: 'exito', titulo: `El pedido ${pedido.numero} se entregó a ${pedido.recogidoPor}` })
  }

  /** El pedido cambio mientras se llenaba el dialogo (otra persona lo entrego) o ya no es de este punto. */
  function desactualizado(e) {
    setRecojo(null)
    setAviso({ tono: 'error', titulo: 'No pudimos registrar el recojo', descripcion: e.message })
    recargar()
  }

  return (
    <LayoutAcopio path={path} punto={datos?.ubicacion} titulo="Pedidos">
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}

      {error && (
        <EstadoVacio titulo="No pudimos cargar los pedidos" descripcion={error.message}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>Reintentar</Boton>
        </EstadoVacio>
      )}

      {!error && datos?.items.length === 0 && (
        <EstadoVacio titulo="Aún no hay pedidos" descripcion="Aquí verás los pedidos que los compradores recogerán en tu punto." />
      )}

      {!error && datos?.items.length > 0 && (
        <div className="pedidos__columnas">
          {COLUMNAS.map((c) => {
            const pedidos = datos.items.filter((p) => p.estado === c.estado)
            return (
              <section key={c.estado} className="pedidos__columna" aria-label={c.titulo}>
                <h2 className="heading-h3">{`${c.titulo} (${pedidos.length})`}</h2>
                {pedidos.length === 0 && <p className="caption">{c.vacio}</p>}
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
  const cambio = [...pedido.historial].reverse().find((c) => c.estado === pedido.estado)
  const cuando = fechaHora(cambio?.registradoEn ?? pedido.confirmadoEn)
  return (
    <li className="pedidos__tarjeta">
      <p className="pedidos__numero">{pedido.numero}</p>
      <ul className="pedidos__lineas" aria-label={`Productos del pedido ${pedido.numero}`}>
        {pedido.lineas.map((l) => (
          <li key={`${l.comunidad}-${l.producto}`}>
            <span className="caption">{`${l.comunidad} · ${l.producto} × ${unidades.format(l.cantidad)}`}</span>
            <ul className="pedidos__lotes" aria-label={`Lotes de ${l.producto}`}>
              {l.lotes.length === 0 && <li className="caption">Sin lotes asignados</li>}
              {l.lotes.map((lote) => (
                <li key={lote.codigo} className="caption">{textoLote(lote)}</li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
      {pedido.recogidoPor && <p className="caption pedidos__recogio">{`Recogió ${pedido.recogidoPor}`}</p>}
      <p className="caption pedidos__pie">
        {`${PIE[pedido.estado]} ${cuando}${cambio?.responsable ? ` · ${cambio.responsable}` : ''} · ${precio(pedido.total)}`}
      </p>
      {accion === 'LISTO' && (
        <Boton
          tipo="secundario"
          tamano="M"
          anchoCompleto
          disabled={ocupado}
          aria-label={`Marcar el pedido ${pedido.numero} como listo para recojo`}
          onClick={() => onMarcar(pedido.numero)}
        >
          {ocupado ? 'Marcando…' : 'Listo para recojo'}
        </Boton>
      )}
      {accion === 'RECOJO' && (
        <Boton
          tipo="secundario"
          tamano="M"
          anchoCompleto
          aria-label={`Registrar el recojo del pedido ${pedido.numero}`}
          onClick={() => onRecojo(pedido)}
        >
          Registrar recojo
        </Boton>
      )}
    </li>
  )
}
