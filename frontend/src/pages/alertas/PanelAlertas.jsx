import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCaja from '../../assets/icons/caja.svg'
import iconoCheck from '../../assets/icons/check.svg'
import iconoManoAyuda from '../../assets/icons/mano-ayuda.svg'
import iconoMovimientos from '../../assets/icons/movimientos.svg'
import iconoPaquete from '../../assets/icons/paquete.svg'
import iconoPausa from '../../assets/icons/pausa.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { consultarPreferencias, guardarPreferencias, listarAlertas, marcarLeida, marcarTodasLeidas } from '../../api/alertas.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import Enlace from '../../components/Enlace.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import { fechaCorta, fechaHora, unidades } from '../../formato.js'
import { erroresPorCampo, mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import '../../components/Chips.css'
import './PanelAlertas.css'

/** Orden de los filtros y de los tipos en "Que quiero recibir". El servidor dice cuales tiene el rol. */
const TIPOS = ['POR_VENCER', 'ESTANCADO', 'REPOSICION', 'INTERES', 'TRANSFERENCIA_RECIBIDA', 'PEDIDO_NUEVO']

const ICONO = {
  POR_VENCER: iconoReloj,
  ESTANCADO: iconoPausa,
  REPOSICION: iconoCaja,
  INTERES: iconoManoAyuda,
  TRANSFERENCIA_RECIBIDA: iconoMovimientos,
  PEDIDO_NUEVO: iconoPaquete,
}

/** CCPIP-425: cada cuanto se vuelve a consultar la bandeja abierta, que el servidor pone al dia al responder. */
const ACTUALIZAR_CADA_MS = 15000

/** Las opciones del Figma ("Elige 7, 15 o 30 dias, o un numero personalizado"). */
const OPCIONES_DIAS = { diasVencimiento: [7, 15, 30], diasEstancado: [15, 30, 60] }

/** A donde lleva cada alerta. */
function destino(alerta, rol) {
  if (rol === 'acopio') return RUTAS.acopioInventario
  switch (alerta.tipo) {
    case 'REPOSICION':
      return RUTAS.productorLotes
    case 'INTERES':
      return `${RUTAS.productorProductos}/${alerta.productoId}`
    case 'TRANSFERENCIA_RECIBIDA':
      return RUTAS.productorTransferencias
    case 'PEDIDO_NUEVO':
      return RUTAS.productorPedidos
    default:
      return RUTAS.productorAlmacen
  }
}

/**
 * PW-21 (productor) y A-08 (punto de acopio): la bandeja de alertas y "Configurar alertas".
 * CCPIP-79 y 145 (por vencer), 337 (estancados), 293 (reposicion), 366 (interes) y 367 (eventos).
 * `rol`: 'productor' | 'acopio'. `onPuntos` recibe los puntos del administrador para su encabezado.
 */
export default function PanelAlertas({ rol, onPuntos }) {
  const { t } = useTranslation()
  const [filtro, setFiltro] = useState('TODAS')
  const consultar = useCallback(() => listarAlertas(rol), [rol])
  const { datos, setDatos, error, recargar } = useConsulta(consultar, { cadaMs: ACTUALIZAR_CADA_MS })

  const alertas = datos?.alertas ?? []
  const tiposConAlertas = TIPOS.filter((tipo) => alertas.some((a) => a.tipo === tipo))
  const visibles = filtro === 'TODAS' ? alertas : alertas.filter((a) => a.tipo === filtro)

  function leida(id) {
    setDatos((d) => ({
      ...d,
      sinLeer: Math.max(0, d.sinLeer - (d.alertas.some((a) => a.id === id && !a.leida && !a.resuelta) ? 1 : 0)),
      alertas: d.alertas.map((a) => (a.id === id ? { ...a, leida: true } : a)),
    }))
    // Si falla, la alerta vuelve a verse sin leer la proxima vez: no detiene la navegacion
    marcarLeida(rol, id).catch(() => {})
  }

  function todasLeidas() {
    marcarTodasLeidas(rol)
      .then(() => setDatos((d) => ({ ...d, sinLeer: 0, alertas: d.alertas.map((a) => ({ ...a, leida: true })) })))
      .catch(() => {})
  }

  return (
    <div className="alertas">
      <section className="alertas__bandeja" aria-label={t('alertas.bandeja')}>
        {datos && alertas.length > 0 && (
          <div className="alertas__barra">
            <div className="chips__lista" role="group" aria-label={t('alertas.filtrar')}>
              {['TODAS', ...tiposConAlertas].map((f) => (
                <button
                  key={f}
                  type="button"
                  className={`chip${filtro === f ? ' chip--activo' : ''}`}
                  aria-pressed={filtro === f}
                  onClick={() => setFiltro(f)}
                >
                  {filtro === f && <Icono src={iconoCheck} tamano={14} />}
                  {f === 'TODAS' ? t('alertas.todas', { total: alertas.length }) : t(`alertas.filtro.${f}`)}
                </button>
              ))}
            </div>
            {datos?.sinLeer > 0 && (
              <Boton tipo="terciario" tamano="S" onClick={todasLeidas}>
                {t('alertas.marcarTodas')}
              </Boton>
            )}
          </div>
        )}

        {error && <Alerta tono="error" titulo={t('alertas.errorCargar')} descripcion={t('comun.errorConexion')} />}
        {!datos && !error && <Cargando />}
        {datos && alertas.length === 0 && <EstadoVacio titulo={t('alertas.vacio')} descripcion={t(`alertas.vacioTexto.${rol}`)} />}

        {visibles.length > 0 && (
          <ul className="alertas__lista">
            {visibles.map((a) => (
              <ItemAlerta key={a.id} alerta={a} rol={rol} onAbrir={() => leida(a.id)} />
            ))}
          </ul>
        )}
      </section>

      {/* Otra anticipacion abre o cierra alertas: la bandeja se vuelve a pedir al guardarla */}
      <ConfigurarAlertas rol={rol} onPuntos={onPuntos} onGuardado={recargar} />
    </div>
  )
}

function ItemAlerta({ alerta: a, rol, onAbrir }) {
  const { t } = useTranslation()
  const nueva = !a.leida && !a.resuelta
  const datos = {
    producto: a.producto,
    lote: a.lote,
    ubicacion: a.ubicacion,
    fecha: fechaCorta(a.fecha),
    cantidad: a.cantidad == null ? '' : unidades(a.cantidad),
    personas: a.personas,
    referencia: a.referencia,
    productos: (a.productos ?? []).join(', '),
  }
  return (
    <li className={`alertas__item alertas__item--${a.tipo}${nueva ? ' alertas__item--nueva' : ''}${a.resuelta ? ' alertas__item--resuelta' : ''}`}>
      <span className="alertas__icono" aria-hidden="true">
        <Icono src={ICONO[a.tipo]} tamano={18} />
      </span>
      <div className="alertas__texto">
        <p className="alertas__titulo">
          {t(`alertas.tipo.${a.tipo}.titulo`, datos)}
          {a.resuelta && <Badge tono="neutro">{t('alertas.resuelta')}</Badge>}
        </p>
        <p className="body-s">{t(`alertas.tipo.${a.tipo}.detalle${rol === 'acopio' ? 'Punto' : ''}`, datos)}</p>
        <p className="alertas__meta caption">
          <span>{fechaHora(a.creadaEn)}</span>
          {a.porCorreo && <span>{t('alertas.tambienCorreo')}</span>}
          <Enlace to={destino(a, rol)} onClick={onAbrir} className="alertas__accion">
            {t(`alertas.tipo.${a.tipo}.accion${rol === 'acopio' ? 'Punto' : ''}`)}
          </Enlace>
        </p>
      </div>
      {nueva && <span className="alertas__punto" role="img" aria-label={t('alertas.sinLeer')} />}
    </li>
  )
}

function ConfigurarAlertas({ rol, onPuntos, onGuardado }) {
  const { t } = useTranslation()
  const [puntos, setPuntos] = useState(null)
  const [punto, setPunto] = useState(null)
  const [form, setForm] = useState(null)
  const [errores, setErrores] = useState({})
  const [estado, setEstado] = useState(null)
  const [guardando, setGuardando] = useState(false)

  useEffect(() => {
    consultarPreferencias(rol)
      .then((r) => {
        if (rol === 'acopio') {
          setPuntos(r)
          onPuntos?.(r)
          if (r.length > 0) elegir(r[0])
        } else {
          setForm(formulario(r))
        }
      })
      .catch(() => setEstado({ tono: 'error', titulo: t('alertas.config.errorCargar') }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rol])

  function elegir(p) {
    setPunto(p.ubicacionId)
    setForm(formulario(p.preferencias))
    setErrores({})
    setEstado(null)
  }

  function cambiar(campo, valor) {
    setForm((f) => ({ ...f, [campo]: valor }))
    setErrores((e) => ({ ...e, [campo]: undefined }))
    setEstado(null)
  }

  function guardar(e) {
    e.preventDefault()
    const datos = {
      diasVencimiento: dias(form.diasVencimiento),
      diasEstancado: dias(form.diasEstancado),
      tipos: form.tipos,
      correo: form.correo,
    }
    const invalidos = {}
    for (const campo of ['diasVencimiento', 'diasEstancado']) {
      if (!Number.isInteger(datos[campo]) || datos[campo] < 1 || datos[campo] > 365) invalidos[campo] = t('alertas.config.diasInvalidos')
    }
    if (Object.keys(invalidos).length > 0) {
      setErrores(invalidos)
      return
    }
    setGuardando(true)
    guardarPreferencias(rol, datos, punto)
      .then((r) => {
        const preferencias = rol === 'acopio' ? r.preferencias : r
        setForm(formulario(preferencias))
        if (rol === 'acopio') setPuntos((ps) => ps.map((p) => (p.ubicacionId === r.ubicacionId ? r : p)))
        setEstado({ tono: 'exito', titulo: t('alertas.config.guardado') })
        onGuardado()
      })
      .catch((err) => {
        setErrores(err.body?.campos ? erroresPorCampo(err) : {})
        setEstado({ tono: 'error', titulo: mensajeError(err) })
      })
      .finally(() => setGuardando(false))
  }

  return (
    <form className="alertas__config tarjeta-panel" onSubmit={guardar} aria-labelledby="titulo-config-alertas" noValidate>
      <h2 id="titulo-config-alertas" className="heading-h4">{t('alertas.config.titulo')}</h2>
      {estado && <Alerta tono={estado.tono} titulo={estado.titulo} />}
      {/* CCPIP-413: la tarjeta no queda vacia mientras llegan las preferencias */}
      {!form && puntos === null && !estado && <Cargando />}
      {puntos?.length > 1 && (
        <Campo
          tipo="selector"
          etiqueta={t('alertas.config.punto')}
          placeholder={t('alertas.config.elegirPunto')}
          value={punto ?? ''}
          onChange={(e) => {
            const p = puntos.find((x) => String(x.ubicacionId) === e.target.value)
            if (p) elegir(p)
          }}
          opciones={puntos.map((p) => ({ valor: String(p.ubicacionId), etiqueta: p.punto }))}
        />
      )}
      {form && (
        <>
          <SelectorDias
            campo="diasVencimiento"
            valor={form.diasVencimiento}
            error={errores.diasVencimiento}
            ayuda={t(rol === 'acopio' ? 'alertas.config.ayudaPunto' : 'alertas.config.ayuda')}
            onCambiar={cambiar}
          />
          <SelectorDias campo="diasEstancado" valor={form.diasEstancado} error={errores.diasEstancado} onCambiar={cambiar} />
          {form.provisional && <p className="caption alertas__provisional">{t('alertas.config.provisional')}</p>}

          <fieldset className="alertas__grupo">
            <legend className="label-m">{t('alertas.config.queRecibir')}</legend>
            {TIPOS.filter((tipo) => form.disponibles.includes(tipo)).map((tipo) => (
              <label key={tipo} className="alertas__check">
                <input
                  type="checkbox"
                  checked={form.tipos.includes(tipo)}
                  onChange={(e) => cambiar('tipos', e.target.checked ? [...form.tipos, tipo] : form.tipos.filter((x) => x !== tipo))}
                />
                {t(`alertas.recibir.${tipo}`)}
              </label>
            ))}
          </fieldset>

          <fieldset className="alertas__grupo">
            <legend className="label-m">{t('alertas.config.canales')}</legend>
            <label className="alertas__check">
              {/* Siempre activo: se ve marcado, no se puede desmarcar */}
              <input type="checkbox" checked aria-disabled="true" onChange={() => {}} />
              {t('alertas.config.plataforma')}
            </label>
            <label className="alertas__check">
              <input type="checkbox" checked={form.correo} onChange={(e) => cambiar('correo', e.target.checked)} />
              {t('alertas.config.correo')}
            </label>
          </fieldset>

          <Boton type="submit" anchoCompleto tamano="M" cargando={guardando}>
            {t('alertas.config.guardar')}
          </Boton>
        </>
      )}
    </form>
  )
}

/** Selector de 7, 15 o 30 dias (o 15, 30, 60) con "Personalizado", que abre un campo numerico. */
function SelectorDias({ campo, valor, error, ayuda, onCambiar }) {
  const { t } = useTranslation()
  const opciones = OPCIONES_DIAS[campo]
  const personalizado = valor.personalizado
  return (
    <div className="alertas__dias">
      <Campo
        tipo="selector"
        etiqueta={t(`alertas.config.${campo}`)}
        placeholder={t('alertas.config.elegirDias')}
        value={personalizado ? 'OTRO' : String(valor.dias)}
        onChange={(e) => {
          const v = e.target.value
          if (v === 'OTRO') onCambiar(campo, { personalizado: true, dias: String(valor.dias) })
          else if (v) onCambiar(campo, { personalizado: false, dias: Number(v) })
        }}
        opciones={[
          ...opciones.map((d) => ({ valor: String(d), etiqueta: t('alertas.config.nDias', { dias: d }) })),
          { valor: 'OTRO', etiqueta: t('alertas.config.personalizado') },
        ]}
        ayuda={personalizado ? undefined : ayuda}
        error={personalizado ? undefined : error}
      />
      {personalizado && (
        <Campo
          etiqueta={t('alertas.config.diasPersonalizados')}
          inputMode="numeric"
          maxLength={3}
          value={valor.dias}
          onChange={(e) => onCambiar(campo, { personalizado: true, dias: e.target.value.replace(/\D/g, '') })}
          ayuda={t('alertas.config.rango')}
          error={error}
        />
      )}
    </div>
  )
}

function formulario(p) {
  return {
    diasVencimiento: { dias: p.diasVencimiento, personalizado: !OPCIONES_DIAS.diasVencimiento.includes(p.diasVencimiento) },
    diasEstancado: { dias: p.diasEstancado, personalizado: !OPCIONES_DIAS.diasEstancado.includes(p.diasEstancado) },
    tipos: p.tipos,
    disponibles: p.disponibles,
    correo: p.correo,
    provisional: p.provisional,
  }
}

const dias = (v) => Number(v.dias)
