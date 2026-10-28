import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarPedidos, marcarListo, marcarListoParaDespacho, marcarPreparacion } from '../../api/acopio.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Buscador from '../../components/Buscador.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LimpiarFiltros, { SinCoincidencias } from '../../components/LimpiarFiltros.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { coincide, fechaCorta, fechaHora, precio, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
import DialogoRecojo from './DialogoRecojo.jsx'
import '../productor/productor.css'
import './PedidosPage.css'

/** Los estados del pedido en el orden del flujo del punto, uno por pestana. */
const ESTADOS = ['CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'LISTO_PARA_DESPACHO', 'EN_CAMINO', 'ENTREGADO']

/** CCPIP-419: las pestanas acordadas siempre estan; las de los estados intermedios, solo si tienen pedidos. */
const FIJAS = ['CONFIRMADO', 'LISTO_PARA_RECOJO', 'ENTREGADO']

/** El cambio de estado que el punto registra desde cada pestana. */
const ACCION = { CONFIRMADO: 'PREPARACION', EN_PREPARACION: 'LISTO', LISTO_PARA_RECOJO: 'RECOJO' }

const MODALIDADES = ['RECOJO', 'ENVIO']

/** CCPIP-417: lo que encuentra la busqueda de un pedido: su numero, sus productos y comunidades, y quien lo recogio. */
const textosPedido = (p) => [p.numero, p.recogidoPor, ...p.lineas.flatMap((l) => [l.producto, l.comunidad])]

/** Cada cambio de estado que el punto registra con un boton: a que API llama y como lo dice (textos de acopio.pedidos). */
const ACCIONES = {
  PREPARACION: {
    llamar: marcarPreparacion, exito: 'preparacionRegistrada', error: 'errorPreparacion',
    etiqueta: 'preparacionEtiqueta', texto: 'columna.EN_PREPARACION', ocupado: 'registrandoPreparacion',
  },
  LISTO: {
    llamar: marcarListo, exito: 'marcado', error: 'errorMarcar',
    etiqueta: 'marcarEtiqueta', texto: 'columna.LISTO_PARA_RECOJO', ocupado: 'marcando',
  },
  // CCPIP-320: el pedido a domicilio preparado sale del punto para que lo retire un distribuidor
  DESPACHO: {
    llamar: marcarListoParaDespacho, exito: 'despachoRegistrado', error: 'errorDespacho',
    etiqueta: 'despachoEtiqueta', texto: 'columna.LISTO_PARA_DESPACHO', ocupado: 'registrandoSalida',
  },
}

/** "Lote L-0247 · vence 02/11/2027 · 2 und": de donde sale lo que se entrega (CCPIP-74). */
function textoLote(lote) {
  const t = i18n.t.bind(i18n)
  const vence = lote.fechaVencimiento ? t('acopio.pedidos.vence', { fecha: fechaCorta(lote.fechaVencimiento) }) : t('productor.lote.noVence').toLowerCase()
  return t('acopio.pedidos.lote', { codigo: lote.codigo, vence, cantidad: unidades(lote.cantidad) })
}

/**
 * A-05 · CCPIP-68 y CCPIP-159: el administrador registra preparacion y luego disponibilidad para recojo;
 * el servidor guarda quien y cuando, y rechaza transiciones que ya cambiaron. CCPIP-320: un pedido a
 * domicilio preparado no se marca listo para recojo: se registra su salida y queda listo para despacho.
 * CCPIP-74: cada linea muestra los lotes asignados, el que vence antes primero, para saber que
 * entregar. CCPIP-71: el pedido listo se entrega con "Registrar recojo", que pide y valida en el
 * servidor quien recoge y su celular. CCPIP-419: los pedidos se ven en una tabla, con una pestana por
 * estado. CCPIP-417: se buscan por texto y se filtran por modalidad; los conteos de las pestanas son los
 * de lo encontrado.
 */
export default function PedidosPage({ path }) {
  const { t } = useTranslation()
  const { datos, setDatos, error, recargar } = useConsulta(listarPedidos)
  const [estado, setEstado] = useState('CONFIRMADO')
  const [texto, setTexto] = useState('')
  const [modalidad, setModalidad] = useState('')
  const [aviso, setAviso] = useState(null)
  const [marcando, setMarcando] = useState(null)
  const [recojo, setRecojo] = useState(null)

  const filtrando = Boolean(texto.trim() || modalidad)
  const items = (datos?.items ?? []).filter((p) => (!modalidad || p.modalidad === modalidad) && coincide(texto, ...textosPedido(p)))
  const delEstado = (e) => items.filter((p) => p.estado === e)
  const titulo = (e) => t(`acopio.pedidos.columna.${e}`)
  const pestanas = ESTADOS.filter((e) => FIJAS.includes(e) || e === estado || delEstado(e).length > 0)
  const visibles = delEstado(estado)

  /** Pone el pedido que devolvio el servidor en su lugar: cambia de pestana sin volver a pedir la lista. */
  function reemplazar(pedido) {
    setDatos((d) => ({ ...d, items: d.items.map((p) => (p.numero === pedido.numero ? pedido : p)) }))
  }

  async function marcar(numero, accion) {
    setMarcando(numero)
    setAviso(null)
    const { llamar, exito, error } = ACCIONES[accion]
    try {
      reemplazar(await llamar(numero))
      setAviso({ tono: 'exito', titulo: t(`acopio.pedidos.${exito}`, { numero }) })
    } catch (e) {
      // Un 401 ya lo maneja la app; aqui se explican las reglas de negocio
      if (e.status === 401) return
      setAviso({ tono: 'error', titulo: t(`acopio.pedidos.${error}`), descripcion: mensajeError(e) })
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

      {!datos && !error && <Cargando />}

      {!error && datos?.items.length === 0 && (
        <EstadoVacio titulo={t('acopio.pedidos.vacio')} descripcion={t('acopio.pedidos.vacioTexto')} />
      )}

      {!error && datos?.items.length > 0 && (
        <>
          <div className="barra-filtros">
            <Buscador
              id="buscar-pedido-punto"
              etiqueta={t('acopio.pedidos.buscar')}
              placeholder={t('acopio.pedidos.buscar')}
              maxLength={100}
              value={texto}
              onChange={(e) => setTexto(e.target.value)}
              onBuscar={(e) => e.preventDefault()}
            />
            <Campo
              tipo="selector"
              etiqueta={t('acopio.pedidos.modalidad')}
              placeholder={t('acopio.pedidos.todasModalidades')}
              value={modalidad}
              onChange={(e) => setModalidad(e.target.value)}
              opciones={MODALIDADES.map((m) => ({ valor: m, etiqueta: t(`acopio.pedidos.modalidadOpcion.${m}`) }))}
            />
            <LimpiarFiltros activos={filtrando} onLimpiar={() => { setTexto(''); setModalidad('') }} />
          </div>
          <Pestanas
            etiqueta={t('acopio.pedidos.estados')}
            valor={estado}
            onCambiar={setEstado}
            opciones={pestanas.map((e) => ({ valor: e, etiqueta: t('comun.conteo', { titulo: titulo(e), total: delEstado(e).length }) }))}
          />
          <section role="tabpanel" aria-label={titulo(estado)}>
            {visibles.length === 0 && (filtrando ? <SinCoincidencias /> : <EstadoVacio titulo={t(`acopio.pedidos.columnaVacia.${estado}`)} />)}
            {visibles.length > 0 && (
              <TablaPedidos pedidos={visibles} accion={ACCION[estado]} marcando={marcando} onMarcar={marcar} onRecojo={setRecojo} />
            )}
          </section>
        </>
      )}

      {recojo && (
        <DialogoRecojo key={recojo.numero} pedido={recojo} onCancelar={() => setRecojo(null)} onRegistrado={recogido} onDesactualizado={desactualizado} />
      )}
    </LayoutAcopio>
  )
}

/** CCPIP-419: los pedidos de la pestana elegida, uno por fila. La columna de accion solo si el estado tiene una. */
function TablaPedidos({ pedidos, accion, marcando, onMarcar, onRecojo }) {
  const { t } = useTranslation()
  return (
    <div className="tabla">
      <div className="tabla__fila tabla__fila--encabezado" aria-hidden="true">
        <span className="tabla__celda pedidos__col-numero">{t('acopio.pedidos.col.pedido')}</span>
        <span className="tabla__celda tabla__celda--flex">{t('acopio.pedidos.col.productos')}</span>
        <span className="tabla__celda pedidos__col-estado">{t('acopio.pedidos.col.estado')}</span>
        <span className="tabla__celda pedidos__col-total">{t('acopio.pedidos.col.total')}</span>
        {accion && <span className="tabla__celda pedidos__col-accion">{t('acopio.pedidos.col.accion')}</span>}
      </div>
      <ul className="pedidos__lista" aria-label={t('acopio.pedidos.lista')}>
        {pedidos.map((p) => (
          <FilaPedido key={p.numero} pedido={p} accion={accion} ocupado={marcando === p.numero} onMarcar={onMarcar} onRecojo={onRecojo} />
        ))}
      </ul>
    </div>
  )
}

function FilaPedido({ pedido, accion, ocupado, onMarcar, onRecojo }) {
  const { t } = useTranslation()
  const cambio = [...pedido.historial].reverse().find((c) => c.estado === pedido.estado)
  const cuando = fechaHora(cambio?.registradoEn ?? pedido.confirmadoEn)
  // CCPIP-59: va al domicilio del comprador; en preparacion se registra su salida en vez de marcarlo listo (CCPIP-320)
  const aDomicilio = pedido.modalidad === 'ENVIO'
  const accionFila = accion === 'LISTO' && aDomicilio ? 'DESPACHO' : accion
  const boton = ACCIONES[accionFila]
  return (
    <li className="tabla__fila pedidos__fila">
      <div className="tabla__celda pedidos__col-numero">
        <p className="pedidos__numero">{pedido.numero}</p>
        {aDomicilio && <Badge tono="info" conPunto={false}>{t('acopio.pedidos.aDomicilio')}</Badge>}
      </div>
      <div className="tabla__celda tabla__celda--flex">
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
      </div>
      <div className="tabla__celda pedidos__col-estado">
        <p className="caption pedidos__pie">
          {`${t(`acopio.pedidos.pie.${pedido.estado}`, { defaultValue: pedido.estado })} ${cuando}${cambio?.responsable ? ` · ${cambio.responsable}` : ''}`}
        </p>
        {pedido.recogidoPor && <p className="caption pedidos__recogio">{t('acopio.pedidos.recogio', { persona: pedido.recogidoPor })}</p>}
        {pedido.estado === 'LISTO_PARA_DESPACHO' && <p className="caption">{t('acopio.pedidos.esperaDistribuidor')}</p>}
      </div>
      <div className="tabla__celda pedidos__col-total label-m">{precio(pedido.total)}</div>
      {accion && (
        <div className="tabla__celda pedidos__col-accion">
          {boton && (
            <Boton
              tipo="secundario"
              tamano="M"
              anchoCompleto
              cargando={ocupado}
              aria-label={t(`acopio.pedidos.${boton.etiqueta}`, { numero: pedido.numero })}
              onClick={() => onMarcar(pedido.numero, accionFila)}
            >
              {t(`acopio.pedidos.${ocupado ? boton.ocupado : boton.texto}`)}
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
        </div>
      )}
    </li>
  )
}
