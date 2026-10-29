import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoInfo from '../../assets/icons/info-pequeno.svg'
import { listarPedidos } from '../../api/productor.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import { diaMes, fechaCorta, fechaHora, precio, unidades } from '../../formato.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './PedidosPage.css'

/** Las pestanas acordadas para PW-07: los estados reales del pedido. EN_PREPARACION aparece si hay alguno. */
const ESTADOS = ['CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'ENTREGADO', 'CANCELADO']

const TONO = { CONFIRMADO: 'info', EN_PREPARACION: 'aviso', LISTO_PARA_RECOJO: 'marca', ENTREGADO: 'exito', CANCELADO: 'neutro' }

/**
 * PW-07 · CCPIP-66 (RF-48): los pedidos que incluyen publicaciones del productor y su estado. Solo
 * consulta: el servidor envia unicamente sus lineas e importes, sin el comprador ni otros productores.
 */
export default function PedidosPage({ path }) {
  const { t } = useTranslation()
  const [estado, setEstado] = useState('CONFIRMADO')
  const [elegido, setElegido] = useState(null)
  const consultar = useCallback(() => listarPedidos(estado), [estado])
  const { datos, error } = useConsulta(consultar)

  const items = datos?.items ?? []
  const pedido = items.find((p) => p.numero === elegido) ?? items[0]
  const paginacion = usePaginacionLocal(items, estado)
  useEffect(() => setElegido(null), [estado])

  const conteos = datos?.conteos ?? {}
  const pestanas = ESTADOS.filter((e) => e !== 'EN_PREPARACION' || conteos[e] > 0 || estado === e)

  return (
    <PaginaProductor path={path} seccion={t('productor.nav.pedidos')} titulo={t('productor.pedidos.titulo')}>
      <Pestanas
        etiqueta={t('productor.pedidos.titulo')}
        valor={estado}
        onCambiar={setEstado}
        opciones={pestanas.map((e) => ({ valor: e, etiqueta: t(`productor.pedidos.pestana.${e}`, { total: conteos[e] ?? 0 }) }))}
      />

      {error && <Alerta tono="error" titulo={t('productor.pedidos.error')} descripcion={t('comun.errorConexion')} />}
      {!datos && !error && <Cargando />}

      {datos && items.length === 0 && (
        <EstadoVacio titulo={t(`productor.pedidos.vacio.${estado}`)} descripcion={t('productor.pedidos.vacioTexto')} />
      )}

      {items.length > 0 && (
        <div className="pedidos-productor">
          <div className="tabla pedidos-productor__tabla">
            <div className="tabla__fila tabla__fila--encabezado" aria-hidden="true">
              <span className="tabla__celda pedidos-productor__col-numero">{t('productor.pedidos.col.pedido')}</span>
              <span className="tabla__celda tabla__celda--flex">{t('productor.tabla.producto')}</span>
              <span className="tabla__celda pedidos-productor__col-modalidad">{t('productor.pedidos.col.modalidad')}</span>
              <span className="tabla__celda pedidos-productor__col-fecha">{t('productor.pedidos.col.pagado')}</span>
              <span className="tabla__celda pedidos-productor__col-estado">{t('productor.lote.tabla.estado')}</span>
            </div>
            <ul className="pedidos-productor__lista" aria-label={t('productor.pedidos.titulo')}>
              {paginacion.filas.map((p) => (
                <li key={p.numero}>
                  <button
                    type="button"
                    className={`tabla__fila pedidos-productor__fila${p.numero === pedido?.numero ? ' pedidos-productor__fila--activa' : ''}`}
                    aria-pressed={p.numero === pedido?.numero}
                    aria-label={t('productor.pedidos.verPedido', { numero: p.numero })}
                    onClick={() => setElegido(p.numero)}
                  >
                    <span className="tabla__celda pedidos-productor__col-numero label-m">{p.numero}</span>
                    <span className="tabla__celda tabla__celda--flex">{resumen(p, t)}</span>
                    <span className="tabla__celda pedidos-productor__col-modalidad">{modalidad(p, t)}</span>
                    <span className="tabla__celda pedidos-productor__col-fecha">{diaMes(p.confirmadoEn)}</span>
                    <span className="tabla__celda pedidos-productor__col-estado">
                      <Badge tono={TONO[p.estado]}>{t(`productor.pedidos.estado.${p.estado}`)}</Badge>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
          {pedido && <Detalle pedido={pedido} />}
          <Paginacion {...paginacion} />
        </div>
      )}

      <p className="productor__nota">
        <Icono src={iconoInfo} tamano={16} />
        {t('productor.pedidos.nota')}
      </p>
    </PaginaProductor>
  )
}

/** "Aceite de sacha inchi × 1" o, con varias lineas propias, la primera y cuantas mas. */
function resumen(p, t) {
  const [primera, ...resto] = p.lineas
  const texto = `${primera.producto} × ${unidades(primera.cantidad)}`
  return resto.length ? t('productor.pedidos.yMas', { texto, count: resto.length }) : texto
}

function modalidad(p, t) {
  return p.modalidad === 'RECOJO'
    ? t('productor.pedidos.recojoEn', { punto: p.puntoRecojo.replace(/^Centro (Nacional|Regional) /, '') })
    : t('productor.pedidos.envio')
}

/** Panel derecho de PW-07: lineas propias, lotes asignados, importes y cambios de estado. */
function Detalle({ pedido }) {
  const { t } = useTranslation()
  return (
    <aside className="tarjeta-panel pedidos-productor__detalle" aria-labelledby="detalle-pedido">
      <div className="pedidos-productor__detalle-cabecera">
        <div>
          <h2 id="detalle-pedido" className="heading-h3">{pedido.numero}</h2>
          <p className="caption">
            {t('productor.pedidos.pagadoEl', { fecha: diaMes(pedido.confirmadoEn) })} · {modalidad(pedido, t)}
          </p>
        </div>
        <Badge tono={TONO[pedido.estado]}>{t(`productor.pedidos.estado.${pedido.estado}`)}</Badge>
      </div>

      <ul className="pedidos-productor__lineas" aria-label={t('productor.pedidos.tusProductos')}>
        {pedido.lineas.map((l) => (
          <li key={l.productoId} className="pedidos-productor__linea">
            <ImagenProducto url={l.imagen} className="tabla__miniatura" />
            <div>
              <p className="label-m">{`${l.producto} × ${unidades(l.cantidad)}`}</p>
              <p className="caption">{`${precio(l.precioUnitario)} c/u · ${precio(l.subtotal)}`}</p>
              {l.lotes.map((lote) => (
                <p key={lote.codigo} className="caption">
                  {lote.fechaVencimiento
                    ? t('productor.pedidos.loteVence', { codigo: lote.codigo, cantidad: unidades(lote.cantidad), fecha: fechaCorta(lote.fechaVencimiento) })
                    : t('productor.pedidos.lote', { codigo: lote.codigo, cantidad: unidades(lote.cantidad) })}
                </p>
              ))}
            </div>
          </li>
        ))}
      </ul>

      <dl className="pedidos-productor__importes">
        <dt>{t('productor.pedidos.subtotal')}</dt>
        <dd>{precio(pedido.subtotal)}</dd>
        {pedido.comision != null && (
          <>
            <dt>{t('productor.pedidos.comision')}</dt>
            <dd>{`− ${precio(pedido.comision)}`}</dd>
            <dt className="label-m">{t('productor.pedidos.neto')}</dt>
            <dd className="label-m">{precio(pedido.neto)}</dd>
          </>
        )}
      </dl>
      {pedido.comision == null && <p className="caption">{t('productor.pedidos.sinComision')}</p>}

      <section aria-labelledby="historial-pedido" className="pedidos-productor__historial">
        <h3 id="historial-pedido" className="heading-h4">{t('productor.pedidos.historial')}</h3>
        <ol>
          {pedido.historial.map((c) => (
            <li key={`${c.estado}-${c.registradoEn}`}>
              <span>{t(`productor.pedidos.estado.${c.estado}`)}</span>
              <span className="caption">{fechaHora(c.registradoEn)}</span>
            </li>
          ))}
        </ol>
      </section>
    </aside>
  )
}
