import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarInventario, crearTransferencia, ubicacionesTransferencia } from '../../api/productor.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import { nombreLugar } from '../../components/DetalleTransferencia.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import { fechaCorta, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { navigate, RUTAS } from '../../router.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'
import './TransferenciasPage.css'

const TIPOS = ['ALMACEN_A_PUNTO', 'ENTRE_PUNTOS']
const CANTIDAD = /^\d{1,9}(\.\d{1,3})?$/

/** Ubicaciones e inventario en una sola carga: ambos hacen falta para armar el formulario. */
const cargar = () => Promise.all([ubicacionesTransferencia(), consultarInventario()]).then(([ubicaciones, inventario]) => ({ ubicaciones, inventario }))

/**
 * PW-18 · Nueva transferencia. CCPIP-73 (RF-67): de su almacen a un punto de acopio; CCPIP-277
 * (RF-177): entre puntos de acopio, como solicitud. CCPIP-146 (RF-112): la ubicacion registrada de
 * cada lugar para elegir el destino, sin mapas ni rutas (RNF-31). Iniciarla no descuenta nada: eso lo
 * hace la preparacion. El servidor revisa otra vez que cada lote sea suyo, este en el origen y alcance.
 */
export default function NuevaTransferenciaPage() {
  const { t } = useTranslation()
  const consultar = useCallback(cargar, [])
  const { datos, error, recargar } = useConsulta(consultar)
  const [tipo, setTipo] = useState('ALMACEN_A_PUNTO')
  const [origenPunto, setOrigenPunto] = useState('')
  const [destino, setDestino] = useState('')
  const [cantidades, setCantidades] = useState({})
  const [observacion, setObservacion] = useState('')
  const [errores, setErrores] = useState({})
  const [errorEnvio, setErrorEnvio] = useState(null)
  const [enviando, setEnviando] = useState(false)

  const almacen = datos?.ubicaciones.almacen ?? null
  const puntos = datos?.ubicaciones.puntos ?? []
  const origen = tipo === 'ALMACEN_A_PUNTO' ? almacen : puntos.find((p) => String(p.id) === origenPunto) ?? null
  const destinos = puntos.filter((p) => p.id !== origen?.id)
  const lugarDestino = destinos.find((p) => String(p.id) === destino) ?? null
  // Lotes con saldo y vigentes en el origen; un vencido no se transfiere
  const lotes = origen
    ? (datos?.inventario.productos ?? []).flatMap((p) =>
      p.lotes.filter((l) => l.ubicacionId === origen.id && l.estado !== 'VENCIDO').map((l) => ({ ...l, producto: p.nombre })))
    : []

  function cambiarTipo(nuevo) {
    setTipo(nuevo)
    setOrigenPunto('')
    setDestino('')
    setCantidades({})
    setErrores({})
  }

  function cambiarOrigen(valor) {
    setOrigenPunto(valor)
    setCantidades({})
    if (valor === destino) setDestino('')
  }

  function validar() {
    const e = {}
    if (!origen) e.origen = t('productor.transferencias.form.errorOrigen')
    if (!lugarDestino) e.destino = t('productor.transferencias.form.errorDestino')
    const elegidos = lotes.filter((l) => (cantidades[l.id] ?? '').trim() !== '')
    for (const l of elegidos) {
      const texto = cantidades[l.id].trim()
      if (!CANTIDAD.test(texto) || Number(texto) <= 0) e[`lote-${l.id}`] = t('productor.transferencias.form.errorCantidad')
      else if (Number(texto) > Number(l.cantidad)) e[`lote-${l.id}`] = t('productor.transferencias.form.errorSaldo', { cantidad: unidades(l.cantidad) })
    }
    if (origen && elegidos.length === 0) e.lotes = t('productor.transferencias.form.errorLotes')
    return { e, elegidos }
  }

  async function enviar(ev) {
    ev.preventDefault()
    setErrorEnvio(null)
    const { e, elegidos } = validar()
    setErrores(e)
    if (Object.keys(e).length) return
    setEnviando(true)
    try {
      const creada = await crearTransferencia({
        tipo,
        origenId: tipo === 'ENTRE_PUNTOS' ? origen.id : undefined,
        destinoId: lugarDestino.id,
        items: elegidos.map((l) => ({ loteId: l.id, cantidad: cantidades[l.id].trim() })),
        observacion: observacion.trim() || undefined,
      })
      navigate(`${RUTAS.productorTransferencias}?creada=${encodeURIComponent(creada.codigo)}`)
    } catch (err) {
      setErrorEnvio(err)
      setEnviando(false)
    }
  }

  return (
    <PaginaProductor path={RUTAS.productorTransferencias} seccion={t('productor.nav.transferencias')} titulo={t('productor.transferencias.form.titulo')}>
      {error && (
        <EstadoVacio titulo={t('productor.transferencias.form.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}

      {!datos && !error && <Cargando />}

      {datos && (
        <form className="nueva-transferencia" onSubmit={enviar} noValidate>
          <fieldset>
            <legend className="heading-h3">{t('productor.transferencias.form.tipo')}</legend>
            <div className="nueva-transferencia__tipos">
              {TIPOS.map((tp) => (
                <label key={tp} className="nueva-transferencia__tipo">
                  <input type="radio" name="tipo" value={tp} checked={tipo === tp} onChange={() => cambiarTipo(tp)} />
                  <span>
                    <span className="label-m">{t(`transferencias.tipo.${tp}`)}</span>
                    <span className="caption">{t(`productor.transferencias.form.tipoTexto.${tp}`)}</span>
                  </span>
                </label>
              ))}
            </div>
            {tipo === 'ENTRE_PUNTOS' && <Alerta tono="info" titulo={t('productor.transferencias.form.solicitud')} descripcion={t('transferencias.pendienteAprobador')} />}
          </fieldset>

          <fieldset>
            <legend className="heading-h3">{t('productor.transferencias.form.lugares')}</legend>
            <div className="nueva-transferencia__lugares">
              <div>
                {tipo === 'ALMACEN_A_PUNTO' ? (
                  <>
                    <p className="campo__etiqueta">{t('transferencias.origen')}</p>
                    {almacen ? (
                      <Ficha lugar={almacen} etiqueta={t('productor.transferencias.form.fichaOrigen')} />
                    ) : (
                      <Alerta tono="aviso" titulo={t('errores.SIN_ALMACEN')} />
                    )}
                  </>
                ) : (
                  <>
                    <Campo
                      tipo="selector"
                      etiqueta={t('transferencias.origen')}
                      value={origenPunto}
                      onChange={(e) => cambiarOrigen(e.target.value)}
                      opciones={puntos.map((p) => ({ valor: String(p.id), etiqueta: p.nombre }))}
                      placeholder={t('productor.transferencias.form.elegirPunto')}
                      error={errores.origen}
                    />
                    {origen && <Ficha lugar={origen} etiqueta={t('productor.transferencias.form.fichaOrigen')} />}
                  </>
                )}
              </div>
              <div>
                <Campo
                  tipo="selector"
                  etiqueta={t('transferencias.destino')}
                  value={destino}
                  onChange={(e) => setDestino(e.target.value)}
                  opciones={destinos.map((p) => ({ valor: String(p.id), etiqueta: p.nombre }))}
                  placeholder={t('productor.transferencias.form.elegirPunto')}
                  error={errores.destino}
                />
                {lugarDestino && <Ficha lugar={lugarDestino} etiqueta={t('productor.transferencias.form.fichaDestino')} />}
              </div>
            </div>
            <p className="productor__nota">{t('productor.transferencias.form.sinRutas')}</p>
          </fieldset>

          <fieldset>
            <legend className="heading-h3">{t('productor.transferencias.form.lotes')}</legend>
            {!origen && <p className="body-s">{t('productor.transferencias.form.primeroOrigen')}</p>}
            {origen && lotes.length === 0 && (
              <EstadoVacio titulo={t('productor.transferencias.form.sinLotes', { lugar: nombreLugar(origen, t) })} descripcion={t('productor.transferencias.form.sinLotesTexto')} />
            )}
            {lotes.length > 0 && (
              <div className="tabla">
                {lotes.map((l) => (
                  <div key={l.id} className="nueva-transferencia__lote">
                    <span className="nueva-transferencia__lote-datos">
                      <span className="label-m">{l.producto}</span>
                      <span className="caption">
                        {t('productor.transferencias.form.loteDatos', {
                          codigo: l.codigo,
                          vence: l.fechaVencimiento ? fechaCorta(l.fechaVencimiento) : t('transferencias.sinVencimiento'),
                          cantidad: unidades(l.cantidad),
                        })}
                      </span>
                    </span>
                    <Campo
                      etiqueta={t('productor.transferencias.form.cantidadDe', { codigo: l.codigo })}
                      inputMode="decimal"
                      value={cantidades[l.id] ?? ''}
                      onChange={(e) => setCantidades((c) => ({ ...c, [l.id]: e.target.value }))}
                      placeholder="0"
                      error={errores[`lote-${l.id}`]}
                    />
                  </div>
                ))}
              </div>
            )}
            {errores.lotes && <Alerta tono="error" titulo={errores.lotes} />}
          </fieldset>

          <Campo
            tipo="area"
            etiqueta={t('productor.transferencias.form.observacion')}
            value={observacion}
            maxLength={255}
            onChange={(e) => setObservacion(e.target.value)}
            ayuda={t('productor.transferencias.form.observacionAyuda')}
          />

          {errorEnvio && <Alerta tono="error" titulo={t('productor.transferencias.form.errorEnviar')} descripcion={mensajeError(errorEnvio, 'comun.errorConexion')} />}

          <div className="nueva-transferencia__acciones">
            <Boton tipo="primario" tamano="L" type="submit" cargando={enviando}>
              {t(enviando ? 'productor.transferencias.form.enviando' : tipo === 'ENTRE_PUNTOS' ? 'productor.transferencias.form.solicitar' : 'productor.transferencias.form.iniciar')}
            </Boton>
            <Boton tipo="terciario" tamano="L" to={RUTAS.productorTransferencias}>{t('comun.cancelar')}</Boton>
          </div>
        </form>
      )}
    </PaginaProductor>
  )
}

/** CCPIP-146: la ubicacion registrada de un lugar (departamento, provincia, distrito y direccion). */
function Ficha({ lugar, etiqueta }) {
  const { t } = useTranslation()
  const zona = [lugar.distrito, lugar.provincia, lugar.departamento].filter(Boolean).join(', ')
  return (
    <dl className="nueva-transferencia__ficha" aria-label={etiqueta}>
      <dt className="visually-hidden">{t('productor.transferencias.form.nombre')}</dt>
      <dd className="label-m">{nombreLugar(lugar, t)}</dd>
      {zona && (
        <>
          <dt className="visually-hidden">{t('productor.transferencias.form.zona')}</dt>
          <dd>{zona}</dd>
        </>
      )}
      {lugar.direccion && (
        <>
          <dt className="visually-hidden">{t('productor.transferencias.form.direccion')}</dt>
          <dd>{lugar.direccion}</dd>
        </>
      )}
    </dl>
  )
}
