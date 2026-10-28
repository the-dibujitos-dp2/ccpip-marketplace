import { useTranslation } from 'react-i18next'
import { coincide } from '../formato.js'
import i18n from '../i18n/index.js'
import Buscador from './Buscador.jsx'
import Campo from './Campo.jsx'
import LimpiarFiltros from './LimpiarFiltros.jsx'

export const SIN_FILTROS = { texto: '', producto: '', desde: '', hasta: '' }

/** Valor del filtro de producto para las denuncias contra la comunidad, que no tienen producto. */
const COMUNIDAD = 'comunidad'

export function hayFiltros(filtros) {
  return Object.values(filtros).some((v) => v.trim())
}

/**
 * Si la denuncia pasa los filtros. `dia` es el dia en que se registro (AAAA-MM-DD); se busca en el
 * producto, el motivo, la descripcion y los `textos` que cada pantalla agrega.
 */
export function pasaFiltros(d, f, dia, ...textos) {
  const producto = d.producto ? String(d.producto.id) : COMUNIDAD
  return (!f.producto || f.producto === producto) && (!f.desde || dia >= f.desde) && (!f.hasta || dia <= f.hasta)
    && coincide(f.texto, d.producto?.nombre, i18n.t(`denuncia.motivos.${d.motivo}`), d.descripcion, ...textos)
}

/**
 * CCPIP-432: busqueda y filtros de una lista de denuncias, los mismos en el expediente de la administracion y
 * en las denuncias que recibe el productor. Se filtra en el navegador: la lista ya llega completa. El
 * selector de producto ofrece solo los productos de esas denuncias y la comunidad (`comunidad` es como se
 * nombra); el motivo se busca escribiendolo.
 */
export default function FiltrosDenuncias({ id, denuncias, filtros, onCambiar, buscar, comunidad }) {
  const { t } = useTranslation()
  const cambiar = (campo) => (e) => onCambiar({ ...filtros, [campo]: e.target.value })
  const productos = [...new Map(denuncias.filter((d) => d.producto).map((d) => [d.producto.id, d.producto.nombre])).entries()]
    .sort(([, a], [, b]) => a.localeCompare(b))
    .map(([valor, etiqueta]) => ({ valor: String(valor), etiqueta }))
  if (denuncias.some((d) => !d.producto)) productos.push({ valor: COMUNIDAD, etiqueta: comunidad })

  return (
    <div className="barra-filtros">
      <Buscador id={id} etiqueta={buscar} placeholder={buscar} maxLength={100} value={filtros.texto}
        onChange={cambiar('texto')} onBuscar={(e) => e.preventDefault()} />
      <Campo tipo="selector" etiqueta={t('denuncia.filtros.producto')} placeholder={t('denuncia.filtros.todos')}
        value={filtros.producto} onChange={cambiar('producto')} opciones={productos} />
      <Campo etiqueta={t('denuncia.filtros.desde')} type="date" value={filtros.desde} max={filtros.hasta || undefined} onChange={cambiar('desde')} />
      <Campo etiqueta={t('denuncia.filtros.hasta')} type="date" value={filtros.hasta} min={filtros.desde || undefined} onChange={cambiar('hasta')} />
      <LimpiarFiltros activos={hayFiltros(filtros)} onLimpiar={() => onCambiar(SIN_FILTROS)} />
    </div>
  )
}
