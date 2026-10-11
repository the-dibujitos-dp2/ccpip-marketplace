import { useId, useState } from 'react'
import iconoAlerta from '../../assets/icons/alerta-campo.svg'
import iconoBolsa from '../../assets/icons/bolsa-marca.svg'
import { agregarAlCarrito } from '../../api/carrito.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
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
 */
export default function AgregarAlCarrito({ producto }) {
  const { user } = useSesion()
  const idError = useId()
  const [cantidad, setCantidad] = useState('1')
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [enviando, setEnviando] = useState(false)

  // Solo el comprador tiene carrito: a otros roles no se les ofrece (el servidor tambien lo niega)
  if (!producto.disponible || (user && user.role !== 'CONSUMIDOR')) return null
  const soloMayorista = producto.modalidad === 'MAYORISTA'

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

  return (
    <form className="agregar-carrito" onSubmit={agregar} noValidate>
      <div className="agregar-carrito__fila">
        <SelectorCantidad
          valor={cantidad}
          onCambiar={cambiar}
          invalido={Boolean(error)}
          disabled={soloMayorista}
          describedBy={error ? idError : undefined}
        />
        <Boton tipo="primario" tamano="L" type="submit" icono={iconoBolsa} disabled={enviando || soloMayorista} className="agregar-carrito__boton">
          {enviando ? 'Agregando…' : 'Agregar al carrito'}
        </Boton>
      </div>
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
