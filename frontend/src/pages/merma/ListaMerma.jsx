import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useConsulta } from '../../api/useConsulta.js'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import { nombreCausa } from '../../components/DialogoMerma.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import { diaMes, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './ListaMerma.css'

/**
 * La merma registrada (Figma A-04): manual (CCPIP-76) y automatica por vencimiento (CCPIP-75), la
 * mas reciente primero. `consultar(pagina)` llama al API del rol; `conUbicacion` muestra donde
 * estaba el lote (el productor tiene lotes en su almacen y en los puntos). `ir` ({ ruta, texto }) lleva a
 * donde se marca la merma, con `irTexto` que lo explica. `onDatos` recibe cada pagina.
 */
export default function ListaMerma({ consultar, conUbicacion = false, ir, irTexto, onDatos }) {
  const { t, i18n } = useTranslation()
  const [pagina, setPagina] = useState(0)
  const cargar = useCallback(() => consultar(pagina).then((d) => { onDatos?.(d); return d }), [consultar, pagina, onDatos])
  const { datos, error, recargar } = useConsulta(cargar)
  const titulo = t('merma.lista.titulo')

  return (
    <>
      <div className="merma__barra">
        <p className="body-s">{irTexto}</p>
        {ir && <Boton tipo="secundario" tamano="M" to={ir.ruta}>{ir.texto}</Boton>}
      </div>

      {error && (
        <EstadoVacio titulo={t('merma.lista.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}
      {!error && datos?.items.length === 0 && <EstadoVacio titulo={t('merma.lista.vacio')} descripcion={t('merma.lista.vacioTexto')} />}

      {!error && datos?.items.length > 0 && (
        <>
          <div className="tabla" role="table" aria-label={titulo}>
            <div className="tabla__fila tabla__fila--encabezado" role="row">
              <span className="tabla__celda merma__col-fecha" role="columnheader">{t('merma.lista.col.fecha')}</span>
              <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('merma.lista.col.producto')}</span>
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
            <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} onCambiar={setPagina} />
          </div>
        </>
      )}
    </>
  )
}
