import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { finalizarCampana } from '../../../api/fidelizacion.js'
import Badge from '../../../components/Badge.jsx'
import Dialogo from '../../../components/Dialogo.jsx'
import { puntosDe, puntosEnCampana } from '../../../fidelizacion.js'
import { fecha, precio, unidades } from '../../../formato.js'
import { mensajeError } from '../../../i18n/errores.js'
import { RUTAS } from '../../../router.js'

/** Prefijo de los textos de la seccion Fidelizacion (CCPIP-86). */
export const TEXTOS = 'admin.fidelizacion'

const TONO_ESTADO = { ACTIVA: 'exito', PROGRAMADA: 'info', FINALIZADA: 'neutro', CANCELADA: 'neutro' }

/** La pagina de una campana: sus metricas, o lo que falta para que empiece. */
export const rutaCampana = (id) => `${RUTAS.adminFidelizacion}/campanas/${id}`

/** Pedidos de ejemplo de la vista previa, en soles de productos. */
const EJEMPLOS = [50, 100, 250]

export function EstadoCampana({ estado }) {
  const { t } = useTranslation()
  return <Badge tono={TONO_ESTADO[estado]}>{t(`${TEXTOS}.estado.${estado}`)}</Badge>
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
