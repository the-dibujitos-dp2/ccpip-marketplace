import { useCallback, useEffect, useId, useRef, useState } from 'react'
import iconoAlerta from '../../assets/icons/alerta-campo.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { consultarCarrito, fijarUnidadesCarrito, modificarCantidad } from '../../api/carrito.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import Pasos from '../../components/Pasos.jsx'
import SelectorCantidad from '../../components/SelectorCantidad.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { precio } from '../../formato.js'
import { RUTAS } from '../../router.js'
import './CarritoPage.css'

const PASOS = ['Carrito', 'Entrega y pago', 'Confirmación']

/** Se puede cambiar la cantidad si la oferta sigue comprable; con stock insuficiente, para bajarla. */
const EDITABLES = new Set(['DISPONIBLE', 'STOCK_INSUFICIENTE'])
/** Espera tras el ultimo cambio antes de guardar: varios clics en "+" son una sola peticion. */
const ESPERA_GUARDAR_MS = 500
/** El motivo de un rechazo del servidor se lee y se va: la cantidad ya volvio a la correcta. */
const DURACION_RECHAZO_MS = 6000

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
 * total. Cambiar la cantidad es CCPIP-268; agrupar por comunidad, CCPIP-63; quitar, CCPIP-156.
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

  // CCPIP-268: el servidor revalida y devuelve el carrito recalculado. Si llegan respuestas cruzadas
  // (dos cambios seguidos), solo se aplica la del ultimo pedido.
  const ultimoCambio = useRef(0)
  const cambiarCantidad = useCallback(async (productoId, cantidad) => {
    const numero = ++ultimoCambio.current
    const nuevo = await modificarCantidad(productoId, cantidad)
    if (numero === ultimoCambio.current) {
      setCarrito(nuevo)
      fijarUnidadesCarrito(nuevo.unidades)
    }
  }, [])

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
        {carrito && carrito.items.length > 0 && <Contenido carrito={carrito} onCambiarCantidad={cambiarCantidad} />}
      </main>
    </LayoutTienda>
  )
}

function Contenido({ carrito, onCambiarCantidad }) {
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
            <ItemCarrito key={item.productoId} item={item} onCambiarCantidad={onCambiarCantidad} />
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

function ItemCarrito({ item, onCambiarCantidad }) {
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
      {EDITABLES.has(item.estado) ? (
        <CantidadEditable item={item} onCambiar={onCambiarCantidad} />
      ) : (
        <p className="carrito__cantidad">{cantidad} und</p>
      )}
      <div className="carrito__subtotal">
        {problema ? <Badge tono={problema.tono}>{problema.texto}</Badge> : <strong>{precio(item.subtotal)}</strong>}
      </div>
    </li>
  )
}

/**
 * CCPIP-268, selector de cada fila (Figma CW-05): guarda solo tras una pausa breve, sin boton
 * "Actualizar". Si el servidor lo rechaza (p. ej. stock insuficiente) explica el motivo unos segundos y
 * vuelve a la cantidad que tenia. Una cantidad invalida se marca mientras siga escrita.
 */
function CantidadEditable({ item, onCambiar }) {
  const idError = useId()
  const guardada = String(Number(item.cantidad))
  const [valor, setValor] = useState(guardada)
  const [error, setError] = useState(null)
  const [rechazo, setRechazo] = useState(null)
  const [guardando, setGuardando] = useState(false)

  useEffect(() => setValor(guardada), [guardada])

  useEffect(() => {
    if (!rechazo) return undefined
    const ocultar = setTimeout(() => setRechazo(null), DURACION_RECHAZO_MS)
    return () => clearTimeout(ocultar)
  }, [rechazo])

  function cambiar(nuevo) {
    setValor(nuevo)
    setRechazo(null)
  }

  useEffect(() => {
    const unidades = Number(valor)
    const valida = valor !== '' && Number.isInteger(unidades) && unidades >= 1
    // Se recalcula siempre: volver a la cantidad guardada tambien borra un error anterior
    setError(valida ? null : 'La cantidad mínima es una unidad')
    if (!valida || valor === guardada) return undefined
    async function guardar() {
      setGuardando(true)
      try {
        await onCambiar(item.productoId, unidades)
      } catch (e) {
        // Un 401 lo maneja la app (vuelve a ingresar); aqui, las reglas de negocio
        if (e.status !== 401) {
          setRechazo(e.message)
          setValor(guardada)
        }
      } finally {
        setGuardando(false)
      }
    }
    // guardar() atrapa sus propios errores
    const espera = setTimeout(() => void guardar(), ESPERA_GUARDAR_MS)
    return () => clearTimeout(espera)
  }, [valor, guardada, item.productoId, onCambiar])

  const mensaje = error ?? rechazo
  return (
    <div className="carrito__cantidad-editable">
      <SelectorCantidad
        valor={valor}
        onCambiar={cambiar}
        etiqueta={`Cantidad de ${item.nombre}`}
        invalido={Boolean(error)}
        describedBy={mensaje ? idError : undefined}
      />
      {guardando && <p className="caption" role="status">Guardando…</p>}
      {mensaje && (
        <p id={idError} className="carrito__error" role="alert">
          <img src={iconoAlerta} width="16" height="16" alt="" />
          {mensaje}
        </p>
      )}
    </div>
  )
}
