import { useCallback, useEffect, useRef, useState } from 'react'
import iconoEscudo from '../../assets/icons/escudo-check.svg'
import { consultarCarrito, recargarUnidadesCarrito } from '../../api/carrito.js'
import { confirmarCompra, consultarPuntosRecojo, pagarPedido } from '../../api/compra.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import OpcionTarjeta from '../../components/OpcionTarjeta.jsx'
import Pasos from '../../components/Pasos.jsx'
import LayoutTienda from '../../components/layout/LayoutTienda.jsx'
import { precio } from '../../formato.js'
import { navigate, RUTAS } from '../../router.js'
import './CompraPage.css'

const PASOS = ['Carrito', 'Entrega y pago', 'Confirmación']
const MESES = Array.from({ length: 12 }, (_, i) => ({ valor: String(i + 1), etiqueta: String(i + 1).padStart(2, '0') }))
const ANIO = new Date().getFullYear()
const ANIOS = Array.from({ length: 12 }, (_, i) => ({ valor: String(ANIO + i), etiqueta: String(ANIO + i) }))

const VACIO = { puntoId: '', recogeOtro: false, personaAutorizada: '', celular: '', numero: '', titular: '', mes: '', anio: '', cvv: '' }

/** Celular peruano: 9 digitos que empiezan con 9; admite "+51", espacios y guiones (como el servidor). */
export function celularValido(texto) {
  const digitos = texto.replace(/\D/g, '')
  const local = digitos.length === 11 && digitos.startsWith('51') ? digitos.slice(2) : digitos
  return /^9\d{8}$/.test(local)
}

/** Lo minimo antes de enviar. La tarjeta la valida a fondo el servidor (Luhn, marca, vencimiento). */
function validar(f) {
  const errores = {}
  if (!f.puntoId) errores.puntoId = 'Elige un punto de acopio'
  if (f.recogeOtro && !f.personaAutorizada.trim()) errores.personaAutorizada = 'Indica quién recogerá el pedido'
  if (!celularValido(f.celular)) errores.celular = 'Ingresa un celular de 9 dígitos que empiece con 9'
  if (f.numero.replace(/\D/g, '').length < 13) errores.numero = 'Ingresa el número de tu tarjeta'
  if (!f.titular.trim()) errores.titular = 'Indica el nombre del titular'
  if (!f.mes) errores.mes = 'Elige el mes'
  if (!f.anio) errores.anio = 'Elige el año'
  if (!/^\d{3,4}$/.test(f.cvv)) errores.cvv = '3 o 4 dígitos'
  return errores
}

/**
 * CW-05, paso "Entrega y pago": punto de recojo y persona autorizada (CCPIP-157) y pago con tarjeta
 * (CCPIP-62). El pedido se crea al pagar y solo se confirma si el pago se aprueba; entonces se
 * descuenta el stock. Un rechazo deja reintentar sin perder lo escrito.
 */
export default function CompraPage() {
  const [carrito, setCarrito] = useState(null)
  const [puntos, setPuntos] = useState(null)
  const [bloqueo, setBloqueo] = useState(null)
  const [form, setForm] = useState(VACIO)
  const [errores, setErrores] = useState({})
  const [aviso, setAviso] = useState(null)
  const [enviando, setEnviando] = useState(false)
  // Pedido ya creado y con que datos de recojo: si no cambian, un reintento de pago usa el mismo
  const pedido = useRef(null)
  // Clave del intento de pago en curso: si la respuesta no llega (corte de red), se reenvia la misma
  const clave = useRef(null)

  const cargar = useCallback(() => {
    setBloqueo(null)
    Promise.all([consultarCarrito(), consultarPuntosRecojo()])
      .then(([c, p]) => {
        setCarrito(c)
        setPuntos(p)
        if (p.length === 1) setForm((f) => ({ ...f, puntoId: String(p[0].id) }))
      })
      .catch((e) => {
        if (e.status === 401) return
        if (e.code === 'CARRITO_VACIO') navigate(RUTAS.carrito, { replace: true })
        else if (e.code === 'CARRITO_CON_PROBLEMAS') setBloqueo({ titulo: 'Revisa tu carrito', descripcion: e.message, alCarrito: true })
        else setBloqueo({ titulo: 'No pudimos preparar tu compra', descripcion: 'Revisa tu conexión e inténtalo otra vez.' })
      })
  }, [])

  useEffect(cargar, [cargar])

  const cambiar = (campo, valor) => {
    setForm((f) => ({ ...f, [campo]: valor }))
    setErrores((e) => ({ ...e, [campo]: undefined }))
  }
  const alEscribir = (campo) => (e) => cambiar(campo, e.target.type === 'checkbox' ? e.target.checked : e.target.value)

  async function pagar(e) {
    e.preventDefault()
    setAviso(null)
    const nuevos = validar(form)
    setErrores(nuevos)
    if (Object.keys(nuevos).length > 0) return

    setEnviando(true)
    try {
      const recojo = {
        puntoId: form.puntoId,
        personaAutorizada: form.recogeOtro ? form.personaAutorizada.trim() : null,
        celularContacto: form.celular,
      }
      const firma = JSON.stringify(recojo)
      if (pedido.current?.firma !== firma) {
        const nuevo = await confirmarCompra(recojo)
        pedido.current = { id: nuevo.id, firma }
        clave.current = null
      }
      clave.current ??= crypto.randomUUID()
      const tarjeta = { numero: form.numero, titular: form.titular.trim(), mes: Number(form.mes), anio: Number(form.anio), cvv: form.cvv }
      const resultado = await pagarPedido(pedido.current.id, clave.current, tarjeta)
      clave.current = null

      if (resultado.estado === 'APROBADO') {
        void recargarUnidadesCarrito()
        navigate(`/pedidos/${resultado.pedido.id}`)
        return
      }
      setForm((f) => ({ ...f, cvv: '' }))
      if (resultado.estado === 'ANULADO') {
        // El stock se agoto mientras pagaba: el pago se devolvio y el pedido se cancelo
        pedido.current = null
        setAviso({ tono: 'error', titulo: 'No se pudo completar tu compra', descripcion: resultado.mensaje })
        cargar()
      } else {
        setAviso({
          tono: 'error',
          titulo: resultado.estado === 'RECHAZADO' ? 'Tu pago fue rechazado' : 'No pudimos procesar tu pago',
          descripcion: resultado.mensaje,
        })
      }
    } catch (err) {
      if (err.status === 401) return
      // Con respuesta del servidor el intento termino; sin ella (red caida) se reintenta con la misma clave
      if (err.status !== undefined) clave.current = null
      manejarError(err)
    } finally {
      setEnviando(false)
    }
  }

  function manejarError(err) {
    switch (err.code) {
      case 'CELULAR_INVALIDO':
        setErrores((e) => ({ ...e, celular: err.message }))
        break
      case 'TARJETA_INVALIDA':
        setAviso({ tono: 'error', titulo: err.message, descripcion: (err.body?.detalles ?? []).join(' ') })
        break
      case 'PUNTO_SIN_EXISTENCIAS':
      case 'CARRITO_CON_PROBLEMAS':
      case 'PEDIDO_NO_PAGABLE':
        pedido.current = null
        setAviso({ tono: 'error', titulo: 'Tu carrito cambió', descripcion: err.message })
        cargar()
        break
      default:
        setAviso({
          tono: 'error',
          titulo: 'No pudimos completar el pago',
          descripcion: err.status ? err.message : 'Revisa tu conexión e inténtalo otra vez. No se hizo ningún cobro duplicado.',
        })
    }
  }

  const comprables = carrito?.items.filter((i) => i.estado === 'DISPONIBLE') ?? []

  return (
    <LayoutTienda>
      <main className="contenedor compra" aria-busy={!bloqueo && (!carrito || !puntos)}>
        <Pasos pasos={PASOS} actual={1} etiqueta="Pasos de la compra" onIr={(i) => i === 0 && navigate(RUTAS.carrito)} />
        <h1 className="compra__titulo">Entrega y pago</h1>

        {bloqueo && (
          <EstadoVacio titulo={bloqueo.titulo} descripcion={bloqueo.descripcion}>
            {bloqueo.alCarrito ? (
              <Boton tamano="M" to={RUTAS.carrito}>
                Volver al carrito
              </Boton>
            ) : (
              <Boton tamano="M" onClick={cargar}>
                Reintentar
              </Boton>
            )}
          </EstadoVacio>
        )}
        {!bloqueo && (!carrito || !puntos) && <p className="visually-hidden">Preparando tu compra…</p>}
        {!bloqueo && puntos?.length === 0 && (
          <EstadoVacio
            titulo="Ningún punto de acopio tiene todo tu carrito"
            descripcion="Por ahora ningún punto tiene existencias para todos tus productos juntos. Ajusta las cantidades o quita alguno."
          >
            <Boton tamano="M" to={RUTAS.carrito}>
              Volver al carrito
            </Boton>
          </EstadoVacio>
        )}

        {!bloqueo && carrito && puntos?.length > 0 && (
          <form className="compra__cuerpo" onSubmit={pagar} noValidate>
            <div className="compra__secciones">
              {aviso && <Alerta {...aviso} onCerrar={() => setAviso(null)} />}

              <section className="compra__seccion" aria-labelledby="compra-recojo">
                <h2 id="compra-recojo" className="compra__seccion-titulo">1. Punto de recojo</h2>
                <p className="caption compra__ayuda">Recoges tu pedido sin costo en un punto de acopio de la Cámara.</p>
                <OpcionTarjeta
                  nombre="punto"
                  etiqueta="Elige dónde recoger"
                  valor={form.puntoId}
                  onCambiar={(v) => cambiar('puntoId', v)}
                  opciones={puntos.map((p) => ({
                    valor: String(p.id),
                    etiqueta: (
                      <span className="compra__punto">
                        <span>{p.nombre}</span>
                        <span className="compra__punto-direccion">
                          {[p.direccion, p.distrito, p.departamento].filter(Boolean).join(' · ')}
                        </span>
                      </span>
                    ),
                  }))}
                />
                {errores.puntoId && <p className="campo__ayuda campo__ayuda--error">{errores.puntoId}</p>}
              </section>

              <section className="compra__seccion" aria-labelledby="compra-quien">
                <h2 id="compra-quien" className="compra__seccion-titulo">2. ¿Quién recoge?</h2>
                <label className="compra__check">
                  <input type="checkbox" checked={form.recogeOtro} onChange={alEscribir('recogeOtro')} />
                  Otra persona recogerá el pedido
                </label>
                {form.recogeOtro && (
                  <Campo
                    etiqueta="Nombre de la persona autorizada"
                    value={form.personaAutorizada}
                    onChange={alEscribir('personaAutorizada')}
                    error={errores.personaAutorizada}
                    maxLength={160}
                  />
                )}
                <Campo
                  etiqueta="Celular de contacto"
                  ayuda="El punto de acopio lo verifica al entregar el pedido."
                  value={form.celular}
                  onChange={alEscribir('celular')}
                  error={errores.celular}
                  inputMode="tel"
                  autoComplete="tel"
                  placeholder="987 654 321"
                  maxLength={20}
                />
              </section>

              <section className="compra__seccion" aria-labelledby="compra-pago">
                <h2 id="compra-pago" className="compra__seccion-titulo">3. Pago con tarjeta</h2>
                <p className="compra__seguro">
                  <Icono src={iconoEscudo} tamano={16} />
                  No guardamos los datos de tu tarjeta.
                </p>
                <Campo
                  etiqueta="Número de tarjeta"
                  value={form.numero}
                  onChange={alEscribir('numero')}
                  error={errores.numero}
                  inputMode="numeric"
                  autoComplete="cc-number"
                  placeholder="1234 5678 9012 3456"
                  maxLength={23}
                />
                <Campo
                  etiqueta="Nombre del titular"
                  value={form.titular}
                  onChange={alEscribir('titular')}
                  error={errores.titular}
                  autoComplete="cc-name"
                  maxLength={120}
                />
                <div className="compra__fila">
                  <Campo tipo="selector" etiqueta="Mes" opciones={MESES} placeholder="MM" value={form.mes}
                    onChange={alEscribir('mes')} error={errores.mes} autoComplete="cc-exp-month" />
                  <Campo tipo="selector" etiqueta="Año" opciones={ANIOS} placeholder="AAAA" value={form.anio}
                    onChange={alEscribir('anio')} error={errores.anio} autoComplete="cc-exp-year" />
                  <Campo etiqueta="CVV" value={form.cvv} onChange={alEscribir('cvv')} error={errores.cvv}
                    inputMode="numeric" autoComplete="cc-csc" maxLength={4} />
                </div>
                <Alerta
                  tono="info"
                  titulo="Ambiente de pruebas: no se hace ningún cobro real"
                  descripcion="Usa 4111 1111 1111 1111 para aprobar el pago o 4000 0000 0000 0002 para ver un rechazo."
                />
              </section>
            </div>

            <aside className="compra__resumen" aria-labelledby="compra-resumen">
              <h2 id="compra-resumen" className="compra__resumen-titulo">Resumen del pedido</h2>
              <ul className="compra__lineas">
                {comprables.map((i) => (
                  <li key={i.productoId}>
                    <span>
                      {i.nombre} <span className="compra__cantidad">× {Number(i.cantidad)}</span>
                    </span>
                    <span>{precio(i.subtotal)}</span>
                  </li>
                ))}
              </ul>
              <dl className="compra__cifras">
                <div>
                  <dt>Comunidades</dt>
                  <dd>{carrito.comunidades}</dd>
                </div>
                <div>
                  <dt>Recojo en punto de acopio</dt>
                  <dd>Gratis</dd>
                </div>
              </dl>
              <p className="compra__total">
                <span>Total</span>
                <strong>{precio(carrito.total)}</strong>
              </p>
              <Boton tipo="acento" anchoCompleto type="submit" disabled={enviando}>
                {enviando ? 'Procesando pago…' : `Pagar ${precio(carrito.total)}`}
              </Boton>
              <p className="caption compra__nota">El stock se descuenta recién cuando tu pago se aprueba.</p>
            </aside>
          </form>
        )}
      </main>
    </LayoutTienda>
  )
}
