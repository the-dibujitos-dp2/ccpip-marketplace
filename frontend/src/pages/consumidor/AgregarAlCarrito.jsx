import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAlerta from '../../assets/icons/alerta-campo.svg'
import iconoBolsa from '../../assets/icons/bolsa-marca.svg'
import { agregarAlCarrito, recargarUnidadesCarrito } from '../../api/carrito.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import SelectorCantidad from '../../components/SelectorCantidad.jsx'
import { precio } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
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
  const { t } = useTranslation()
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
      setError(t('carrito.agregar.minimo'))
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
        titulo: t('carrito.agregar.agregaste', { count: unidades }),
        descripcion: t('carrito.agregar.ahoraTienes', { cantidad: Number(r.cantidad) }),
      })
    } catch (err) {
      // Un 401 ya lo maneja la app (vuelve al inicio de sesion); aqui se muestran las reglas de negocio
      if (err.status !== 401) {
        // El mensaje por campo llega del servidor en espanol: en ingles se usa el del code
        const delCampo = i18n.resolvedLanguage === 'es' ? err.body?.campos?.cantidad : null
        setAviso({ tono: 'error', titulo: t('carrito.agregar.error'), descripcion: delCampo ?? mensajeError(err) })
      }
    } finally {
      setEnviando(false)
    }
  }

  const textoBoton = enviando ? t('carrito.agregar.agregando') : t('carrito.agregar.boton')

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
        <div className="barra-compra" role="region" aria-label={t('carrito.agregar.compraRapida')}>
          <div className="contenedor barra-compra__contenido">
            <ImagenProducto url={producto.imagenes?.[0]?.miniatura} linea={producto.linea?.codigo} className="barra-compra__imagen" />
            <div className="barra-compra__datos">
              <p className="barra-compra__nombre">{producto.nombre}</p>
              <p className={`caption barra-compra__detalle${aviso?.tono === 'error' || error ? ' barra-compra__detalle--error' : ''}`} role="status">
                {error ?? (aviso ? (aviso.tono === 'exito' ? aviso.titulo : aviso.descripcion) : resumenPrecios(producto, t))}
              </p>
            </div>
            <SelectorCantidad valor={cantidad} onCambiar={cambiar} invalido={Boolean(error)} etiqueta={t('carrito.agregar.cantidad')} />
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
          titulo={t('carrito.agregar.soloMayorista', { minimo: producto.cantidadMinimaMayorista })}
          descripcion={t('carrito.agregar.mayoristaPronto')}
        />
      ) : (
        producto.modalidad === 'AMBAS' && (
          <p className="caption">
            {t('carrito.agregar.ambas', { minimo: producto.cantidadMinimaMayorista, precio: precio(producto.precioMayorista) })}
          </p>
        )
      )}
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
    </form>
  )
}

/** "C.N. Yarina · S/ 18.00 minorista · S/ 14.50 desde 24 und", como en CW-03b. */
function resumenPrecios(producto, t) {
  return [
    producto.tienda?.nombre,
    producto.precio != null && t('carrito.agregar.resumenMinorista', { precio: precio(producto.precio) }),
    producto.modalidad !== 'MINORISTA' && producto.precioMayorista != null &&
      t('carrito.agregar.resumenMayorista', { precio: precio(producto.precioMayorista), minimo: producto.cantidadMinimaMayorista }),
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
