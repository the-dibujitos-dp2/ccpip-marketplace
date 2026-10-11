import { useCallback, useEffect, useState } from 'react'
import iconoReloj from '../../assets/icons/reloj.svg'
import { consultarCarrito, fijarUnidadesCarrito } from '../../api/carrito.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pasos from '../../components/Pasos.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { precio } from '../../formato.js'
import { RUTAS } from '../../router.js'
import './CarritoPage.css'

const PASOS = ['Carrito', 'Entrega y pago', 'Confirmación']

/** Por que una oferta del carrito no suma al total: el servidor la revalido al consultar. */
const PROBLEMAS = {
  STOCK_INSUFICIENTE: (item) => ({ tono: 'aviso', texto: `Solo quedan ${Number(item.disponibles)}: ajusta la cantidad` }),
  AGOTADO: () => ({ tono: 'error', texto: 'Agotado por ahora' }),
  SOLO_MAYORISTA: () => ({ tono: 'neutro', texto: 'Solo se vende por mayor' }),
  NO_DISPONIBLE: () => ({ tono: 'neutro', texto: 'Ya no está disponible' }),
}

/**
 * CW-05 · CCPIP-155: articulos, cantidades, subtotales y total del carrito persistido. Precio y
 * stock vienen revalidados del servidor; lo que ya no se puede comprar se muestra sin sumar al
 * total. Agrupar por comunidad es CCPIP-63; cambiar cantidad, CCPIP-268; quitar, CCPIP-156.
 */
export default function CarritoPage() {
  const [carrito, setCarrito] = useState(null)
  const [error, setError] = useState(null)

  const cargar = useCallback(() => {
    setError(null)
    consultarCarrito()
      .then((c) => {
        setCarrito(c)
        fijarUnidadesCarrito(c.unidades)
      })
      .catch((e) => e.status !== 401 && setError(e))
  }, [])

  useEffect(cargar, [cargar])

  return (
    <LayoutTienda>
      <main className="contenedor carrito" aria-busy={!carrito && !error}>
        <Pasos pasos={PASOS} actual={0} etiqueta="Pasos de la compra" />
        <h1 className="carrito__titulo">Tu carrito</h1>
        {error && (
          <EstadoVacio titulo="No pudimos cargar tu carrito" descripcion="Revisa tu conexión e inténtalo otra vez.">
            <Boton tamano="M" onClick={cargar}>
              Reintentar
            </Boton>
          </EstadoVacio>
        )}
        {!error && !carrito && <p className="visually-hidden">Cargando tu carrito…</p>}
        {carrito?.items.length === 0 && (
          <EstadoVacio titulo="Tu carrito está vacío" descripcion="Explora los productos de las comunidades y agrégalos aquí.">
            <Boton tamano="M" to={RUTAS.buscar}>
              Explorar productos
            </Boton>
          </EstadoVacio>
        )}
        {carrito?.items.length > 0 && <Contenido carrito={carrito} />}
      </main>
    </LayoutTienda>
  )
}

function Contenido({ carrito }) {
  const comprables = carrito.items.filter((i) => i.estado === 'DISPONIBLE')
  const unidadesComprables = comprables.reduce((suma, i) => suma + Number(i.cantidad), 0)
  const conProblemas = comprables.length < carrito.items.length
  return (
    <div className="carrito__cuerpo">
      <section className="carrito__lista" aria-label="Productos en tu carrito">
        {conProblemas && (
          <Alerta
            tono="aviso"
            titulo="Algunos productos no se pueden comprar como están"
            descripcion="Los revisamos al abrir tu carrito y no suman al total."
          />
        )}
        <ul className="carrito__items">
          {carrito.items.map((item) => (
            <ItemCarrito key={item.productoId} item={item} />
          ))}
        </ul>
      </section>

      <aside className="carrito__resumen" aria-labelledby="titulo-resumen">
        <h2 id="titulo-resumen" className="carrito__resumen-titulo">Resumen del pedido</h2>
        <dl className="carrito__cifras">
          <div>
            <dt>Productos ({unidadesComprables})</dt>
            <dd>{precio(carrito.total)}</dd>
          </div>
          <div>
            <dt>Comunidades</dt>
            <dd>{carrito.comunidades}</dd>
          </div>
          <div>
            <dt>Entrega</dt>
            <dd>Se elige en el siguiente paso</dd>
          </div>
        </dl>
        <p className="carrito__total">
          <span>Total</span>
          <strong>{precio(carrito.total)}</strong>
        </p>
        <div className="carrito__nota">
          <p className="carrito__nota-titulo">
            <Icono src={iconoReloj} tamano={16} />
            Tu carrito no reserva stock
          </p>
          <p className="caption">La disponibilidad se confirma al pagar y el stock se descuenta en ese momento.</p>
        </div>
      </aside>
    </div>
  )
}

function ItemCarrito({ item }) {
  const problema = PROBLEMAS[item.estado]?.(item)
  const presentacion = item.contenido ?? item.unidad?.toLowerCase()
  const cantidad = Number(item.cantidad)
  return (
    <li className={`carrito__item${problema ? ' carrito__item--inactivo' : ''}`}>
      <ImagenProducto url={item.imagen} linea={item.linea} className="carrito__imagen" />
      <div className="carrito__datos">
        <Enlace to={`/productos/${item.productoId}`} className="carrito__nombre">
          {item.nombre}
        </Enlace>
        <p className="caption">
          {[presentacion, `de ${item.tienda.nombre}`].filter(Boolean).join(' · ')}
        </p>
        {item.precio != null && <p className="body-s">{precio(item.precio)} c/u</p>}
      </div>
      <p className="carrito__cantidad">{cantidad} und</p>
      <div className="carrito__subtotal">
        {problema ? <Badge tono={problema.tono}>{problema.texto}</Badge> : <strong>{precio(item.subtotal)}</strong>}
      </div>
    </li>
  )
}
