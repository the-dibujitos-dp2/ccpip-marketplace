import { useTranslation } from 'react-i18next'
import Boton from './Boton.jsx'
import EstadoVacio from './EstadoVacio.jsx'

/**
 * CCPIP-417: devuelve una lista a como estaba antes de buscar y filtrar. Solo aparece con algun
 * filtro puesto (`activos`), para que no ocupe espacio cuando no hay nada que limpiar.
 */
export default function LimpiarFiltros({ activos, onLimpiar }) {
  const { t } = useTranslation()
  if (!activos) return null
  return (
    <Boton tipo="terciario" tamano="M" onClick={onLimpiar}>
      {t('comun.limpiarFiltros')}
    </Boton>
  )
}

/** CCPIP-417: una lista que tiene elementos, pero ninguno coincide con la busqueda y los filtros. */
export function SinCoincidencias() {
  const { t } = useTranslation()
  return <EstadoVacio titulo={t('comun.sinCoincidencias')} descripcion={t('comun.sinCoincidenciasTexto')} />
}
