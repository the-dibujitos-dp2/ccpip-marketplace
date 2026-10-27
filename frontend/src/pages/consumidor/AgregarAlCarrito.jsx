import { useCallback, useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAlerta from '../../assets/icons/alerta-campo.svg'
import iconoBolsa from '../../assets/icons/bolsa-marca.svg'
import { agregarAlCarrito, recargarUnidadesCarrito } from '../../api/carrito.js'
import Alerta from '../../components/Alerta.jsx'
import AvisoLateral from '../../components/AvisoLateral.jsx'
import Boton from '../../components/Boton.jsx'
import ImagenProducto from '../../components/ImagenProducto.jsx'
import MinimoMayorista from '../../components/MinimoMayorista.jsx'
import SelectorCantidad from '../../components/SelectorCantidad.jsx'
import { precio } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
import { navigate, RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import { resolverTarifa } from '../../tarifa.js'
import './AgregarAlCarrito.css'

/**
 * CCPIP-58, panel de compra del Figma (CW-03c): el comprador agrega esta oferta al carrito
 * indicando la cantidad. Sin sesion lo lleva a ingresar y lo regresa a la ficha. La
 * disponibilidad real la valida el servidor; el carrito no reserva existencias (RNF-35).
 * CCPIP-134 (RF-108): la cantidad decide la tarifa. Desde el minimo se aplica el precio mayorista;
 * una oferta solo mayorista arranca en su minimo y bajo el no se puede agregar. La ficha comparte la
 * cantidad (`cantidad`, `onCantidad`) para marcar la tarjeta de precio que corresponde (`modo`).
 * Una oferta en "Venta pausada" (CCPIP-269) no ofrece compra: la ficha explica por que.
 */
export default function AgregarAlCarrito({ producto, cantidad, onCantidad, modo }) {
  const { t } = useTranslation()
  const { user } = useSesion()
  const idError = useId()
  const [error, setError] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [enviando, setEnviando] = useState(false)
  // CCPIP-414: aviso lateral con el nombre del producto; `id` reinicia su tiempo en cada agregado
  const [lateral, setLateral] = useState(null)
  const cerrarLateral = useCallback(() => setLateral(null), [])
  // Solo el comprador tiene carrito: a otros roles no se les ofrece (el servidor tambien lo niega)
  const ofrecer = producto.disponible && producto.comprable !== false && (!user || user.role === 'CONSUMIDOR')
  const [panel, panelFuera] = usePanelFuera(ofrecer)

  if (!ofrecer) return null

  const soloMayorista = producto.modalidad === 'MAYORISTA'
  const minimo = soloMayorista ? (producto.cantidadMinimaMayorista ?? 1) : 1
  const unidades = Number(cantidad)
  const valida = cantidad !== '' && Number.isInteger(unidades) && unidades >= 1
  const tarifa = resolverTarifa(producto, cantidad)
  // Solo por mayor y bajo el minimo: no se agrega (el servidor tambien lo niega)
  const bloqueado = valida && !tarifa.comprable

  function cambiar(valor) {
    onCantidad(valor)
    setError(null)
    setAviso(null)
  }

  async function agregar(e) {
    e.preventDefault()
    if (!user) {
      navigate(`${RUTAS.ingresar}?volver=${encodeURIComponent(window.location.pathname)}`)
      return
    }
    if (!valida) {
      setError(t('carrito.agregar.minimo'))
      return
    }
    if (bloqueado) return
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
      setLateral((previo) => ({ id: (previo?.id ?? 0) + 1 }))
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
  const faltaParaAgregar = bloqueado ? t('carrito.agregar.faltaParaAgregar', { count: tarifa.faltante }) : null

  return (
    <form className="agregar-carrito" onSubmit={agregar} noValidate>
      <div className="agregar-carrito__fila" ref={panel}>
        <SelectorCantidad
          valor={cantidad}
          onCambiar={cambiar}
          min={minimo}
          invalido={Boolean(error)}
          describedBy={error ? idError : undefined}
        />
        <Boton tipo="primario" tamano="L" type="submit" icono={iconoBolsa} disabled={bloqueado} cargando={enviando} className="agregar-carrito__boton">
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
              <p className={`caption barra-compra__detalle${aviso?.tono === 'error' || error || bloqueado ? ' barra-compra__detalle--error' : ''}`} role="status">
                {error ?? (aviso ? (aviso.tono === 'exito' ? aviso.titulo : aviso.descripcion) : (faltaParaAgregar ?? resumenPrecios(producto, t)))}
              </p>
            </div>
            <SelectorCantidad valor={cantidad} onCambiar={cambiar} min={minimo} invalido={Boolean(error)} etiqueta={t('carrito.agregar.cantidad')} />
            <Boton tipo="primario" tamano="L" type="submit" icono={iconoBolsa} disabled={bloqueado} cargando={enviando} className="barra-compra__boton">
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
      <AvisoTarifa producto={producto} unidades={valida ? unidades : 0} tarifa={tarifa} modo={modo} faltaParaAgregar={faltaParaAgregar} />
      {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}
      {lateral && (
        <AvisoLateral
          key={lateral.id}
          titulo={t('carrito.agregar.avisoLateral', { producto: producto.nombre })}
          enlace={{ to: RUTAS.carrito, texto: t('carrito.agregar.verCarrito') }}
          onCerrar={cerrarLateral}
        />
      )}
    </form>
  )
}

/**
 * CCPIP-134, estados del panel de compra (Figma CW-03c): cuanto falta para el precio mayorista, el
 * mayorista ya aplicado con lo que se ahorra, o el bloqueo de una oferta solo mayorista bajo su minimo.
 */
function AvisoTarifa({ producto, unidades, tarifa, modo, faltaParaAgregar }) {
  const { t } = useTranslation()
  const minimo = producto.cantidadMinimaMayorista
  if (tarifa.faltante == null) return null
  if (faltaParaAgregar) {
    return (
      <MinimoMayorista
        estado="bloqueado"
        titulo={t('carrito.agregar.soloMayorista', { minimo })}
        detalle={faltaParaAgregar}
        cantidad={unidades}
        minimo={minimo}
      />
    )
  }
  if (producto.modalidad === 'MAYORISTA') return null
  if (tarifa.tarifa === 'MAYORISTA') {
    const ahorro = unidades * (Number(producto.precio) - Number(producto.precioMayorista))
    return (
      <MinimoMayorista
        estado="alcanzado"
        titulo={t('carrito.mayorista.aplicado', { precio: precio(producto.precioMayorista) })}
        detalle={t('carrito.mayorista.ahorro', { monto: precio(ahorro) })}
        cantidad={unidades}
        minimo={minimo}
      />
    )
  }
  if (modo === 'MAYORISTA') {
    return (
      <MinimoMayorista
        estado="falta"
        titulo={t('carrito.mayorista.falta', { count: tarifa.faltante })}
        detalle={t('carrito.mayorista.avance', { cantidad: unidades, minimo })}
        cantidad={unidades}
        minimo={minimo}
      />
    )
  }
  return <p className="caption">{t('carrito.agregar.desdeMayorista', { minimo, precio: precio(producto.precioMayorista) })}</p>
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
