import { useEffect, useId, useState } from 'react'
import iconoAlerta from '../../assets/icons/alerta-campo.svg'
import iconoBolsa from '../../assets/icons/bolsa-marca.svg'
import { agregarAlCarrito, recargarUnidadesCarrito } from '../../api/carrito.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import SelectorCantidad from '../../components/SelectorCantidad.jsx'
import { precio } from '../../formato.js'
import { navigate, RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import './AgregarAlCarrito.css'

/**
 * CCPIP-58, panel de compra del Figma (CW-03c): el comprador agrega esta oferta al carrito
 * indicando la cantidad. Sin sesion lo lleva a ingresar y lo regresa a la ficha. La
 * disponibilidad real la valida el servidor; el carrito no reserva existencias (RNF-35).
 * La tarifa mayorista (CCPIP-134) llega en Sprint 1: por ahora se compra al precio minorista.
 * Una oferta en "Venta pausada" (CCPIP-269) no ofrece compra: la ficha explica por que.
 */
export default function AgregarAlCarrito({ producto }) {
  const { user } = useSesion()
  const idError = useId()
  const [cantidad, setCantidad] = useState('1')
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [enviando, setEnviando] = useState(false)
  // Solo el comprador tiene carrito: a otros roles no se les ofrece (el servidor tambien lo niega)
  const ofrecer = producto.disponible && producto.comprable !== false && (!user || user.role === 'CONSUMIDOR')
  const soloMayorista = producto.modalidad === 'MAYORISTA'
  const [panel, panelFuera] = usePanelFuera(ofrecer && !soloMayorista)

  if (!ofrecer) return null

  function cambiar(valor) {
    setCantidad(valor)
    setError(null)
    setAviso(null)
  }

  async function agregar(e) {
    e.preventDefault()
    if (!user) {
      navigate(`${RUTAS.ingresar}?volver=${encodeURIComponent(window.location.pathname)}`)
      return
    }
    const unidades = Number(cantidad)
    if (cantidad === '' || !Number.isInteger(unidades) || unidades < 1) {
      setError('La cantidad mínima es una unidad')
      return
    }
    setEnviando(true)
    setAviso(null)
    try {
      const r = await agregarAlCarrito(producto.id, unidades)
      // La cabecera se actualiza en segundo plano; sus errores no afectan esta confirmacion
      void recargarUnidadesCarrito()
      setAviso({
        tono: 'exito',
        titulo: `Agregaste ${unidades} ${unidades === 1 ? 'unidad' : 'unidades'} a tu carrito`,
        descripcion: `Ahora tienes ${Number(r.cantidad)} de este producto en tu carrito.`,
      })
    } catch (err) {
      // Un 401 ya lo maneja la app (vuelve al inicio de sesion); aqui se muestran las reglas de negocio
      if (err.status !== 401) {
        setAviso({ tono: 'error', titulo: 'No pudimos agregarlo', descripcion: err.body?.campos?.cantidad ?? err.message })
      }
    } finally {
      setEnviando(false)
    }
  }

  const textoBoton = enviando ? 'Agregando…' : 'Agregar al carrito'

  return (
    <form className="agregar-carrito" onSubmit={agregar} noValidate>
      <div className="agregar-carrito__fila" ref={panel}>
        <SelectorCantidad
          valor={cantidad}
          onCambiar={cambiar}
          invalido={Boolean(error)}
          disabled={soloMayorista}
          describedBy={error ? idError : undefined}
        />
        <Boton tipo="primario" tamano="L" type="submit" icono={iconoBolsa} disabled={enviando || soloMayorista} className="agregar-carrito__boton">
          {textoBoton}
        </Boton>
      </div>
      {panelFuera && (
        // CW-03b: al bajar por la ficha, la compra sigue a mano. Comparte cantidad y envio con el panel.
        <div className="barra-compra" role="region" aria-label="Compra rápida">
          <div className="contenedor barra-compra__contenido">
            <ImagenProducto url={producto.imagenes?.[0]?.miniatura} linea={producto.linea?.codigo} className="barra-compra__imagen" />
            <div className="barra-compra__datos">
              <p className="barra-compra__nombre">{producto.nombre}</p>
              <p className={`caption barra-compra__detalle${aviso?.tono === 'error' || error ? ' barra-compra__detalle--error' : ''}`} role="status">
                {error ?? (aviso ? (aviso.tono === 'exito' ? aviso.titulo : aviso.descripcion) : resumenPrecios(producto))}
              </p>
            </div>
            <SelectorCantidad valor={cantidad} onCambiar={cambiar} invalido={Boolean(error)} etiqueta="Cantidad a agregar" />
            <Boton tipo="primario" tamano="L" type="submit" icono={iconoBolsa} disabled={enviando} className="barra-compra__boton">
              {textoBoton}
            </Boton>
          </div>
        </div>
      )}
      {error && (
        <p id={idError} className="agregar-carrito__error">
          <img src={iconoAlerta} width="16" height="16" alt="" />
          {error}
        </p>
      )}
      {soloMayorista ? (
        <Alerta
          tono="error"
          titulo={`Este producto se vende solo por mayor: mínimo ${producto.cantidadMinimaMayorista} und`}
          descripcion="La compra mayorista estará disponible pronto en el carrito."
        />
      ) : (
        producto.modalidad === 'AMBAS' && (
          <p className="caption">
            Desde {producto.cantidadMinimaMayorista} und el precio mayorista ({precio(producto.precioMayorista)} c/u) estará
            disponible pronto; por ahora el carrito usa el precio minorista.
          </p>
        )
      )}
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
    </form>
  )
}

/** "C.N. Yarina · S/ 18.00 minorista · S/ 14.50 desde 24 und", como en CW-03b. */
function resumenPrecios(producto) {
  return [
    producto.tienda?.nombre,
    producto.precio != null && `${precio(producto.precio)} minorista`,
    producto.modalidad !== 'MINORISTA' && producto.precioMayorista != null &&
      `${precio(producto.precioMayorista)} desde ${producto.cantidadMinimaMayorista} und`,
  ].filter(Boolean).join(' · ')
}

/**
 * Avisa cuando el panel de compra quedo arriba, fuera de la pantalla (no cuando aun no se llega a el).
 * Se mide en cada scroll (una vez por cuadro) y no con IntersectionObserver: este no avisa si la pagina
 * salta de "panel abajo" a "panel arriba" sin pasar por el, como con "Leer su historia" o un scroll rapido.
 */
function usePanelFuera(activo) {
  const [elemento, setElemento] = useState(null)
  const [fuera, setFuera] = useState(false)
  useEffect(() => {
    if (!activo || !elemento) return undefined
    let cuadro = null
    const medir = () => {
      cuadro = null
      setFuera(elemento.getBoundingClientRect().bottom < 0)
    }
    const alMover = () => {
      cuadro ??= requestAnimationFrame(medir)
    }
    medir()
    window.addEventListener('scroll', alMover, { passive: true })
    window.addEventListener('resize', alMover)
    return () => {
      window.removeEventListener('scroll', alMover)
      window.removeEventListener('resize', alMover)
      if (cuadro !== null) cancelAnimationFrame(cuadro)
      setFuera(false)
    }
  }, [activo, elemento])
  return [setElemento, activo && fuera]
}
