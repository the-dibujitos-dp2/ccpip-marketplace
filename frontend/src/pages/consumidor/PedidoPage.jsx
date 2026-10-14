import { useCallback, useEffect, useState } from 'react'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import { consultarPedido } from '../../api/compra.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import Pasos from '../../components/Pasos.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { fechaHora, precio } from '../../formato.js'
import { RUTAS } from '../../router.js'
import './PedidoPage.css'

const PASOS = ['Carrito', 'Entrega y pago', 'Confirmación']

const ESTADOS_PAGO = {
  APROBADO: { tono: 'exito', texto: 'Aprobado' },
  RECHAZADO: { tono: 'error', texto: 'Rechazado' },
  ERROR: { tono: 'error', texto: 'Error' },
  PENDIENTE: { tono: 'aviso', texto: 'En proceso' },
  ANULADO: { tono: 'neutro', texto: 'Devuelto' },
}

/**
 * CW-05, paso "Confirmación" · CCPIP-64: numero de pedido, articulos e importes con el precio
 * pagado (no el vigente), punto de recojo y quien recoge. CCPIP-163: cada intento de pago con su
 * estado y monto, sin datos de la tarjeta salvo la marca.
 */
export default function PedidoPage({ id }) {
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
        <Pasos pasos={PASOS} actual={pedido?.estado === 'PENDIENTE_PAGO' ? 1 : 2} etiqueta="Pasos de la compra" />
        {error?.status === 404 && (
          <EstadoVacio titulo="No encontramos ese pedido" descripcion="Revisa el enlace o vuelve a la tienda.">
            <Boton tamano="M" to={RUTAS.catalogo}>
              Ir a la tienda
            </Boton>
          </EstadoVacio>
        )}
        {error && error.status !== 404 && (
          <EstadoVacio titulo="No pudimos cargar tu pedido" descripcion="Revisa tu conexión e inténtalo otra vez.">
            <Boton tamano="M" onClick={cargar}>
              Reintentar
            </Boton>
          </EstadoVacio>
        )}
        {!error && !pedido && <p className="visually-hidden">Cargando tu pedido…</p>}
        {pedido && <Detalle pedido={pedido} />}
      </main>
    </LayoutTienda>
  )
}

function Detalle({ pedido }) {
  const confirmado = !['PENDIENTE_PAGO', 'CANCELADO'].includes(pedido.estado)
  const pagoAprobado = pedido.pagos.find((p) => p.estado === 'APROBADO')
  const punto = pedido.punto
  return (
    <>
      {confirmado ? (
        <header className="pedido__exito">
          <Icono src={iconoCheck} tamano={48} className="pedido__exito-icono" />
          <h1 className="pedido__titulo">¡Gracias por tu compra!</h1>
          <p className="pedido__subtitulo">
            Tu pedido <strong>{pedido.numero}</strong> está confirmado. Te avisaremos cuando esté listo para recoger.
          </p>
        </header>
      ) : (
        <>
          <h1 className="pedido__titulo">Pedido {pedido.numero}</h1>
          {pedido.estado === 'PENDIENTE_PAGO' && (
            <Alerta tono="aviso" titulo="Este pedido espera su pago" descripcion="Vuelve a entrega y pago para completarlo." />
          )}
          {pedido.estado === 'CANCELADO' && (
            <Alerta tono="error" titulo="Este pedido fue cancelado" descripcion="No se cobró nada o el pago fue devuelto." />
          )}
        </>
      )}

      <div className="pedido__cuerpo">
        <section className="pedido__tarjeta" aria-labelledby="pedido-articulos">
          <h2 id="pedido-articulos" className="pedido__tarjeta-titulo">Artículos</h2>
          <table className="pedido__tabla">
            <thead>
              <tr>
                <th scope="col">Producto</th>
                <th scope="col">Cantidad</th>
                <th scope="col">Precio</th>
                <th scope="col">Subtotal</th>
              </tr>
            </thead>
            <tbody>
              {pedido.lineas.map((l) => (
                <tr key={l.productoId}>
                  <td>
                    <span className="pedido__producto">{l.nombre}</span>
                    <span className="caption">de {l.tienda}</span>
                  </td>
                  <td>{Number(l.cantidad)}</td>
                  <td>{precio(l.precioUnitario)}</td>
                  <td>{precio(l.subtotal)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="pedido__total">
            <span>Total</span>
            <strong>{precio(pedido.total)}</strong>
          </p>
        </section>

        <aside className="pedido__lateral">
          <section className="pedido__tarjeta" aria-labelledby="pedido-recojo">
            <h2 id="pedido-recojo" className="pedido__tarjeta-titulo">Recojo</h2>
            <dl className="pedido__datos">
              <div>
                <dt>Punto de acopio</dt>
                <dd>
                  {punto.nombre}
                  <span className="caption">{[punto.direccion, punto.distrito, punto.departamento].filter(Boolean).join(' · ')}</span>
                </dd>
              </div>
              <div>
                <dt>Recoge</dt>
                <dd>{pedido.personaAutorizada ?? 'Tú, con tu documento'}</dd>
              </div>
              <div>
                <dt>Celular de contacto</dt>
                <dd>{pedido.celularContacto}</dd>
              </div>
            </dl>
          </section>

          <section className="pedido__tarjeta" aria-labelledby="pedido-pagos">
            <h2 id="pedido-pagos" className="pedido__tarjeta-titulo">Pago</h2>
            {pagoAprobado && (
              <p className="pedido__pago-ok">
                Pagaste {precio(pagoAprobado.monto)} con {pagoAprobado.marca}
                <span className="caption">Operación {pagoAprobado.referencia}</span>
              </p>
            )}
            {pedido.pagos.length > 0 && (
              <ul className="pedido__intentos" aria-label="Intentos de pago">
                {pedido.pagos.map((p) => (
                  <li key={p.id}>
                    <span>
                      {fechaHora(p.creadoEn)} · {p.marca} · {precio(p.monto)}
                    </span>
                    <Badge tono={ESTADOS_PAGO[p.estado]?.tono ?? 'neutro'}>{ESTADOS_PAGO[p.estado]?.texto ?? p.estado}</Badge>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <Boton tipo="secundario" anchoCompleto to={RUTAS.buscar}>
            Seguir comprando
          </Boton>
        </aside>
      </div>
    </>
  )
}
