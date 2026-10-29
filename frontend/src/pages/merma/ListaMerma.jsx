import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useConsulta } from '../../api/useConsulta.js'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import { nombreCausa } from '../../components/DialogoMerma.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LimpiarFiltros from '../../components/LimpiarFiltros.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import { diaMes, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './ListaMerma.css'

const SIN_FILTROS = { desde: '', hasta: '', lote: '', producto: '', ubicacion: '', productor: '' }

/**
 * La merma registrada (Figma A-04): manual (CCPIP-76) y automatica por vencimiento (CCPIP-75), la
 * mas reciente primero. `consultar(pagina)` llama al API del rol; `conUbicacion` muestra donde
 * estaba el lote (el productor tiene lotes en su almacen y en los puntos). `ir` ({ ruta, texto }) lleva a
 * donde se marca la merma, con `irTexto` que lo explica. `onDatos` recibe cada pagina.
 */
export default function ListaMerma({ consultar, exportar, conUbicacion = false, conProductor = false, ir, irTexto, onDatos }) {
  const { t, i18n } = useTranslation()
  const [pagina, setPagina] = useState(0)
  const [escritos, setEscritos] = useState(SIN_FILTROS)
  const [filtros, setFiltros] = useState(escritos)
  const [filtrosAplicados, setFiltrosAplicados] = useState(false)
  const cargar = useCallback(() => consultar(pagina, filtros).then((d) => { onDatos?.(d); return d }), [consultar, pagina, filtros, onDatos])
  const { datos, error, recargar } = useConsulta(cargar)
  const titulo = t('merma.lista.titulo')
  const cambiar = (campo) => (e) => setEscritos((actual) => ({ ...actual, [campo]: e.target.value }))
  const aplicar = () => { setPagina(0); setFiltros(escritos); setFiltrosAplicados(true) }
  // CCPIP-417: vuelve a toda la merma, sin filtros
  const limpiar = () => { setPagina(0); setEscritos(SIN_FILTROS); setFiltros(SIN_FILTROS); setFiltrosAplicados(false) }

  return (
    <>
      <div className="merma__barra">
        <p className="body-s">{irTexto}</p>
        {ir && <Boton tipo="secundario" tamano="M" to={ir.ruta}>{ir.texto}</Boton>}
      </div>
      <section className="merma__filtros" aria-label={t('merma.lista.filtros.titulo')}>
        <Campo etiqueta={t('merma.lista.filtros.desde')} type="date" value={escritos.desde} max={escritos.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('merma.lista.filtros.hasta')} type="date" value={escritos.hasta} min={escritos.desde || undefined} onChange={cambiar('hasta')} />
        <Campo etiqueta={t('merma.lista.filtros.lote')} value={escritos.lote} onChange={cambiar('lote')} />
        <Campo etiqueta={t('merma.lista.filtros.producto')} value={escritos.producto} onChange={cambiar('producto')} />
        <Campo etiqueta={t('merma.lista.filtros.ubicacion')} value={escritos.ubicacion} onChange={cambiar('ubicacion')} />
        {/* CCPIP-417: el punto filtra tambien por productor; el productor solo ve su propia merma */}
        {conProductor && <Campo etiqueta={t('merma.lista.filtros.productor')} value={escritos.productor} onChange={cambiar('productor')} />}
        <Boton tamano="M" onClick={aplicar}>{t('merma.lista.filtros.aplicar')}</Boton>
        <LimpiarFiltros activos={filtrosAplicados || Object.values(escritos).some(Boolean)} onLimpiar={limpiar} />
        {exportar && ['pdf', 'xlsx'].map((formato) => (
          <a key={formato} className="boton boton--secundario boton--M" href={exportar(formato, filtros)} download>
            {t(`merma.lista.filtros.exportar.${formato}`)}
          </a>
        ))}
      </section>

      {error && (
        <EstadoVacio titulo={t('merma.lista.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}
      {!datos && !error && <Cargando />}
      {!error && datos?.items.length === 0 && <EstadoVacio titulo={t('merma.lista.vacio')} descripcion={t(filtrosAplicados ? 'merma.lista.filtros.vacio' : 'merma.lista.vacioTexto')} />}

      {!error && datos?.items.length > 0 && (
        <>
          <div className="tabla" role="table" aria-label={titulo}>
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda merma__col-fecha" role="columnheader">{t('merma.lista.col.fecha')}</span>
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('merma.lista.col.producto')}</span>
              {conProductor && <span className="tabla__celda merma__col-productor" role="columnheader">{t('merma.lista.col.productor')}</span>}
              {conUbicacion && <span className="tabla__celda merma__col-ubicacion" role="columnheader">{t('merma.lista.col.ubicacion')}</span>}
              <span className="tabla__celda merma__col-causa" role="columnheader">{t('merma.lista.col.causa')}</span>
              <span className="tabla__celda merma__col-cantidad" role="columnheader">{t('merma.lista.col.cantidad')}</span>
              <span className="tabla__celda merma__col-registro" role="columnheader">{t('merma.lista.col.registro')}</span>
              <span className="tabla__celda merma__col-estado" role="columnheader">{t('merma.lista.col.estado')}</span>
            </div>
            {datos.items.map((m) => (
              <div key={m.id} className="tabla__fila" role="row">
                <span className="tabla__celda merma__col-fecha label-m" role="cell">{diaMes(m.registradoEn)}</span>
                <span className="tabla__celda tabla__celda--flex" role="cell">{`${m.producto} · ${m.lote}`}</span>
                {conProductor && <span className="tabla__celda merma__col-productor" role="cell">{m.productor ?? '—'}</span>}
                {conUbicacion && <span className="tabla__celda merma__col-ubicacion" role="cell">{m.ubicacion}</span>}
                <span className="tabla__celda merma__col-causa" role="cell">
                  {m.automatica ? t('merma.lista.automaticaCausa', { causa: nombreCausa(m, i18n) }) : nombreCausa(m, i18n)}
                  {m.detalle && <span className="caption merma__detalle">{m.detalle}</span>}
                </span>
                <span className="tabla__celda merma__col-cantidad" role="cell">{`−${unidades(m.cantidad)}`}</span>
                <span className="tabla__celda merma__col-registro caption" role="cell">
                  {m.automatica ? t('merma.lista.sistema') : (m.registradoPor ?? (conUbicacion ? t('merma.lista.elPunto') : '—'))}
                </span>
                <span className="tabla__celda merma__col-estado" role="cell">
                  <Badge tono={m.automatica ? 'info' : 'error'}>{t(m.automatica ? 'merma.lista.automatica' : 'merma.lista.confirmada')}</Badge>
                </span>
              </div>
            ))}
          </div>
          <div className="merma__pie">
            <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} total={datos.total} onCambiar={setPagina} />
          </div>
        </>
      )}
    </>
  )
}
