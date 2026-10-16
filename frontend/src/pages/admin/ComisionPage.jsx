import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoInfo from '../../assets/icons/info.svg'
import { cancelarComision, obtenerComision, programarComision } from '../../api/admin.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Dialogo from '../../components/Dialogo.jsx'
import Icono from '../../components/Icono.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaCorta, fechaLarga, precio, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './admin.css'

const VENTA_EJEMPLO = 100
const PORCENTAJE = /^\d{1,2}(\.\d{1,2})?$/
// Codes del servidor que senalan un campo concreto
const CAMPO_DEL_CODE = { VIGENCIA_PASADA: 'vigenteDesde', VIGENCIA_OCUPADA: 'vigenteDesde', MISMO_PORCENTAJE: 'porcentaje' }
const TONO_ESTADO = { VIGENTE: 'exito', PROGRAMADA: 'info', ANTERIOR: 'neutro', CANCELADA: 'neutro' }

const vacio = (hoy) => ({ porcentaje: '', vigenteDesde: hoy ?? '', motivo: '' })

/** El porcentaje escrito, si es valido (0 a menos de 100, hasta 2 decimales); si no, null. */
function porcentajeValido(texto) {
  const limpio = texto.trim().replace(',', '.')
  return PORCENTAJE.test(limpio) ? Number(limpio) : null
}

/**
 * SA-02 (CCPIP-90): el superadministrador ve la comision vigente y programa un cambio con fecha y
 * motivo. Uno pendiente a la vez; los pedidos ya confirmados conservan su comision. La base de calculo
 * queda fija hasta que CCPIP-101 defina el flete. "Parametros de la plataforma" llega con sus historias.
 */
export default function ComisionPage({ path }) {
  const { t } = useTranslation()
  const [estado, setEstado] = useState(null)
  const [errorCarga, setErrorCarga] = useState(false)
  const [f, setF] = useState(vacio())
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [cancelando, setCancelando] = useState(false)
  const [confirmarCancelar, setConfirmarCancelar] = useState(false)

  const aplicar = (e) => {
    setEstado(e)
    setF(vacio(e.hoy))
    setErrores({})
  }

  useEffect(() => {
    obtenerComision().then(aplicar).catch(() => setErrorCarga(true))
  }, [])

  const set = (campo) => (e) => {
    setF((actual) => ({ ...actual, [campo]: e.target.value }))
    setErrores((actual) => ({ ...actual, [campo]: undefined }))
  }

  function validar() {
    const e = {}
    const p = porcentajeValido(f.porcentaje)
    if (!f.porcentaje.trim()) e.porcentaje = t('admin.comision.validacion.porcentajeVacio')
    else if (p == null || p >= 100) e.porcentaje = t('admin.comision.validacion.porcentaje')
    else if (estado.vigente && p === Number(estado.vigente.porcentaje)) e.porcentaje = t('admin.comision.validacion.mismo')
    if (!f.vigenteDesde) e.vigenteDesde = t('admin.comision.validacion.fechaVacia')
    else if (f.vigenteDesde < estado.hoy) e.vigenteDesde = t('admin.comision.validacion.fechaPasada')
    if (!f.motivo.trim()) e.motivo = t('admin.comision.validacion.motivo')
    return e
  }

  async function programar(evento) {
    evento.preventDefault()
    setAlerta(null)
    const e = validar()
    setErrores(e)
    if (Object.keys(e).length) return
    setEnviando(true)
    try {
      const desde = f.vigenteDesde
      const nuevo = await programarComision({ porcentaje: porcentajeValido(f.porcentaje), vigenteDesde: desde, motivo: f.motivo.trim() })
      aplicar(nuevo)
      setAlerta(desde === nuevo.hoy
        ? { tono: 'exito', titulo: t('admin.comision.aplicada', { porcentaje: unidades(nuevo.vigente.porcentaje) }) }
        : { tono: 'exito', titulo: t('admin.comision.programada', { porcentaje: unidades(nuevo.programado.porcentaje), fecha: fechaLarga(desde) }) })
    } catch (error) {
      const campos = {}
      Object.keys(error.body?.campos ?? {}).forEach((campo) => {
        if (campo in f) campos[campo] = t(`admin.comision.validacion.${campo === 'vigenteDesde' ? 'fechaVacia' : campo}`)
      })
      const porCode = CAMPO_DEL_CODE[error.code]
      if (porCode) campos[porCode] = mensajeError(error)
      setErrores(campos)
      if (!Object.keys(campos).length) setAlerta({ tono: 'error', titulo: t('admin.comision.errorGuardar'), descripcion: mensajeError(error) })
    } finally {
      setEnviando(false)
    }
  }

  async function cancelarProgramado() {
    setCancelando(true)
    try {
      const nuevo = await cancelarComision(estado.programado.id)
      aplicar(nuevo)
      setAlerta({ tono: 'exito', titulo: t('admin.comision.cancelado') })
    } catch (error) {
      setAlerta({ tono: 'error', titulo: t('admin.comision.errorCancelar'), descripcion: mensajeError(error) })
    } finally {
      setCancelando(false)
      setConfirmarCancelar(false)
    }
  }

  return (
    <LayoutAdmin path={path} migas={t('admin.nav.comision')} titulo={t('admin.comision.titulo')}>
      {errorCarga && <Alerta tono="error" titulo={t('admin.comision.errorCargar')} descripcion={t('comun.errorConexion')} />}
      {!estado && !errorCarga && <p className="caption">{t('comun.cargando')}</p>}
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {estado && (
        <div className="comision">
          <section className="tarjeta-panel comision__principal" aria-labelledby="comision-vigente">
            <Vigente estado={estado} />
            {estado.programado ? (
              <Programado cambio={estado.programado} onCancelar={() => setConfirmarCancelar(true)} />
            ) : (
              <Formulario
                estado={estado}
                f={f}
                set={set}
                errores={errores}
                enviando={enviando}
                onSubmit={programar}
                onLimpiar={() => {
                  setF(vacio(estado.hoy))
                  setErrores({})
                }}
              />
            )}
          </section>
          <Lateral estado={estado} nuevo={estado.programado ? null : porcentajeValido(f.porcentaje)} />
        </div>
      )}
      <Dialogo
        abierto={confirmarCancelar}
        titulo={t('admin.comision.cancelarTitulo')}
        confirmar={t('admin.comision.cancelarConfirmar')}
        tipoConfirmar="destructivo"
        onConfirmar={cancelarProgramado}
        onCancelar={() => setConfirmarCancelar(false)}
        ocupado={cancelando}
      >
        {estado?.programado && (
          <p>{t('admin.comision.cancelarTexto', { porcentaje: unidades(estado.programado.porcentaje), fecha: fechaLarga(estado.programado.vigenteDesde) })}</p>
        )}
      </Dialogo>
    </LayoutAdmin>
  )
}

function Vigente({ estado }) {
  const { t } = useTranslation()
  const v = estado.vigente
  if (!v) {
    return (
      <div className="comision__vigente">
        <h2 id="comision-vigente" className="heading-h2">{t('admin.comision.sinVigente')}</h2>
        <p className="body-s">{t('admin.comision.sinVigenteTexto')}</p>
      </div>
    )
  }
  return (
    <div className="comision__vigente">
      <h2 id="comision-vigente" className="heading-h2">{t('admin.comision.vigente', { porcentaje: unidades(v.porcentaje) })}</h2>
      <p className="body-s">{t('admin.comision.desde', { fecha: fechaLarga(v.vigenteDesde), nombre: v.registradoPor })}</p>
    </div>
  )
}

function Programado({ cambio, onCancelar }) {
  const { t } = useTranslation()
  return (
    <div className="comision__programado" role="status">
      <div>
        <p className="label-m">{t('admin.comision.programadoTitulo', { porcentaje: unidades(cambio.porcentaje), fecha: fechaLarga(cambio.vigenteDesde) })}</p>
        <p className="body-s">{t('admin.comision.motivo', { motivo: cambio.motivo })}</p>
        <p className="caption">{t('admin.comision.programadoTexto')}</p>
      </div>
      <Boton tipo="secundario" tamano="M" onClick={onCancelar}>
        {t('admin.comision.cancelarProgramado')}
      </Boton>
    </div>
  )
}

function Formulario({ estado, f, set, errores, enviando, onSubmit, onLimpiar }) {
  const { t } = useTranslation()
  const desdeHoy = f.vigenteDesde === estado.hoy
  return (
    <form className="comision__formulario" onSubmit={onSubmit} noValidate aria-label={t('admin.comision.nuevo')}>
      <div className="comision__campos">
        <Campo
          etiqueta={t('admin.comision.campo.porcentaje')}
          value={f.porcentaje}
          onChange={set('porcentaje')}
          error={errores.porcentaje}
          inputMode="decimal"
          maxLength={5}
          autoComplete="off"
        />
        <Campo
          etiqueta={t('admin.comision.campo.vigenteDesde')}
          type="date"
          min={estado.hoy}
          value={f.vigenteDesde}
          onChange={set('vigenteDesde')}
          error={errores.vigenteDesde}
        />
      </div>
      <Campo
        etiqueta={t('admin.comision.campo.base')}
        tipo="selector"
        opciones={[{ valor: estado.base, etiqueta: t(`admin.comision.base.${estado.base}`, { defaultValue: estado.base }) }]}
        value={estado.base}
        disabled
        ayuda={t('admin.comision.basePendiente')}
      />
      <Campo
        etiqueta={t('admin.comision.campo.motivo')}
        tipo="area"
        rows={3}
        value={f.motivo}
        onChange={set('motivo')}
        error={errores.motivo}
        maxLength={300}
        placeholder={t('admin.comision.motivoEjemplo')}
      />
      <div className="comision__aviso">
        <Icono src={iconoInfo} tamano={20} />
        <div>
          <p className="label-m">{t('admin.comision.conservan')}</p>
          <p className="body-s">
            {f.vigenteDesde && f.vigenteDesde >= estado.hoy
              ? t(desdeHoy ? 'admin.comision.conservanHoy' : 'admin.comision.conservanDesde', { fecha: fechaCorta(f.vigenteDesde) })
              : t('admin.comision.conservanSinFecha')}
          </p>
        </div>
      </div>
      <div className="comision__acciones">
        <Boton tipo="terciario" tamano="M" onClick={onLimpiar} disabled={enviando}>
          {t('comun.cancelar')}
        </Boton>
        <Boton type="submit" tamano="M" disabled={enviando}>
          {t(desdeHoy ? 'admin.comision.aplicarHoy' : 'admin.comision.programar')}
        </Boton>
      </div>
    </form>
  )
}

/** Vista previa por una venta de S/ 100 (actual -> nuevo) e historial de cambios. */
function Lateral({ estado, nuevo }) {
  const { t } = useTranslation()
  const actual = estado.vigente ? Number(estado.vigente.porcentaje) : null
  const destino = nuevo != null && nuevo < 100 && nuevo !== actual ? nuevo : null
  const camara = (p) => (VENTA_EJEMPLO * p) / 100
  const fila = (etiqueta, calcular) => (
    <div className="comision__previa-fila">
      <dt>{etiqueta}</dt>
      <dd>
        {actual != null && <span className={destino != null ? 'comision__antes' : 'comision__monto'}>{precio(calcular(actual))}</span>}
        {destino != null && (
          <>
            <span aria-hidden="true">→</span>
            <span className="comision__monto">{precio(calcular(destino))}</span>
          </>
        )}
        {actual == null && destino == null && <span className="caption">—</span>}
      </dd>
    </div>
  )
  return (
    <aside className="comision__lateral" aria-labelledby="comision-previa">
      <h2 id="comision-previa" className="heading-h4">{t('admin.comision.previa', { monto: precio(VENTA_EJEMPLO) })}</h2>
      <dl className="comision__previa">
        {fila(t('admin.comision.paraCamara'), camara)}
        {fila(t('admin.comision.paraProductor'), (p) => VENTA_EJEMPLO - camara(p))}
      </dl>
      <h3 id="comision-historial" className="label-m comision__historial-titulo">{t('admin.comision.historial')}</h3>
      {estado.historial.length === 0 ? (
        <p className="caption">{t('admin.comision.sinHistorial')}</p>
      ) : (
        <ol className="comision__historial" aria-labelledby="comision-historial">
          {estado.historial.map((c) => (
            <li key={c.id} className={c.estado === 'CANCELADA' ? 'comision__cancelada' : undefined}>
              <span className="comision__historial-linea">
                <strong>{unidades(c.porcentaje)} %</strong>
                <Badge tono={TONO_ESTADO[c.estado]}>{t(`admin.comision.estado.${c.estado}`)}</Badge>
              </span>
              <span className="caption">
                {t('admin.comision.historialDesde', { fecha: fechaCorta(c.vigenteDesde), nombre: c.registradoPor })}
              </span>
              <span className="caption">{c.motivo}</span>
            </li>
          ))}
        </ol>
      )}
    </aside>
  )
}
