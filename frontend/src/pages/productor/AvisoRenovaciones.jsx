import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { renovacionesDeProducto } from '../../api/autorizaciones.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import { fechaCorta } from '../../formato.js'

/**
 * CCPIP-321: en las evidencias del producto (PW-14), lo que la Camara pidio renovar, con su plazo y su
 * motivo. Es el aviso en la plataforma que acompana al correo. Sin pendientes no muestra nada.
 */
export default function AvisoRenovaciones({ productoId }) {
  const { t } = useTranslation()
  const consultar = useCallback(() => renovacionesDeProducto(productoId), [productoId])
  const { datos } = useConsulta(consultar)
  const pendientes = (datos ?? []).filter((r) => r.estado === 'PENDIENTE')
  if (pendientes.length === 0) return null
  const detalle = pendientes
    .map((r) => t('evidencias.renovacion.detalle', { certificado: r.certificado.titulo, plazo: fechaCorta(r.plazo), motivo: r.motivo }))
    .join(' ')
  return (
    <Alerta
      tono="aviso"
      titulo={t('evidencias.renovacion.titulo', { count: pendientes.length })}
      descripcion={`${detalle} ${t('evidencias.renovacion.venta')}`}
    />
  )
}
