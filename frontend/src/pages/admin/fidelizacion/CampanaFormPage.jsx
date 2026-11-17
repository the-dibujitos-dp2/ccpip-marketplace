import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { crearCampana, editarCampana, obtenerFidelizacion } from '../../../api/fidelizacion.js'
import Alerta from '../../../components/Alerta.jsx'
import Boton from '../../../components/Boton.jsx'
import Campo from '../../../components/Campo.jsx'
import Cargando from '../../../components/Cargando.jsx'
import Chips from '../../../components/Chips.jsx'
import LayoutAdmin from '../../../components/layout/LayoutAdmin.jsx'
import { multiplicador } from '../../../fidelizacion.js'
import { fecha, unidades } from '../../../formato.js'
import { erroresPorCampo, mensajeError } from '../../../i18n/errores.js'
import { navigate, RUTAS } from '../../../router.js'
import '../../productor/productor.css'
import '../admin.css'
import { rutaCampana, TEXTOS, VistaPrevia } from './comun.jsx'
import './fidelizacion.css'

const F = `${TEXTOS}.form`
const ENTERO = /^\d+$/
const MONTO = /^\d+(\.\d{1,2})?$/
// Reglas frecuentes: veces la regla base (1 punto por cada S/ 10)
const ATAJOS = { doble: [2, '10'], triple: [3, '10'], quintuple: [5, '10'], diezPorSol: [10, '1'] }
// Codes del servidor que senalan un campo
const CAMPO_DEL_CODE = {
  CAMPANA_INICIO_PASADO: 'desde', CAMPANA_SE_CRUZA: 'desde', CAMPANA_FIN_ANTES_DE_INICIO: 'hasta',
  CAMPANA_MUY_LARGA: 'hasta', CAMPANA_NO_MEJORA_BASE: 'puntos',
}

const numeroDe = (texto) => texto.trim().replace(',', '.')
const vacio = (hoy) => ({ nombre: '', descripcion: '', desde: hoy ?? '', hasta: '', puntos: '2', cadaSoles: '10', montoMinimo: '', topePuntos: '' })
const desdeCampana = (c) => ({
  nombre: c.nombre, descripcion: c.descripcion ?? '', desde: c.desde, hasta: c.hasta, puntos: String(c.puntos),
  cadaSoles: String(Number(c.cadaSoles)), montoMinimo: Number(c.montoMinimo) > 0 ? String(Number(c.montoMinimo)) : '',
  topePuntos: c.topePuntos ? String(c.topePuntos) : '',
})

/**
 * CCPIP-86: programa una campana de puntos o cambia una que aun no empieza. Al lado, lo que ganarian pedidos de
 * ejemplo con la regla base y con la campana, para ver su efecto antes de guardarla.
 */
export default function CampanaFormPage({ path, id }) {
  const { t } = useTranslation()
  const [panel, setPanel] = useState(null)
  const [errorCarga, setErrorCarga] = useState(null)
  const [f, setF] = useState(null)
  const [errores, setErrores] = useState({})
  const [alerta, setAlerta] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const editando = id != null

  useEffect(() => {
    obtenerFidelizacion().then((p) => {
      const campana = editando ? p.campanas.find((c) => String(c.id) === String(id)) : null
      if (editando && campana?.estado !== 'PROGRAMADA') {
        setErrorCarga(campana ? 'noEditable' : 'noEncontrada')
        return
      }
      setPanel(p)
      setF(campana ? desdeCampana(campana) : vacio(p.hoy))
    }).catch(() => setErrorCarga('cargar'))
  }, [id, editando])

  const set = (campo) => (e) => {
    setF((actual) => ({ ...actual, [campo]: e.target.value }))
    setErrores((actual) => ({ ...actual, [campo]: undefined, ...(campo === 'cadaSoles' ? { puntos: undefined } : {}) }))
  }

  const regla = f && ENTERO.test(f.puntos) && MONTO.test(numeroDe(f.cadaSoles)) && Number(numeroDe(f.cadaSoles)) >= 1
    ? { puntos: Number(f.puntos), cadaSoles: Number(numeroDe(f.cadaSoles)) } : null
  const veces = regla && panel ? multiplicador(regla, panel.reglaBase) : null
  const atajo = f && Object.keys(ATAJOS).find((k) => String(ATAJOS[k][0]) === f.puntos && ATAJOS[k][1] === numeroDe(f.cadaSoles))

  function validar() {
    const e = {}
    if (!f.nombre.trim()) e.nombre = t(`${F}.validacion.nombre`)
    if (!f.desde) e.desde = t(`${F}.validacion.desde`)
    else if (f.desde < panel.hoy) e.desde = t(`${F}.validacion.desdePasado`)
    if (!f.hasta) e.hasta = t(`${F}.validacion.hasta`)
    else if (f.desde && f.hasta < f.desde) e.hasta = t(`${F}.validacion.hastaAntes`)
    if (!ENTERO.test(f.puntos) || Number(f.puntos) < 1 || Number(f.puntos) > 1000) e.puntos = t(`${F}.validacion.puntos`)
    else if (!MONTO.test(numeroDe(f.cadaSoles)) || Number(numeroDe(f.cadaSoles)) < 1) e.cadaSoles = t(`${F}.validacion.cadaSoles`)
    else if (veces <= 1) e.puntos = t(`${F}.validacion.mejorQueBase`)
    if (f.montoMinimo.trim() && !MONTO.test(numeroDe(f.montoMinimo))) e.montoMinimo = t(`${F}.validacion.monto`)
    if (f.topePuntos.trim() && (!ENTERO.test(f.topePuntos) || Number(f.topePuntos) < 1)) e.topePuntos = t(`${F}.validacion.tope`)
    return e
  }

  async function guardar(evento) {
    evento.preventDefault()
    setAlerta(null)
    const e = validar()
    setErrores(e)
    if (Object.keys(e).length) return
    const cuerpo = {
      nombre: f.nombre.trim(), descripcion: f.descripcion.trim() || null, desde: f.desde, hasta: f.hasta,
      puntos: Number(f.puntos), cadaSoles: Number(numeroDe(f.cadaSoles)),
      montoMinimo: f.montoMinimo.trim() ? Number(numeroDe(f.montoMinimo)) : null,
      topePuntos: f.topePuntos.trim() ? Number(f.topePuntos) : null,
    }
    setEnviando(true)
    try {
      const campana = editando ? await editarCampana(id, cuerpo) : await crearCampana(cuerpo)
      navigate(rutaCampana(campana.id))
    } catch (error) {
      const campos = error.body?.campos ? erroresPorCampo(error) : {}
      const porCode = CAMPO_DEL_CODE[error.code]
      if (porCode) campos[porCode] = mensajeError(error)
      setErrores(campos)
      if (!Object.keys(campos).length) setAlerta({ tono: 'error', titulo: t(`${F}.errorGuardar`), descripcion: mensajeError(error) })
    } finally {
      setEnviando(false)
    }
  }

  const titulo = t(editando ? `${F}.tituloEditar` : `${F}.tituloNueva`)
  return (
    <LayoutAdmin path={path} migas={`${t('admin.nav.fidelizacion')} / ${titulo}`} titulo={titulo}>
      {errorCarga && (
        <Alerta tono="error" titulo={t(`${F}.error.${errorCarga}`)} descripcion={errorCarga === 'cargar' ? t('comun.errorConexion') : undefined} />
      )}
      {!f && !errorCarga && <Cargando />}
      {alerta && <Alerta {...alerta} onCerrar={() => setAlerta(null)} />}
      {f && (
        <div className="fidelizacion__formulario-grid">
          <form className="tarjeta-panel fidelizacion__formulario" onSubmit={guardar} noValidate aria-label={titulo}>
            <fieldset className="fidelizacion__grupo">
              <legend className="heading-h4">{t(`${F}.datos`)}</legend>
              <Campo etiqueta={t(`${F}.nombre`)} value={f.nombre} onChange={set('nombre')} error={errores.nombre} maxLength={80}
                placeholder={t(`${F}.nombreEjemplo`)} autoComplete="off" />
              <Campo etiqueta={t(`${F}.descripcion`)} tipo="area" rows={2} value={f.descripcion} onChange={set('descripcion')}
                error={errores.descripcion} maxLength={300} ayuda={t(`${F}.descripcionAyuda`)} />
            </fieldset>
            <fieldset className="fidelizacion__grupo">
              <legend className="heading-h4">{t(`${F}.periodo`)}</legend>
              <div className="fidelizacion__fila">
                <Campo etiqueta={t(`${F}.desde`)} type="date" min={panel.hoy} value={f.desde} onChange={set('desde')} error={errores.desde} />
                <Campo etiqueta={t(`${F}.hasta`)} type="date" min={f.desde || panel.hoy} value={f.hasta} onChange={set('hasta')} error={errores.hasta} />
              </div>
            </fieldset>
            <fieldset className="fidelizacion__grupo">
              <legend className="heading-h4">{t(`${F}.reglaTitulo`)}</legend>
              <Chips
                etiqueta={t(`${F}.atajos`)}
                opciones={Object.keys(ATAJOS).map((k) => ({ valor: k, etiqueta: t(`${F}.atajo.${k}`) }))}
                seleccion={atajo ? [atajo] : []}
                onCambiar={(sel) => {
                  const elegido = sel.find((k) => k !== atajo)
                  if (!elegido) return
                  const [puntos, cadaSoles] = ATAJOS[elegido]
                  setF((actual) => ({ ...actual, puntos: String(puntos), cadaSoles }))
                  setErrores((actual) => ({ ...actual, puntos: undefined, cadaSoles: undefined }))
                }}
              />
              <div className="fidelizacion__fila">
                <Campo etiqueta={t(`${F}.puntos`)} value={f.puntos} onChange={set('puntos')} error={errores.puntos} inputMode="numeric" maxLength={4} />
                <Campo etiqueta={t(`${F}.cadaSoles`)} value={f.cadaSoles} onChange={set('cadaSoles')} error={errores.cadaSoles} inputMode="decimal" maxLength={8} />
              </div>
              {veces > 1 && <p className="body-s fidelizacion__veces">{t(`${F}.veces`, { veces: unidades(Math.round(veces * 10) / 10) })}</p>}
              <div className="fidelizacion__fila">
                <Campo etiqueta={t(`${F}.montoMinimo`)} value={f.montoMinimo} onChange={set('montoMinimo')} error={errores.montoMinimo}
                  inputMode="decimal" maxLength={10} placeholder="0.00" ayuda={t(`${F}.montoMinimoAyuda`)} />
                <Campo etiqueta={t(`${F}.topePuntos`)} value={f.topePuntos} onChange={set('topePuntos')} error={errores.topePuntos}
                  inputMode="numeric" maxLength={7} ayuda={t(`${F}.topePuntosAyuda`)} />
              </div>
            </fieldset>
            <div className="fidelizacion__acciones fidelizacion__acciones--fin">
              <Boton tipo="terciario" tamano="M" to={editando ? rutaCampana(id) : RUTAS.adminFidelizacion}>{t('comun.cancelar')}</Boton>
              <Boton type="submit" tamano="M" cargando={enviando}>{t(editando ? `${F}.guardar` : `${F}.programar`)}</Boton>
            </div>
          </form>
          <aside className="fidelizacion__lateral" aria-label={t(`${TEXTOS}.previa.titulo`)}>
            <VistaPrevia base={panel.reglaBase}
              campana={regla && { ...regla, montoMinimo: numeroDe(f.montoMinimo) || 0, topePuntos: Number(f.topePuntos) || null }} />
            {f.desde && f.hasta && f.hasta >= f.desde && (
              <p className="body-s">{t(`${F}.resumenPeriodo`, { desde: fecha(f.desde), hasta: fecha(f.hasta) })}</p>
            )}
            <p className="caption">{t(`${F}.conservan`)}</p>
          </aside>
        </div>
      )}
    </LayoutAdmin>
  )
}
