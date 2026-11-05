import { useTranslation } from 'react-i18next'
import iconoCheck from '../assets/icons/check.svg'
import { diaMesHora } from '../formato.js'
import Icono from './Icono.jsx'
import './SeguimientoPedido.css'

/**
 * Etapas visibles de un pedido (CW-09). EN_PREPARACION la marca el punto de acopio; si un pedido
 * antiguo paso directo a disponible, la etapa se ve hecha y sin fecha. El pedido a domicilio (CCPIP-59) sale del punto
 * listo para despacho y el distribuidor lo lleva en camino hasta entregarlo (CCPIP-318, CCPIP-319). Internacional tendra
 * las suyas.
 */
const ETAPAS = {
  RECOJO: ['CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_RECOJO', 'ENTREGADO'],
  ENVIO: ['CONFIRMADO', 'EN_PREPARACION', 'LISTO_PARA_DESPACHO', 'EN_CAMINO', 'ENTREGADO'],
}

/** Fecha en que el pedido llego por primera vez a `estado`, segun su historial. */
export function fechaDe(historial, estado) {
  return historial?.find((e) => e.estado === estado)?.registradoEn ?? null
}

/** ¿El pedido esta en una etapa que se puede seguir? Sin pagar o cancelado, no. */
export function conSeguimiento(pedido) {
  return (ETAPAS[pedido.modalidad] ?? ETAPAS.RECOJO).includes(pedido.estado)
}

/**
 * CCPIP-70 y CCPIP-340: en que etapa esta el pedido y cuando paso por cada una. Una etapa alcanzada
 * sin registro en el historial (datos antiguos) se marca hecha y sin fecha.
 */
export default function SeguimientoPedido({ pedido }) {
  const { t } = useTranslation()
  const etapas = ETAPAS[pedido.modalidad] ?? ETAPAS.RECOJO
  const actual = etapas.indexOf(pedido.estado)
  return (
    <ol className="seguimiento" aria-label={t('misPedidos.seguimiento', { numero: pedido.numero })}>
      {etapas.map((estado, i) => {
        const tipo = i < actual || (i === actual && i === etapas.length - 1) ? 'hecho' : i === actual ? 'actual' : 'pendiente'
        const fecha = fechaDe(pedido.historial, estado)
        return (
          <li key={estado} className={`seguimiento__etapa seguimiento__etapa--${tipo}`} aria-current={i === actual ? 'step' : undefined}>
            <span className="seguimiento__marca" aria-hidden="true">
              {/* La etapa actual ya se alcanzo: lleva su check, y la linea a la siguiente queda por recorrer */}
              {tipo !== 'pendiente' && <Icono src={iconoCheck} tamano={14} />}
            </span>
            <span className="seguimiento__nombre">{t(`misPedidos.etapa.${estado}`)}</span>
            <span className="seguimiento__fecha">{i <= actual && fecha ? diaMesHora(fecha) : '—'}</span>
          </li>
        )
      })}
    </ol>
  )
}
