import { useCallback, useEffect, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import { consultarPedido } from '../../api/compra.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import Pasos from '../../components/Pasos.jsx'
import SeguimientoPedido, { conSeguimiento } from '../../components/SeguimientoPedido.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { fechaHora, precio, unidades } from '../../formato.js'
import { RUTAS } from '../../router.js'
import './PedidoPage.css'

const PASOS = ['carrito', 'entrega', 'confirmacion']

const DE_LA_COMPRA = ['PENDIENTE_PAGO', 'CONFIRMADO', 'CANCELADO']

const TONO_PAGO = { APROBADO: 'exito', RECHAZADO: 'error', ERROR: 'error', PENDIENTE: 'aviso', ANULADO: 'neutro' }

/**
 * CW-05, paso "Confirmación" · CCPIP-64: numero de pedido, articulos e importes con el precio
 * pagado (no el vigente), punto de recojo y quien recoge. CCPIP-163: cada intento de pago con su
 * estado y monto, sin datos de la tarjeta salvo la marca. CCPIP-70 y CCPIP-340: la etapa del pedido y
 * su historial de estados en orden cronologico.
 */
export default function PedidoPage({ id }) {
  const { t } = useTranslation()
  const [pedido, setPedido] = useState(null)
  const [error, setError] = useState(null)

  const cargar = useCallback(() => {
    setError(null)
    consultarPedido(id)
      .then(setPedido)
      .catch((e) => e.status !== 401 && setError(e))
  }, [id])

  useEffect(cargar, [cargar])

  return (
    <LayoutTienda>
      <main className="contenedor pedido" aria-busy={!pedido && !error}>
        {/* Los pasos de la compra solo tienen sentido hasta la confirmacion; despues, el seguimiento (CCPIP-70) */}
        {(!pedido || DE_LA_COMPRA.includes(pedido.estado)) && (
          <Pasos pasos={PASOS.map((p) => t(`carrito.pasos.${p}`))} actual={pedido?.estado === 'PENDIENTE_PAGO' ? 1 : 2} etiqueta={t('carrito.pasos.etiqueta')} />
        )}
        {error?.status === 404 && (
          <EstadoVacio titulo={t('pedido.noEncontrado')} descripcion={t('pedido.noEncontradoTexto')}>
            <Boton tamano="M" to={RUTAS.catalogo}>
              {t('pedido.irTienda')}
            </Boton>
          </EstadoVacio>
        )}
        {error && error.status !== 404 && (
          <EstadoVacio titulo={t('pedido.error')} descripcion={t('comun.errorConexion')}>
            <Boton tamano="M" onClick={cargar}>
              {t('carrito.reintentar')}
            </Boton>
          </EstadoVacio>
        )}
        {!error && !pedido && <p className="visually-hidden">{t('pedido.cargando')}</p>}
        {pedido && <Detalle pedido={pedido} />}
      </main>
    </LayoutTienda>
  )
}

/**
 * CCPIP-167 (RF-136): lo que quedo fijado al confirmar. Los precios de cada linea ya se ven arriba;
 * aqui la modalidad y la comision de la Camara, incluida en el total (no se suma). Cambios
 * posteriores de la comision no alteran este pedido.
 */
function Condiciones({ pedido }) {
  const { t } = useTranslation()
  const { comision } = pedido
  return (
    <section className="pedido__tarjeta" aria-labelledby="pedido-condiciones">
      <h2 id="pedido-condiciones" className="pedido__tarjeta-titulo">{t('pedido.condiciones.titulo')}</h2>
      <dl className="pedido__datos">
        <div>
          <dt>{t('pedido.condiciones.precios')}</dt>
          <dd>{t('pedido.condiciones.preciosFijados', { fecha: fechaHora(pedido.confirmadoEn) })}</dd>
        </div>
        <div>
          <dt>{t('pedido.condiciones.modalidad')}</dt>
          <dd>{t(`pedido.condiciones.modalidades.${pedido.modalidad}`, { defaultValue: pedido.modalidad })}</dd>
        </div>
        <div>
          <dt>{t('pedido.condiciones.comision')}</dt>
          <dd>
            <span>
              {comision
                ? t('pedido.condiciones.comisionMonto', { porcentaje: unidades(comision.porcentaje), monto: precio(comision.monto) })
                : t('pedido.condiciones.sinComision')}
            </span>
            {comision && <span className="caption">{t('pedido.condiciones.incluida')}</span>}
          </dd>
        </div>
      </dl>
    </section>
  )
}

function Detalle({ pedido }) {
  const { t } = useTranslation()
  // "Gracias por tu compra" al confirmarse; si ya avanzo (listo, entregado), el pedido con su numero
  const confirmado = pedido.estado === 'CONFIRMADO'
  const pagoAprobado = pedido.pagos.find((p) => p.estado === 'APROBADO')
  const punto = pedido.punto
  return (
    <>
      {confirmado ? (
        <header className="pedido__exito">
          <Icono src={iconoCheck} tamano={48} className="pedido__exito-icono" />
          <h1 className="pedido__titulo">{t('pedido.gracias')}</h1>
          <p className="pedido__subtitulo">
            <Trans i18nKey="pedido.confirmado" values={{ numero: pedido.numero }} components={{ strong: <strong /> }} />
          </p>
        </header>
      ) : (
        <>
          <h1 className="pedido__titulo">{t('pedido.titulo', { numero: pedido.numero })}</h1>
          {pedido.estado === 'PENDIENTE_PAGO' && (
            <Alerta tono="aviso" titulo={t('pedido.esperaPago')} descripcion={t('pedido.esperaPagoTexto')} />
          )}
          {pedido.estado === 'CANCELADO' && (
            <Alerta tono="error" titulo={t('pedido.cancelado')} descripcion={t('pedido.canceladoTexto')} />
          )}
        </>
      )}

      <div className="pedido__cuerpo">
        <div className="pedido__principal">
          {conSeguimiento(pedido) && (
            <section className="pedido__tarjeta" aria-labelledby="pedido-seguimiento">
              <h2 id="pedido-seguimiento" className="pedido__tarjeta-titulo">{t('misPedidos.seguimientoTitulo')}</h2>
              <SeguimientoPedido pedido={pedido} />
            </section>
          )}
          <section className="pedido__tarjeta" aria-labelledby="pedido-articulos">
            <h2 id="pedido-articulos" className="pedido__tarjeta-titulo">{t('pedido.articulos')}</h2>
            <table className="pedido__tabla">
              <thead>
                <tr>
                  <th scope="col">{t('productor.tabla.producto')}</th>
                  <th scope="col">{t('selectorCantidad.etiqueta')}</th>
                  <th scope="col">{t('productor.productos.col.precio')}</th>
                  <th scope="col">{t('pedido.subtotal')}</th>
                </tr>
              </thead>
              <tbody>
                {pedido.lineas.map((l) => (
                  <tr key={l.productoId}>
                    <td>
                      <span className="pedido__producto">{l.nombre}</span>
                      <span className="caption">{t('carrito.deTienda', { nombre: l.tienda })}</span>
                    </td>
                    <td>{Number(l.cantidad)}</td>
                    <td>{precio(l.precioUnitario)}</td>
                    <td>{precio(l.subtotal)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="pedido__total">
              <span>{t('carrito.total')}</span>
              <strong>{precio(pedido.total)}</strong>
            </p>
          </section>
          {pedido.confirmadoEn && <Condiciones pedido={pedido} />}
          {pedido.historial?.length > 0 && (
            <section className="pedido__tarjeta" aria-labelledby="pedido-historial">
              <h2 id="pedido-historial" className="pedido__tarjeta-titulo">{t('misPedidos.historialPedido')}</h2>
              <ol className="pedido__historial">
                {pedido.historial.map((e, i) => (
                  <li key={`${e.estado}-${i}`}>
                    <span>{t(`misPedidos.evento.${e.estado}`, { defaultValue: e.estado })}</span>
                    <time dateTime={e.registradoEn} className="caption">{fechaHora(e.registradoEn)}</time>
                  </li>
                ))}
              </ol>
            </section>
          )}
        </div>

        <aside className="pedido__lateral">
          <section className="pedido__tarjeta" aria-labelledby="pedido-recojo">
            <h2 id="pedido-recojo" className="pedido__tarjeta-titulo">{t('pedido.recojo')}</h2>
            <dl className="pedido__datos">
              <div>
                <dt>{t('pedido.punto')}</dt>
                <dd>
                  {punto.nombre}
                  <span className="caption">{[punto.direccion, punto.distrito, punto.departamento].filter(Boolean).join(' · ')}</span>
                </dd>
              </div>
              <div>
                <dt>{t('pedido.recoge')}</dt>
                <dd>{pedido.personaAutorizada ?? t('pedido.tu')}</dd>
              </div>
              <div>
                <dt>{t('compra.celular')}</dt>
                <dd>{pedido.celularContacto}</dd>
              </div>
            </dl>
          </section>

          <section className="pedido__tarjeta" aria-labelledby="pedido-pagos">
            <h2 id="pedido-pagos" className="pedido__tarjeta-titulo">{t('pedido.pago')}</h2>
            {pagoAprobado && (
              <p className="pedido__pago-ok">
                {t('pedido.pagaste', { monto: precio(pagoAprobado.monto), marca: pagoAprobado.marca })}
                <span className="caption">{t('pedido.operacion', { referencia: pagoAprobado.referencia })}</span>
              </p>
            )}
            {pedido.pagos.length > 0 && (
              <ul className="pedido__intentos" aria-label={t('pedido.intentos')}>
                {pedido.pagos.map((p) => (
                  <li key={p.id}>
                    <span>
                      {fechaHora(p.creadoEn)} · {p.marca} · {precio(p.monto)}
                    </span>
                    <Badge tono={TONO_PAGO[p.estado] ?? 'neutro'}>{t(`pedido.estadoPago.${p.estado}`, { defaultValue: p.estado })}</Badge>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <Boton tipo="secundario" anchoCompleto to={RUTAS.misPedidos}>
            {t('misPedidos.verMisPedidos')}
          </Boton>
          <Boton tipo="terciario" anchoCompleto to={RUTAS.buscar}>
            {t('pedido.seguirComprando')}
          </Boton>
        </aside>
      </div>
    </>
  )
}
