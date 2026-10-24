import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarInventario } from '../../api/acopio.js'
import { marcarMermaPunto } from '../../api/merma.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Buscador from '../../components/Buscador.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoMerma from '../../components/DialogoMerma.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { fechaCorta, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import '../productor/productor.css'
import './InventarioPage.css'
import { useMovimientoPunto } from './useMovimientoPunto.jsx'

const TONO = { DISPONIBLE: 'exito', POR_VENCER: 'aviso', VENCIDO: 'error', AGOTADO: 'neutro' }
const ESTADOS = ['ACTIVOS', 'POR_VENCER', 'AGOTADOS', 'TODOS']


/**
 * A-02 · CCPIP-162 (RF-131): inventario de los puntos del administrador por producto, productor,
 * lote y estado. El servidor filtra por sus puntos (RNF-05) y calcula los totales una vez por lote;
 * lo vencido no es vendible. Desde aqui se registran entradas (CCPIP-121) y salidas (CCPIP-287).
 * "Marcar como merma" (CCPIP-76, A-04 y A-07) tambien parte del lote; las transferencias llegan con su historia.
 */
export default function InventarioPage({ path }) {
  const { t } = useTranslation()
  const [texto, setTexto] = useState('')
  const [q, setQ] = useState('')
  const [productor, setProductor] = useState('')
  const [estado, setEstado] = useState('ACTIVOS')
  const [pagina, setPagina] = useState(0)
  const [avisoVisible, setAvisoVisible] = useState(true)
  const consultar = useCallback(() => consultarInventario({ q, productor, estado, pagina }), [q, productor, estado, pagina])
  const { datos, error, recargar } = useConsulta(consultar)
  const movimiento = useMovimientoPunto(recargar)
  // CCPIP-76 (A-04, A-07): marcar como merma desde el lote
  const [merma, setMerma] = useState(null)
  const [marcada, setMarcada] = useState(null)

  function alMarcar(r) {
    setMerma(null)
    setMarcada(r)
    recargar()
  }

  function buscar(e) {
    e.preventDefault()
    setQ(texto.trim())
    setPagina(0)
  }

  return (
    <LayoutAcopio path={path} punto={datos?.ubicacion} titulo={t('acopio.nav.inventario')}>
      <div className="inventario__filtros">
        <Buscador
          id="buscar-inventario"
          etiqueta={t('acopio.inventario.buscar')}
          placeholder={t('acopio.inventario.buscar')}
          maxLength={100}
          value={texto}
          onChange={(e) => setTexto(e.target.value)}
          onBuscar={buscar}
        />
        <Campo
          tipo="selector"
          etiqueta={t('acopio.inventario.productor')}
          placeholder={t('acopio.inventario.todos')}
          value={productor}
          onChange={(e) => { setProductor(e.target.value); setPagina(0) }}
          opciones={(datos?.productores ?? []).map((p) => ({ valor: String(p.id), etiqueta: p.nombre }))}
        />
        <Campo
          tipo="selector"
          etiqueta={t('acopio.inventario.estadoLote')}
          placeholder={t('acopio.inventario.estado.ACTIVOS')}
          value={estado === 'ACTIVOS' ? '' : estado}
          onChange={(e) => { setEstado(e.target.value || 'ACTIVOS'); setPagina(0) }}
          opciones={ESTADOS.filter((e) => e !== 'ACTIVOS').map((e) => ({ valor: e, etiqueta: t(`acopio.inventario.estado.${e}`) }))}
        />
        <Boton tamano="M" onClick={() => movimiento.abrir()}>{t('movimiento.titulo')}</Boton>
      </div>

      {avisoVisible && (
        <Alerta
          tono="info"
          titulo={t('acopio.inventario.unSoloInventario')}
          descripcion={t('acopio.inventario.unSoloInventarioTexto')}
          onCerrar={() => setAvisoVisible(false)}
        />
      )}

      {movimiento.avisos}
      {marcada && (
        <Alerta
          tono="exito"
          titulo={t('merma.marcada', { cantidad: unidades(marcada.merma.cantidad), lote: marcada.merma.lote })}
          descripcion={t('merma.marcadaSaldo', { saldo: unidades(marcada.saldo) })}
          onCerrar={() => setMarcada(null)}
        />
      )}

      {error && (
        <EstadoVacio titulo={t('acopio.inventario.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}

      {!datos && !error && <Cargando />}

      {!error && datos && (
        <p className="body-s inventario__resumen">
          {t('acopio.inventario.resumen', {
            stock: unidades(datos.resumen.stock),
            vendible: unidades(datos.resumen.vendible),
            noVendible: unidades(datos.resumen.noVendible),
          })}
        </p>
      )}

      {!error && datos?.items.length === 0 && (
        <EstadoVacio titulo={t('acopio.inventario.vacio')} descripcion={t('acopio.inventario.vacioTexto')} />
      )}

      {!error && datos?.items.length > 0 && (
        <>
          <TablaInventario items={datos.items} onMovimiento={movimiento.abrir} onMerma={(l) => { setMarcada(null); setMerma(l) }} />
          <div className="inventario__pie">
            <span className="caption">
              {t('acopio.inventario.mostrando', {
                desde: datos.pagina * datos.tamano + 1,
                hasta: datos.pagina * datos.tamano + datos.items.length,
                total: datos.total,
              })}
            </span>
            <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} onCambiar={setPagina} />
          </div>
        </>
      )}

      {movimiento.formulario}
      {merma && (
        <DialogoMerma
          lote={{ id: merma.id, codigo: merma.codigo, producto: merma.producto, detalle: merma.productor, stock: merma.stock }}
          marcar={marcarMermaPunto}
          onMarcada={alMarcar}
          onCancelar={() => setMerma(null)}
        />
      )}
    </LayoutAcopio>
  )
}

function TablaInventario({ items, onMovimiento, onMerma }) {
  const { t } = useTranslation()
  const titulo = t('acopio.inventario.lista')
  return (
    <div className="tabla" role="table" aria-label={titulo}>
      <div className="tabla__fila tabla__fila--encabezado" role="row">
        <span className="tabla__celda tabla__celda--flex" role="columnheader">{t('productor.tabla.producto')}</span>
        <span className="tabla__celda inventario__col-productor" role="columnheader">{t('acopio.inventario.productor')}</span>
        <span className="tabla__celda inventario__col-lote" role="columnheader">{t('movimiento.lote')}</span>
        <span className="tabla__celda inventario__col-vence" role="columnheader">{t('acopio.inventario.col.vence')}</span>
        <span className="tabla__celda inventario__col-numero" role="columnheader">{t('acopio.inventario.col.entradas')}</span>
        <span className="tabla__celda inventario__col-numero" role="columnheader">{t('acopio.inventario.col.salidas')}</span>
        <span className="tabla__celda inventario__col-numero" role="columnheader">{t('acopio.inventario.col.stock')}</span>
        <span className="tabla__celda inventario__col-estado" role="columnheader">{t('productor.lote.tabla.estado')}</span>
        <span className="tabla__celda inventario__col-accion" role="columnheader">{t('acopio.inventario.col.accion')}</span>
      </div>
      {items.map((l) => (
        <div key={l.id} className="tabla__fila" role="row">
          <span className="tabla__celda tabla__celda--flex label-m" role="cell">{l.producto}</span>
          <span className="tabla__celda inventario__col-productor" role="cell">{l.productor}</span>
          <span className="tabla__celda inventario__col-lote" role="cell">{l.codigo}</span>
          <span className="tabla__celda inventario__col-vence" role="cell">{l.fechaVencimiento ? fechaCorta(l.fechaVencimiento) : '—'}</span>
          <span className="tabla__celda inventario__col-numero" role="cell">{unidades(l.entradas)}</span>
          <span className="tabla__celda inventario__col-numero" role="cell">{unidades(l.salidas)}</span>
          <span className="tabla__celda inventario__col-numero label-m" role="cell">{unidades(l.stock)}</span>
          <span className="tabla__celda inventario__col-estado" role="cell">
            <Badge tono={TONO[l.estado]}>{t(`acopio.inventario.estados.${l.estado}`)}</Badge>
          </span>
          <span className="tabla__celda inventario__col-accion" role="cell">
            <Boton tipo="terciario" tamano="S" onClick={() => onMovimiento(l.id)} aria-label={t('acopio.inventario.movimientoDe', { lote: l.codigo })}>
              {t('acopio.inventario.movimiento')}
            </Boton>
            {Number(l.stock) > 0 && (
              <Boton tipo="terciario" tamano="S" onClick={() => onMerma(l)} aria-label={t('merma.marcarDe', { lote: l.codigo })}>
                {t('merma.accion')}
              </Boton>
            )}
          </span>
        </div>
      ))}
    </div>
  )
}
