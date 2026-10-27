import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarMovimientos } from '../../api/acopio.js'
import { useConsulta } from '../../api/useConsulta.js'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Buscador from '../../components/Buscador.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LimpiarFiltros, { SinCoincidencias } from '../../components/LimpiarFiltros.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { fechaHora, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { useDiferido } from '../../useDiferido.js'
import '../productor/productor.css'
import './MovimientosPage.css'
import { useMovimientoPunto } from './useMovimientoPunto.jsx'

const PESTANAS = ['', 'ENTRADAS', 'SALIDAS', 'AJUSTES']

const SIN_FILTROS = { texto: '', desde: '', hasta: '', cantidadMin: '', cantidadMax: '' }

/** Como se ve cada tipo de movimiento en A-03. El signo de la cantidad lo da el tipo, no el numero. */
const TIPOS = {
  ENTRADA: { tono: 'exito', signo: '+' },
  TRANSFERENCIA_ENTRADA: { tono: 'exito', signo: '+' },
  // Una venta es una salida; la fila muestra su numero de pedido
  SALIDA_VENTA: { tono: 'info', signo: '−' },
  // CCPIP-287: salida manual, con su motivo
  SALIDA: { tono: 'info', signo: '−' },
  TRANSFERENCIA_SALIDA: { tono: 'info', signo: '−' },
  AJUSTE: { tono: 'neutro', signo: '' },
  // CCPIP-76 y CCPIP-75: merma marcada o automatica por vencimiento
  MERMA: { tono: 'error', signo: '−' },
}

/**
 * A-03 · CCPIP-158 (RF-127): movimientos de existencias del punto, entre ellos el descuento que
 * genero cada compra confirmada, con su numero de pedido. Registra entradas (CCPIP-121) y salidas
 * (CCPIP-287) con el mismo formulario que A-02; recibir transferencias (CCPIP-277) llega con su historia.
 * CCPIP-417: busca mientras se escribe (producto, lote o pedido) y filtra por fecha y por cantidad.
 */
export default function MovimientosPage({ path }) {
  const { t } = useTranslation()
  const [tipo, setTipo] = useState('')
  const [filtros, setFiltros] = useState(SIN_FILTROS)
  const [pagina, setPagina] = useState(0)
  // Se consulta cuando se deja de escribir, no con cada tecla
  const aplicados = useDiferido(filtros)
  const consultar = useCallback(
    () => consultarMovimientos({ tipo, q: aplicados.texto, desde: aplicados.desde, hasta: aplicados.hasta,
      cantidadMin: aplicados.cantidadMin, cantidadMax: aplicados.cantidadMax, pagina }),
    [tipo, aplicados, pagina],
  )
  const { datos, error, recargar } = useConsulta(consultar)
  const movimiento = useMovimientoPunto(recargar)
  const buscando = Object.values(filtros).some((v) => v.trim())

  function cambiarTipo(valor) {
    setTipo(valor)
    setPagina(0)
  }

  const cambiar = (campo) => (e) => {
    setFiltros((actual) => ({ ...actual, [campo]: e.target.value }))
    setPagina(0)
  }

  function limpiar() {
    setFiltros(SIN_FILTROS)
    setTipo('')
    setPagina(0)
  }

  return (
    <LayoutAcopio path={path} punto={datos?.ubicacion} titulo={t('acopio.nav.movimientos')}>
      <div className="movimientos__filtros">
        <Pestanas opciones={PESTANAS.map((p) => ({ valor: p, etiqueta: t(`acopio.movimientos.pestana.${p || 'TODOS'}`) }))} valor={tipo} onCambiar={cambiarTipo} etiqueta={t('acopio.movimientos.tipoEtiqueta')} />
        <Boton tamano="M" onClick={() => movimiento.abrir()}>{t('movimiento.titulo')}</Boton>
      </div>
      <div className="barra-filtros">
        <Buscador
          id="buscar-movimiento"
          etiqueta={t('acopio.movimientos.buscar')}
          placeholder={t('acopio.movimientos.buscar')}
          maxLength={100}
          value={filtros.texto}
          onChange={cambiar('texto')}
          onBuscar={(e) => e.preventDefault()}
        />
        <Campo etiqueta={t('acopio.movimientos.desde')} type="date" value={filtros.desde} max={filtros.hasta || undefined} onChange={cambiar('desde')} />
        <Campo etiqueta={t('acopio.movimientos.hasta')} type="date" value={filtros.hasta} min={filtros.desde || undefined} onChange={cambiar('hasta')} />
        <Campo etiqueta={t('acopio.movimientos.cantidadMin')} type="number" min="0" step="any" inputMode="decimal" value={filtros.cantidadMin} onChange={cambiar('cantidadMin')} />
        <Campo etiqueta={t('acopio.movimientos.cantidadMax')} type="number" min={filtros.cantidadMin || '0'} step="any" inputMode="decimal" value={filtros.cantidadMax} onChange={cambiar('cantidadMax')} />
        <LimpiarFiltros activos={buscando || Boolean(tipo)} onLimpiar={limpiar} />
      </div>
      <p className="caption">{t('acopio.movimientos.unDia')}</p>

      {movimiento.avisos}

      {error && (
        <EstadoVacio titulo={t('acopio.movimientos.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}

      {!datos && !error && <Cargando />}

      {!error && datos?.items.length === 0 && (buscando ? <SinCoincidencias /> : (
        <EstadoVacio titulo={t('acopio.movimientos.vacio')} descripcion={t('acopio.movimientos.vacioTexto')} />
      ))}

      {!error && datos?.items.length > 0 && (
        <>
          <TablaMovimientos items={datos.items} />
          <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} onCambiar={setPagina} />
        </>
      )}

      {movimiento.formulario}
    </LayoutAcopio>
  )
}

function TablaMovimientos({ items }) {
  const { t } = useTranslation()
  return (
    <div className="tabla">
      <div className="tabla__fila tabla__fila--encabezado" aria-hidden="true">
        <span className="tabla__celda movimientos__col-fecha">{t('acopio.movimientos.col.fecha')}</span>
        <span className="tabla__celda movimientos__col-tipo">{t('acopio.movimientos.col.tipo')}</span>
        <span className="tabla__celda tabla__celda--flex">{t('acopio.movimientos.col.producto')}</span>
        <span className="tabla__celda movimientos__col-cantidad">{t('selectorCantidad.etiqueta')}</span>
        <span className="tabla__celda movimientos__col-registro">{t('acopio.movimientos.col.registro')}</span>
      </div>
      <ul className="movimientos__lista" aria-label={t('acopio.movimientos.lista')}>
        {items.map((m) => {
          const tipo = TIPOS[m.tipo] ?? { tono: 'neutro', signo: '' }
          return (
            <li key={m.id} className="tabla__fila">
              <span className="tabla__celda movimientos__col-fecha label-m">{fechaHora(m.registradoEn)}</span>
              <span className="tabla__celda movimientos__col-tipo">
                <Badge tono={tipo.tono}>{t(`acopio.movimientos.tipo.${m.tipo}`, { defaultValue: m.tipo })}</Badge>
              </span>
              <span className="tabla__celda tabla__celda--flex">
                {[m.producto, m.lote, m.pedido, m.motivo && t(`movimiento.motivos.${m.motivo}`)].filter(Boolean).join(' · ')}
              </span>
              <span className="tabla__celda movimientos__col-cantidad">{`${tipo.signo}${unidades(m.cantidad)}`}</span>
              <span className="tabla__celda movimientos__col-registro">
                {m.registradoPor ?? (m.tipo === 'SALIDA_VENTA' ? t('acopio.movimientos.compraEnLinea') : '—')}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
