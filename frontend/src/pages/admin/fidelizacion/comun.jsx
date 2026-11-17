import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { finalizarCampana } from '../../../api/fidelizacion.js'
import Badge from '../../../components/Badge.jsx'
import Campo from '../../../components/Campo.jsx'
import Enlace from '../../../components/Enlace.jsx'
import Dialogo from '../../../components/Dialogo.jsx'
import Pestanas from '../../../components/Pestanas.jsx'
import '../../../components/Pestanas.css'
import { puntosDe, puntosEnCampana } from '../../../fidelizacion.js'
import { fecha, precio, unidades } from '../../../formato.js'
import { erroresPorCampo, mensajeError } from '../../../i18n/errores.js'
import { RUTAS } from '../../../router.js'

/** Prefijo de los textos de la seccion Fidelizacion (CCPIP-86). */
export const TEXTOS = 'admin.fidelizacion'

// Campanas (femenino) y cupones (masculino) comparten los mismos tonos
const TONO_ESTADO = {
  ACTIVA: 'exito', ACTIVO: 'exito', PROGRAMADA: 'info', PROGRAMADO: 'info', FINALIZADA: 'neutro', FINALIZADO: 'neutro', CANCELADA: 'neutro',
}

/** La pagina de una campana: sus metricas, o lo que falta para que empiece. */
export const rutaCampana = (id) => `${RUTAS.adminFidelizacion}/campanas/${id}`

/** Pedidos de ejemplo de la vista previa, en soles de productos. */
const EJEMPLOS = [50, 100, 250]

/** El estado de una campana o de un cupon, con su texto en `{textos}.{estado}`. */
export function Estado({ estado, textos = `${TEXTOS}.estado` }) {
  const { t } = useTranslation()
  return <Badge tono={TONO_ESTADO[estado]}>{t(`${textos}.${estado}`)}</Badge>
}

export const EstadoCampana = ({ estado }) => <Estado estado={estado} />

/** Filtros por estado: `{ filtro: [estados] }`; uno sin estados deja pasar todo. */
export const FILTROS_CAMPANAS = { TODAS: [], ACTIVA: ['ACTIVA'], PROGRAMADA: ['PROGRAMADA'], FINALIZADA: ['FINALIZADA', 'CANCELADA'] }
export const FILTROS_CUPONES = { TODOS: [], ACTIVO: ['ACTIVO'], PROGRAMADO: ['PROGRAMADO'], FINALIZADO: ['FINALIZADO'] }
export const enFiltro = (filtros, filtro) => (item) => !filtros[filtro].length || filtros[filtro].includes(item.estado)

/** Pestanas que filtran una lista por estado, cada una con su conteo (`{textos}.{filtro}` con `count`). */
export function PestanasEstado({ etiqueta, filtros, items, valor, onCambiar, textos }) {
  const { t } = useTranslation()
  return (
    <Pestanas etiqueta={etiqueta} valor={valor} onCambiar={onCambiar}
      opciones={Object.keys(filtros).map((f) => ({ valor: f, etiqueta: t(`${textos}.${f}`, { count: items.filter(enFiltro(filtros, f)).length }) }))} />
  )
}

/** "10 puntos por cada S/ 1.00" */
export const textoRegla = (t, { puntos, cadaSoles }) => t(`${TEXTOS}.regla`, { count: Number(puntos), monto: precio(cadaSoles) })

/** "Del 16 nov 2026 al 23 nov 2026"; si se finalizo antes, hasta ese dia. */
export const textoPeriodo = (t, { desde, hasta, finEfectivo }) =>
  t(`${TEXTOS}.periodo`, { desde: fecha(desde), hasta: fecha(finEfectivo ?? hasta) })

/** El pedido minimo y el tope de una campana, en una linea. */
export function textoCondiciones(t, { montoMinimo, topePuntos }) {
  const minimo = Number(montoMinimo) > 0 ? t(`${TEXTOS}.minimo`, { monto: precio(montoMinimo) }) : t(`${TEXTOS}.sinMinimo`)
  const tope = topePuntos ? t(`${TEXTOS}.tope`, { count: Number(topePuntos), puntos: unidades(topePuntos) }) : t(`${TEXTOS}.sinTope`)
  return `${minimo} · ${tope}`
}

/**
 * Lo que ganarian pedidos de ejemplo con la regla base y con la campana (debajo de su minimo, la base), para que
 * el sub administrador vea el efecto de la regla antes de programarla.
 */
export function VistaPrevia({ campana, base, titulo }) {
  const { t } = useTranslation()
  const valida = campana && Number(campana.puntos) > 0 && Number(campana.cadaSoles) > 0
  return (
    <section className="fidelizacion__previa" aria-labelledby="fidelizacion-previa">
      <h2 id="fidelizacion-previa" className="heading-h4">{titulo ?? t(`${TEXTOS}.previa.titulo`)}</h2>
      <table className="fidelizacion__previa-tabla">
        <thead>
          <tr>
            <th scope="col">{t(`${TEXTOS}.previa.pedido`)}</th>
            <th scope="col">{t(`${TEXTOS}.previa.base`)}</th>
            <th scope="col">{t(`${TEXTOS}.previa.campana`)}</th>
          </tr>
        </thead>
        <tbody>
          {EJEMPLOS.map((monto) => (
            <tr key={monto}>
              <th scope="row">{precio(monto)}</th>
              <td>{unidades(puntosDe(monto, base))}</td>
              <td className="fidelizacion__previa-campana">{valida ? unidades(puntosEnCampana(monto, campana, base)) : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="caption">{t(`${TEXTOS}.previa.nota`)}</p>
    </section>
  )
}

/**
 * Confirma finalizar una campana activa (rige hasta ahora) o cancelar una programada. `onListo` recibe la campana
 * como quedo; `onError`, el mensaje para mostrar.
 */
export function DialogoFinalizar({ campana, onListo, onError, onCerrar }) {
  const { t } = useTranslation()
  const [ocupado, setOcupado] = useState(false)
  const accion = campana?.estado === 'PROGRAMADA' ? 'cancelar' : 'finalizar'
  async function confirmar() {
    setOcupado(true)
    try {
      onListo(await finalizarCampana(campana.id))
    } catch (error) {
      onError(mensajeError(error))
    } finally {
      setOcupado(false)
    }
  }
  return (
    <Dialogo
      abierto={Boolean(campana)}
      titulo={t(`${TEXTOS}.${accion}.titulo`)}
      confirmar={t(`${TEXTOS}.${accion}.confirmar`)}
      tipoConfirmar="destructivo"
      onConfirmar={confirmar}
      onCancelar={onCerrar}
      ocupado={ocupado}
    >
      {campana && <p>{t(`${TEXTOS}.${accion}.texto`, { nombre: campana.nombre })}</p>}
    </Dialogo>
  )
}

/** Las dos partes de Fidelizacion: las campanas y los beneficios (niveles y cupones, CCPIP-88/89). */
export function SeccionesFidelizacion({ activa }) {
  const { t } = useTranslation()
  const secciones = [['campanas', RUTAS.adminFidelizacion], ['beneficios', RUTAS.adminFidelizacionBeneficios]]
  return (
    <nav className="pestanas" aria-label={t(`${TEXTOS}.secciones.titulo`)}>
      {secciones.map(([clave, ruta]) => (
        <Enlace key={clave} to={ruta} className={`pestanas__item${clave === activa ? ' pestanas__item--activa' : ''}`}
          aria-current={clave === activa ? 'page' : undefined}>
          {t(`${TEXTOS}.secciones.${clave}`)}
        </Enlace>
      ))}
    </nav>
  )
}

const MONTO = /^\d+(\.\d{1,2})?$/
const numero = (texto) => String(texto ?? '').trim().replace(',', '.')


/** Los campos de un descuento vacios, o los de uno existente para editarlo. */
export const beneficioVacio = (b) => ({
  tipo: b?.tipo ?? 'MONTO', valor: b?.valor != null ? String(Number(b.valor)) : '',
  montoMinimo: Number(b?.montoMinimo) > 0 ? String(Number(b.montoMinimo)) : '', topeDescuento: b?.topeDescuento ? String(Number(b.topeDescuento)) : '',
})

/** Errores de los campos de un descuento, con las mismas reglas que el servidor. */
export function validarBeneficio(t, f) {
  const V = `${TEXTOS}.beneficio.validacion`
  const e = {}
  const valor = numero(f.valor)
  if (!MONTO.test(valor) || Number(valor) <= 0) e.valor = t(`${V}.valor`)
  else if (f.tipo === 'PORCENTAJE' && Number(valor) > 90) e.valor = t(`${V}.porcentaje`)
  if (numero(f.montoMinimo) && !MONTO.test(numero(f.montoMinimo))) e.montoMinimo = t(`${V}.monto`)
  if (f.tipo === 'PORCENTAJE' && numero(f.topeDescuento) && (!MONTO.test(numero(f.topeDescuento)) || Number(numero(f.topeDescuento)) <= 0)) {
    e.topeDescuento = t(`${V}.monto`)
  }
  return e
}

/** El descuento como lo espera el servidor. El tope solo va con los porcentajes. */
export const cuerpoBeneficio = (f) => ({
  tipo: f.tipo, valor: Number(numero(f.valor)),
  montoMinimo: numero(f.montoMinimo) ? Number(numero(f.montoMinimo)) : null,
  topeDescuento: f.tipo === 'PORCENTAJE' && numero(f.topeDescuento) ? Number(numero(f.topeDescuento)) : null,
})

/** Los campos de un descuento: monto fijo o porcentaje (con tope), desde un pedido minimo. */
export function CamposBeneficio({ f, set, errores }) {
  const { t } = useTranslation()
  const B = `${TEXTOS}.beneficio`
  return (
    <>
      <div className="fidelizacion__fila">
        <Campo etiqueta={t(`${B}.tipo`)} tipo="selector" placeholder={false} value={f.tipo} onChange={set('tipo')}
          opciones={['MONTO', 'PORCENTAJE'].map((v) => ({ valor: v, etiqueta: t(`${B}.tipos.${v}`) }))} />
        <Campo etiqueta={t(f.tipo === 'PORCENTAJE' ? `${B}.valorPorcentaje` : `${B}.valorMonto`)} value={f.valor} onChange={set('valor')}
          error={errores.valor} inputMode="decimal" maxLength={8} />
      </div>
      <div className="fidelizacion__fila">
        <Campo etiqueta={t(`${B}.montoMinimo`)} value={f.montoMinimo} onChange={set('montoMinimo')} error={errores.montoMinimo}
          inputMode="decimal" maxLength={10} placeholder="0.00" />
        {f.tipo === 'PORCENTAJE' && (
          <Campo etiqueta={t(`${B}.topeDescuento`)} value={f.topeDescuento} onChange={set('topeDescuento')}
            error={errores.topeDescuento} inputMode="decimal" maxLength={10} ayuda={t(`${B}.topeAyuda`)} />
        )}
      </div>
    </>
  )
}

/**
 * Estado de un formulario de dialogo: los campos, sus errores, si se esta enviando y el error general. `set(campo)` es
 * el manejador de un campo; `enviar(validar, llamar)` valida, llama al servidor y reparte sus errores por campo.
 */
export function useFormulario(inicial, onListo) {
  const [f, setF] = useState(inicial)
  const [errores, setErrores] = useState({})
  const [enviando, setEnviando] = useState(false)
  const [error, setError] = useState(null)
  const set = (campo) => (e) => {
    setF((actual) => ({ ...actual, [campo]: e.target.value }))
    setErrores((actual) => ({ ...actual, [campo]: undefined }))
  }
  async function enviar(evento, validar, llamar) {
    evento?.preventDefault?.()
    setError(null)
    const e = validar(f)
    setErrores(e)
    if (Object.keys(e).length) return
    setEnviando(true)
    try {
      onListo(await llamar(f))
    } catch (err) {
      // Los del descuento llegan anidados ("beneficio.valor") y van a su campo
      const campos = err.body?.campos
        ? Object.fromEntries(Object.entries(erroresPorCampo(err)).map(([k, v]) => [k.replace(/^beneficio\./, ''), v])) : {}
      if (Object.keys(campos).length) setErrores(campos)
      else setError(err)
    } finally {
      setEnviando(false)
    }
  }
  return { f, set, errores, enviando, error, enviar }
}
