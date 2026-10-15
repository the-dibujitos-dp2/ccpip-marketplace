import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
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
import { mensajeError } from '../../i18n/errores.js'
import i18n from '../../i18n/index.js'
import { navigate, RUTAS } from '../../router.js'
import { digitos, formatear, largoCvv, marca, nombreMarca, numeroValido, vencimientoValido } from '../../tarjeta.js'
import './CompraPage.css'

const PASOS = ['carrito', 'entrega', 'confirmacion']
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

/** Nombres de personas: letras (con tildes), espacios, apostrofos, puntos y guiones. */
const NOMBRE = /^\p{L}[\p{L}\s'’.-]*$/u
const soloNombre = (texto) => texto.replace(/[^\p{L}\s'’.-]/gu, '')

/** Solo los 9 digitos del celular; si se pega con +51, se quita el prefijo. */
function limpiarCelular(texto) {
  const d = digitos(texto)
  return (d.length > 9 && d.startsWith('51') ? d.slice(2) : d).slice(0, 9)
}

/**
 * Lo que se limpia mientras se escribe, para que no se pueda escribir de mas. El CVV depende de la
 * marca: 4 digitos en American Express, 3 en las demas.
 */
const LIMPIAR = {
  celular: (valor) => limpiarCelular(valor),
  numero: (valor) => formatear(valor),
  cvv: (valor, f) => digitos(valor).slice(0, largoCvv(marca(f.numero))),
  titular: (valor) => soloNombre(valor),
  personaAutorizada: (valor) => soloNombre(valor),
}

/** Las mismas reglas que el servidor (Tarjeta.java, PersonaDeRecojo.java), para avisar antes de enviar. */
function validar(f) {
  const t = (clave, opciones) => i18n.t(`compra.validacion.${clave}`, opciones)
  const errores = {}
  if (!f.puntoId) errores.puntoId = t('punto')
  if (f.recogeOtro && !f.personaAutorizada.trim()) errores.personaAutorizada = t('persona')
  else if (f.recogeOtro && !NOMBRE.test(f.personaAutorizada.trim())) errores.personaAutorizada = t('soloLetras')
  if (!celularValido(f.celular)) errores.celular = t('celular')
  if (digitos(f.numero).length < 13) errores.numero = t('numero')
  else if (!numeroValido(f.numero)) errores.numero = t('numeroInvalido')
  if (!f.titular.trim()) errores.titular = t('titular')
  else if (!NOMBRE.test(f.titular.trim())) errores.titular = t('soloLetras')
  if (!f.mes) errores.mes = t('mes')
  if (!f.anio) errores.anio = t('anio')
  if (f.mes && f.anio && !vencimientoValido(f.mes, f.anio)) errores.mes = t('vencida')
  const cvv = largoCvv(marca(f.numero))
  if (!/^\d+$/.test(f.cvv) || f.cvv.length !== cvv) errores.cvv = t('cvv', { digitos: cvv })
  return errores
}

/** El mensaje del resultado del pago llega en espanol: en ingles se usa uno por estado. */
function mensajePago(resultado) {
  return i18n.resolvedLanguage === 'es' ? resultado.mensaje : i18n.t(`compra.resultado.${resultado.estado}`, { defaultValue: i18n.t('compra.resultado.ERROR') })
}

/**
 * CW-05, paso "Entrega y pago": punto de recojo y persona autorizada (CCPIP-157) y pago con tarjeta
 * (CCPIP-62). El pedido se crea al pagar y solo se confirma si el pago se aprueba; entonces se
 * descuenta el stock. Un rechazo deja reintentar sin perder lo escrito.
 */
export default function CompraPage() {
  const { t } = useTranslation()
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
  // El aviso va junto al boton "Pagar", donde el comprador acaba de hacer clic. Si aun asi quedo
  // fuera de la pantalla, se desplaza lo justo para verlo (sin saltar al inicio del formulario).
  const refAviso = useRef(null)
  useEffect(() => {
    if (aviso) refAviso.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
  }, [aviso])

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
        else if (e.code === 'CARRITO_CON_PROBLEMAS') setBloqueo({ titulo: t('compra.revisaCarrito'), descripcion: mensajeError(e), alCarrito: true })
        else setBloqueo({ titulo: t('compra.noPreparamos'), descripcion: t('comun.errorConexion') })
      })
  }, [t])

  useEffect(cargar, [cargar])

  const cambiar = (campo, valor) => {
    setForm((f) => {
      const nuevo = { ...f, [campo]: LIMPIAR[campo] ? LIMPIAR[campo](valor, f) : valor }
      // Si cambia la marca (p. ej. de Amex a Visa), el CVV se recorta a su nuevo largo
      if (campo === 'numero') nuevo.cvv = LIMPIAR.cvv(f.cvv, nuevo)
      return nuevo
    })
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
        setAviso({ tono: 'error', titulo: t('compra.noCompletada'), descripcion: mensajePago(resultado) })
        cargar()
      } else {
        setAviso({
          tono: 'error',
          titulo: resultado.estado === 'RECHAZADO' ? t('compra.rechazado') : t('compra.noProcesado'),
          descripcion: mensajePago(resultado),
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
        setErrores((e) => ({ ...e, celular: mensajeError(err) }))
        break
      case 'TARJETA_INVALIDA':
        // Los detalles llegan del servidor en espanol: en ingles basta el mensaje del code
        setAviso({ tono: 'error', titulo: mensajeError(err), descripcion: i18n.resolvedLanguage === 'es' ? (err.body?.detalles ?? []).join(' ') : undefined })
        break
      case 'PUNTO_SIN_EXISTENCIAS':
      case 'CARRITO_CON_PROBLEMAS':
      case 'PEDIDO_NO_PAGABLE':
        pedido.current = null
        setAviso({ tono: 'error', titulo: t('compra.carritoCambio'), descripcion: mensajeError(err) })
        cargar()
        break
      default:
        setAviso({
          tono: 'error',
          titulo: t('compra.noCompletamosPago'),
          descripcion: err.status ? mensajeError(err) : t('compra.sinConexion'),
        })
    }
  }

  const comprables = carrito?.items.filter((i) => i.estado === 'DISPONIBLE') ?? []
  const marcaTarjeta = marca(form.numero)

  return (
    <LayoutTienda>
      <main className="contenedor compra" aria-busy={!bloqueo && (!carrito || !puntos)}>
        <Pasos pasos={PASOS.map((p) => t(`carrito.pasos.${p}`))} actual={1} etiqueta={t('carrito.pasos.etiqueta')} onIr={(i) => i === 0 && navigate(RUTAS.carrito)} />
        <h1 className="compra__titulo">{t('carrito.pasos.entrega')}</h1>

        {bloqueo && (
          <EstadoVacio titulo={bloqueo.titulo} descripcion={bloqueo.descripcion}>
            {bloqueo.alCarrito ? (
              <Boton tamano="M" to={RUTAS.carrito}>
                {t('compra.volverCarrito')}
              </Boton>
            ) : (
              <Boton tamano="M" onClick={cargar}>
                {t('carrito.reintentar')}
              </Boton>
            )}
          </EstadoVacio>
        )}
        {!bloqueo && (!carrito || !puntos) && <p className="visually-hidden">{t('compra.preparando')}</p>}
        {!bloqueo && puntos?.length === 0 && (
          <EstadoVacio
            titulo={t('compra.sinPunto')}
            descripcion={t('compra.sinPuntoTexto')}
          >
            <Boton tamano="M" to={RUTAS.carrito}>
              {t('compra.volverCarrito')}
            </Boton>
          </EstadoVacio>
        )}

        {!bloqueo && carrito && puntos?.length > 0 && (
          <form className="compra__cuerpo" onSubmit={pagar} noValidate>
            <div className="compra__secciones">
              <section className="compra__seccion" aria-labelledby="compra-recojo">
                <h2 id="compra-recojo" className="compra__seccion-titulo">{t('compra.puntoTitulo')}</h2>
                <p className="caption compra__ayuda">{t('compra.puntoAyuda')}</p>
                <OpcionTarjeta
                  nombre="punto"
                  etiqueta={t('compra.eligeDonde')}
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
                <h2 id="compra-quien" className="compra__seccion-titulo">{t('compra.quienTitulo')}</h2>
                <label className="compra__check">
                  <input type="checkbox" checked={form.recogeOtro} onChange={alEscribir('recogeOtro')} />
                  {t('compra.otraPersona')}
                </label>
                {form.recogeOtro && (
                  <Campo
                    etiqueta={t('compra.personaAutorizada')}
                    value={form.personaAutorizada}
                    onChange={alEscribir('personaAutorizada')}
                    error={errores.personaAutorizada}
                    maxLength={160}
                  />
                )}
                <Campo
                  etiqueta={t('compra.celular')}
                  ayuda={t('compra.celularAyuda')}
                  value={form.celular}
                  onChange={alEscribir('celular')}
                  error={errores.celular}
                  inputMode="numeric"
                  autoComplete="tel-national"
                  placeholder="987654321"
                />
              </section>

              <section className="compra__seccion" aria-labelledby="compra-pago">
                <h2 id="compra-pago" className="compra__seccion-titulo">{t('compra.pagoTitulo')}</h2>
                <p className="compra__seguro">
                  <Icono src={iconoEscudo} tamano={16} />
                  {t('compra.noGuardamos')}
                </p>
                <Campo
                  etiqueta={t('compra.numero')}
                  ayuda={nombreMarca(marcaTarjeta) ?? undefined}
                  value={form.numero}
                  onChange={alEscribir('numero')}
                  error={errores.numero}
                  inputMode="numeric"
                  autoComplete="cc-number"
                  placeholder="1234 5678 9012 3456"
                />
                <Campo
                  etiqueta={t('compra.titular')}
                  value={form.titular}
                  onChange={alEscribir('titular')}
                  error={errores.titular}
                  autoComplete="cc-name"
                  maxLength={120}
                />
                <div className="compra__fila">
                  <Campo tipo="selector" etiqueta={t('compra.mes')} opciones={MESES} placeholder="MM" value={form.mes}
                    onChange={alEscribir('mes')} error={errores.mes} autoComplete="cc-exp-month" />
                  <Campo tipo="selector" etiqueta={t('compra.anio')} opciones={ANIOS} placeholder={t('compra.anioPlaceholder')} value={form.anio}
                    onChange={alEscribir('anio')} error={errores.anio} autoComplete="cc-exp-year" />
                  <Campo etiqueta="CVV" value={form.cvv} onChange={alEscribir('cvv')} error={errores.cvv}
                    inputMode="numeric" autoComplete="cc-csc" maxLength={largoCvv(marcaTarjeta)}
                    placeholder={'•'.repeat(largoCvv(marcaTarjeta))} />
                </div>
                <Alerta
                  tono="info"
                  titulo={t('compra.pruebas')}
                  descripcion={t('compra.pruebasTexto')}
                />
              </section>
            </div>

            <aside className="compra__resumen" aria-labelledby="compra-resumen">
              <h2 id="compra-resumen" className="compra__resumen-titulo">{t('carrito.resumen')}</h2>
              <ul className="compra__lineas">
                {comprables.map((i) => (
                  <li key={i.productoId}>
                    <span>
                      {i.nombre} <span className="compra__cantidad">× {Number(i.cantidad)}</span>
                      <span className="caption compra__productor">{t('carrito.deTienda', { nombre: i.tienda.nombre })}</span>
                    </span>
                    <span>{precio(i.subtotal)}</span>
                  </li>
                ))}
              </ul>
              <dl className="compra__cifras">
                <div>
                  <dt>{t('comunidades.titulo')}</dt>
                  <dd>{carrito.comunidades}</dd>
                </div>
                <div>
                  <dt>{t('compra.recojo')}</dt>
                  <dd>{t('compra.gratis')}</dd>
                </div>
              </dl>
              <p className="compra__total">
                <span>{t('carrito.total')}</span>
                <strong>{precio(carrito.total)}</strong>
              </p>
              {aviso && (
                <div ref={refAviso} className="compra__aviso">
                  <Alerta {...aviso} onCerrar={() => setAviso(null)} />
                </div>
              )}
              <Boton tipo="acento" anchoCompleto type="submit" disabled={enviando}>
                {enviando ? t('compra.procesando') : t('compra.pagar', { monto: precio(carrito.total) })}
              </Boton>
              <p className="caption compra__nota">{t('compra.notaStock')}</p>
            </aside>
          </form>
        )}
      </main>
    </LayoutTienda>
  )
}
