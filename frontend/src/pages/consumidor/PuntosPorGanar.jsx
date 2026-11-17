import { useEffect, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import iconoEstrella from '../../assets/icons/estrella.svg'
import { estimacionPuntos } from '../../api/fidelizacion.js'
import Icono from '../../components/Icono.jsx'
import Rocco from '../../components/Rocco.jsx'
import { unidades } from '../../formato.js'
import './PuntosPorGanar.css'

/**
 * CCPIP-86/88: cuantos puntos daria una compra de `monto` soles en productos (sin envio, ya con el
 * descuento), con el nivel del comprador y la campana de hoy. Nulo mientras consulta, si no gana puntos
 * o si la consulta falla: es un aliciente, no bloquea la compra.
 */
export function usePuntosPorGanar(monto) {
  const [estimacion, setEstimacion] = useState(null)
  useEffect(() => {
    if (!(monto > 0)) {
      setEstimacion(null)
      return undefined
    }
    let vigente = true
    estimacionPuntos(monto)
      .then((e) => vigente && setEstimacion(e.puntos > 0 ? e : null))
      .catch(() => vigente && setEstimacion(null))
    return () => {
      vigente = false
    }
  }, [monto])
  return estimacion
}

/** "Ganarás 40 puntos al recibir tu pedido", en una linea (pago y "Gracias por tu compra"). */
export function LineaPuntos({ puntos, className = '' }) {
  return (
    <p className={`puntos-linea${className ? ` ${className}` : ''}`}>
      <Icono src={iconoEstrella} tamano={16} />
      <span>
        <Trans i18nKey="puntosPorGanar.linea" count={puntos.puntos} values={{ puntos: unidades(puntos.puntos) }}
          components={{ strong: <strong /> }} />
      </span>
    </p>
  )
}

/** En el carrito, a la par del titulo y sobre el resumen: Rocco aparece contando cuantos puntos ganaria la compra. */
export default function RoccoPuntos({ puntos }) {
  const { t } = useTranslation()
  return (
    <section className="rocco-puntos" aria-live="polite" aria-label={t('puntosPorGanar.etiqueta')}>
      <Rocco variante="emocionado" tamano={64} className="rocco-puntos__rocco" />
      <div className="rocco-puntos__globo">
        <p className="rocco-puntos__titulo">
          <Trans i18nKey="puntosPorGanar.carrito" count={puntos.puntos} values={{ puntos: unidades(puntos.puntos) }}
            components={{ strong: <strong /> }} />
        </p>
        <p className="caption rocco-puntos__detalle">
          {puntos.campana ? t('puntosPorGanar.campana', { campana: puntos.campana }) : t('puntosPorGanar.base')}
        </p>
      </div>
    </section>
  )
}
