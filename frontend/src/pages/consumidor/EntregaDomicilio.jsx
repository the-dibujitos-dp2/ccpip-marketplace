import { useTranslation } from 'react-i18next'
import Campo from '../../components/Campo.jsx'

/** "Wanchaq, Cusco, Cusco": el distrito con su provincia y departamento, para elegirlo en una lista. */
export const etiquetaDistrito = ({ distrito }) => `${distrito.distrito}, ${distrito.provincia}, ${distrito.departamento}`

/**
 * CW-05, entrega a domicilio (CCPIP-59): el distrito de destino, entre los que algun punto con existencias
 * para todo el carrito cubre, y la direccion. El punto que lo entrega se elige aparte, con sus tarjetas.
 */
export default function EntregaDomicilio({ distritos, form, errores, alEscribir }) {
  const { t } = useTranslation()
  return (
    <>
      <Campo
        tipo="selector"
        etiqueta={t('compra.domicilio.distrito')}
        opciones={distritos.map((d) => ({ valor: d.distrito.ubigeo, etiqueta: etiquetaDistrito(d) }))}
        placeholder={t('compra.domicilio.eligeDistrito')}
        value={form.ubigeo}
        onChange={alEscribir('ubigeo')}
        error={errores.ubigeo}
      />
      <Campo
        etiqueta={t('compra.domicilio.direccion')}
        ayuda={t('compra.domicilio.direccionAyuda')}
        value={form.direccion}
        onChange={alEscribir('direccion')}
        error={errores.direccion}
        maxLength={255}
        autoComplete="street-address"
      />
      <Campo
        etiqueta={t('compra.domicilio.referencia')}
        value={form.referencia}
        onChange={alEscribir('referencia')}
        error={errores.referencia}
        maxLength={255}
      />
    </>
  )
}
