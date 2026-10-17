import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAlerta from '../../assets/icons/alerta-campo.svg'
import iconoBasurero from '../../assets/icons/basurero.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { consultarCarrito, fijarUnidadesCarrito, modificarCantidad, quitarDelCarrito } from '../../api/carrito.js'
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
import { mesAnio, precio } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import './CarritoPage.css'

const PASOS = ['carrito', 'entrega', 'confirmacion']

/** Se puede cambiar la cantidad si la oferta sigue comprable; con stock insuficiente, para bajarla. */
const EDITABLES = new Set(['DISPONIBLE', 'STOCK_INSUFICIENTE'])
/** Espera tras el ultimo cambio antes de guardar: varios clics en "+" son una sola peticion. */
const ESPERA_GUARDAR_MS = 500
/** El motivo de un rechazo del servidor se lee y se va: la cantidad ya volvio a la correcta. */
const DURACION_RECHAZO_MS = 6000

/** Por que una oferta del carrito no suma al total: el servidor la revalido al consultar. */
const PROBLEMAS = {
  STOCK_INSUFICIENTE: (item, t) => ({ tono: 'aviso', texto: t('carrito.problema.STOCK_INSUFICIENTE', { quedan: Number(item.disponibles) }) }),
  AGOTADO: (item, t) => ({ tono: 'error', texto: t('carrito.problema.AGOTADO') }),
  SOLO_MAYORISTA: (item, t) => ({ tono: 'neutro', texto: t('carrito.problema.SOLO_MAYORISTA') }),
  NO_DISPONIBLE: (item, t) => ({ tono: 'neutro', texto: t('carrito.problema.NO_DISPONIBLE') }),
  // CCPIP-269: el productor no tiene cuenta bancaria activa o autorizacion vigente
  VENTA_PAUSADA: (item, t) => ({ tono: 'aviso', texto: t('carrito.problema.VENTA_PAUSADA') }),
}

/**
 * CW-05 · CCPIP-155: articulos, cantidades, subtotales y total del carrito persistido. Precio y
 * stock vienen revalidados del servidor; lo que ya no se puede comprar se muestra sin sumar al
 * total. Cambiar la cantidad es CCPIP-268 y quitar, CCPIP-156; agrupar por comunidad, CCPIP-63.
 */
export default function CarritoPage() {
  const { t } = useTranslation()
  const [carrito, setCarrito] = useState(null)
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)

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

  // CCPIP-156: quitar sin confirmacion, como en el Figma; si fue un error se vuelve a agregar desde la ficha
  const quitar = useCallback(async (item) => {
    const numero = ++ultimoCambio.current
    setAviso(null)
    try {
      const nuevo = await quitarDelCarrito(item.productoId)
      if (numero === ultimoCambio.current) {
        setCarrito(nuevo)
        fijarUnidadesCarrito(nuevo.unidades)
      }
      setAviso({ tono: 'exito', titulo: t('carrito.quitaste', { nombre: item.nombre }) })
    } catch (e) {
      if (e.status === 404) {
        // Ya no estaba (p. ej. se quito desde otra pestana): se muestra el carrito actual
        cargar()
      } else if (e.status !== 401) {
        setAviso({ tono: 'error', titulo: t('carrito.noQuitamos', { nombre: item.nombre }), descripcion: t('carrito.intentaOtraVez') })
      }
    }
  }, [cargar, t])

  return (
    <LayoutTienda>
      <main className="contenedor carrito" aria-busy={!carrito && !error}>
        <Pasos pasos={PASOS.map((p) => t(`carrito.pasos.${p}`))} actual={0} etiqueta={t('carrito.pasos.etiqueta')} />
        <h1 className="carrito__titulo">{t('carrito.titulo')}</h1>
        {aviso && (
          <div className="carrito__aviso">
            <Alerta {...aviso} onCerrar={() => setAviso(null)} />
          </div>
        )}
        {error && (
          <EstadoVacio titulo={t('carrito.error')} descripcion={t('comun.errorConexion')}>
            <Boton tamano="M" onClick={cargar}>
              {t('carrito.reintentar')}
            </Boton>
          </EstadoVacio>
        )}
        {!error && !carrito && <p className="visually-hidden">{t('carrito.cargando')}</p>}
        {carrito?.items.length === 0 && (
          <EstadoVacio titulo={t('carrito.vacio')} descripcion={t('carrito.vacioTexto')}>
            <Boton tamano="M" to={RUTAS.buscar}>
              {t('inicio.hero.explorar')}
            </Boton>
          </EstadoVacio>
        )}
        {carrito && carrito.items.length > 0 && (
          <Contenido carrito={carrito} onCambiarCantidad={cambiarCantidad} onQuitar={quitar} />
        )}
      </main>
    </LayoutTienda>
  )
}

function Contenido({ carrito, onCambiarCantidad, onQuitar }) {
  const { t } = useTranslation()
  const comprables = carrito.items.filter((i) => i.estado === 'DISPONIBLE')
  const unidadesComprables = comprables.reduce((suma, i) => suma + Number(i.cantidad), 0)
  const conProblemas = comprables.length < carrito.items.length
  return (
    <div className="carrito__cuerpo">
      <section className="carrito__lista" aria-label={t('carrito.productos')}>
        {conProblemas && (
          <Alerta
            tono="aviso"
            titulo={t('carrito.conProblemas')}
            descripcion={t('carrito.conProblemasTexto')}
          />
        )}
        <ul className="carrito__items">
          {carrito.items.map((item) => (
            <ItemCarrito key={item.productoId} item={item} onCambiarCantidad={onCambiarCantidad} onQuitar={onQuitar} />
          ))}
        </ul>
      </section>

      <aside className="carrito__resumen" aria-labelledby="titulo-resumen">
        <h2 id="titulo-resumen" className="carrito__resumen-titulo">{t('carrito.resumen')}</h2>
        <dl className="carrito__cifras">
          <div>
            <dt>{t('carrito.productosConteo', { unidades: unidadesComprables })}</dt>
            <dd>{precio(carrito.total)}</dd>
          </div>
          <div>
            <dt>{t('comunidades.titulo')}</dt>
            <dd>{carrito.comunidades}</dd>
          </div>
          <div>
            <dt>{t('carrito.entrega')}</dt>
            <dd>{t('carrito.entregaSiguiente')}</dd>
          </div>
        </dl>
        <p className="carrito__total">
          <span>{t('carrito.total')}</span>
          <strong>{precio(carrito.total)}</strong>
        </p>
        {/* CCPIP-157: para confirmar, todo el carrito debe poder comprarse (el servidor lo vuelve a revisar) */}
        {comprables.length > 0 && !conProblemas ? (
          <Boton tipo="acento" anchoCompleto to={RUTAS.compra}>
            {t('carrito.continuar')}
          </Boton>
        ) : (
          <>
            <Boton tipo="acento" anchoCompleto disabled>
              {t('carrito.continuar')}
            </Boton>
            <p className="caption carrito__bloqueo">{t('carrito.bloqueo')}</p>
          </>
        )}
        <div className="carrito__nota">
          <p className="carrito__nota-titulo">
            <Icono src={iconoReloj} tamano={16} />
            {t('carrito.noReserva')}
          </p>
          <p className="caption">{t('carrito.noReservaTexto')}</p>
        </div>
      </aside>
    </div>
  )
}

function ItemCarrito({ item, onCambiarCantidad, onQuitar }) {
  const { t } = useTranslation()
  const problema = PROBLEMAS[item.estado]?.(item, t)
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
          {[presentacion, item.cosecha && t('carrito.cosechaDe', { mes: cosecha(item.cosecha) }), t('carrito.deTienda', { nombre: item.tienda.nombre })]
            .filter(Boolean)
            .join(' · ')}
        </p>
        {item.precio != null && <p className="body-s">{t('carrito.precioCadaUno', { precio: precio(item.precio) })}</p>}
      </div>
      {EDITABLES.has(item.estado) ? (
        <CantidadEditable item={item} onCambiar={onCambiarCantidad} />
      ) : (
        <p className="carrito__cantidad">{t('producto.unidades', { count: cantidad })}</p>
      )}
      <div className="carrito__subtotal">
        {problema ? <Badge tono={problema.tono}>{problema.texto}</Badge> : <strong>{precio(item.subtotal)}</strong>}
      </div>
      <BotonQuitar item={item} onQuitar={onQuitar} />
    </li>
  )
}

/**
 * CCPIP-268, selector de cada fila (Figma CW-05): guarda solo tras una pausa breve, sin boton
 * "Actualizar". Si el servidor lo rechaza (p. ej. stock insuficiente) explica el motivo unos segundos y
 * vuelve a la cantidad que tenia. Una cantidad invalida se marca mientras siga escrita.
 */
function CantidadEditable({ item, onCambiar }) {
  const { t } = useTranslation()
  const idError = useId()
  const guardada = String(Number(item.cantidad))
  const [valor, setValor] = useState(guardada)
  const [error, setError] = useState(null)
  const [rechazo, setRechazo] = useState(null)
  const [guardando, setGuardando] = useState(false)

  // Si el servidor cambia la cantidad guardada, el campo la sigue. Se ajusta al renderizar y no en un
  // efecto: un efecto de montaje podia correr despues de lo primero que se escribio y borrarlo
  const [guardadaAntes, setGuardadaAntes] = useState(guardada)
  if (guardada !== guardadaAntes) {
    setGuardadaAntes(guardada)
    setValor(guardada)
  }

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
    setError(valida ? null : t('carrito.agregar.minimo'))
    if (!valida || valor === guardada) return undefined
    async function guardar() {
      setGuardando(true)
      try {
        await onCambiar(item.productoId, unidades)
      } catch (e) {
        // Un 401 lo maneja la app (vuelve a ingresar); aqui, las reglas de negocio
        if (e.status !== 401) {
          setRechazo(mensajeError(e))
          setValor(guardada)
        }
      } finally {
        setGuardando(false)
      }
    }
    // guardar() atrapa sus propios errores
    const espera = setTimeout(() => void guardar(), ESPERA_GUARDAR_MS)
    return () => clearTimeout(espera)
  }, [valor, guardada, item.productoId, onCambiar, t])

  const mensaje = error ?? rechazo
  return (
    <div className="carrito__cantidad-editable">
      <SelectorCantidad
        valor={valor}
        onCambiar={cambiar}
        etiqueta={t('carrito.cantidadDe', { nombre: item.nombre })}
        invalido={Boolean(error)}
        describedBy={mensaje ? idError : undefined}
      />
      {guardando && <p className="caption" role="status">{t('carrito.guardando')}</p>}
      {mensaje && (
        <p id={idError} className="carrito__error" role="alert">
          <img src={iconoAlerta} width="16" height="16" alt="" />
          {mensaje}
        </p>
      )}
    </div>
  )
}

/** "2026-10-04" -> "octubre 2026" · "October 2026", como en el Figma CW-05 ("cosecha de octubre 2026"). */
function cosecha(iso) {
  return mesAnio(iso).replace(' de ', ' ')
}

/** CCPIP-156: basurero de la fila (Figma CW-05). Se desactiva mientras se quita para no repetir el pedido. */
function BotonQuitar({ item, onQuitar }) {
  const { t } = useTranslation()
  const [quitando, setQuitando] = useState(false)
  async function quitar() {
    setQuitando(true)
    try {
      await onQuitar(item)
    } finally {
      setQuitando(false)
    }
  }
  return (
    <button
      type="button"
      className="carrito__quitar"
      onClick={() => void quitar()}
      disabled={quitando}
      aria-label={t('carrito.quitar', { nombre: item.nombre })}
      title={t('carrito.quitarTitulo')}
    >
      <Icono src={iconoBasurero} tamano={20} />
    </button>
  )
}
