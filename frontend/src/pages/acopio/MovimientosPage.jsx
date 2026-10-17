import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { consultarMovimientos, listarLotesPunto, registrarMovimiento } from '../../api/acopio.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Buscador from '../../components/Buscador.jsx'
import DialogoMovimiento from '../../components/DialogoMovimiento.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { fechaHora, unidades } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { MOTIVOS_PUNTO } from '../../movimientos.js'
import '../productor/productor.css'
import './MovimientosPage.css'

const PESTANAS = ['', 'ENTRADAS', 'SALIDAS', 'AJUSTES']

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
}

/**
 * A-03 · CCPIP-158 (RF-127): movimientos de existencias del punto, entre ellos el descuento que
 * genero cada compra confirmada, con su numero de pedido. Registra entradas (CCPIP-121) y salidas
 * (CCPIP-287) con el mismo formulario que A-02; recibir transferencias (CCPIP-277) llega con su historia.
 */
export default function MovimientosPage({ path }) {
  const { t } = useTranslation()
  const [tipo, setTipo] = useState('')
  const [pedido, setPedido] = useState('')
  const [buscado, setBuscado] = useState('')
  const [pagina, setPagina] = useState(0)
  const consultar = useCallback(() => consultarMovimientos({ tipo, pedido: buscado, pagina }), [tipo, buscado, pagina])
  const { datos, error, recargar } = useConsulta(consultar)
  const [dialogo, setDialogo] = useState(null)
  const [registrado, setRegistrado] = useState(null)
  const [errorLotes, setErrorLotes] = useState(null)

  /** El formulario necesita todos los lotes de sus puntos, aun sin saldo; se piden al abrirlo. */
  async function abrir() {
    setErrorLotes(null)
    try {
      setDialogo({ lotes: await listarLotesPunto(), clave: Date.now() })
    } catch (err) {
      if (err.status !== 401) setErrorLotes(err)
    }
  }

  function alRegistrar(r) {
    setDialogo(null)
    setRegistrado(r)
    recargar()
  }

  function cambiarTipo(valor) {
    setTipo(valor)
    setPagina(0)
  }

  function buscar(e) {
    e.preventDefault()
    setBuscado(pedido.trim())
    setPagina(0)
  }

  function limpiar() {
    setPedido('')
    setBuscado('')
    setPagina(0)
  }

  return (
    <LayoutAcopio path={path} punto={datos?.ubicacion} titulo={t('acopio.nav.movimientos')}>
      <div className="movimientos__filtros">
        <Pestanas opciones={PESTANAS.map((p) => ({ valor: p, etiqueta: t(`acopio.movimientos.pestana.${p || 'TODOS'}`) }))} valor={tipo} onCambiar={cambiarTipo} etiqueta={t('acopio.movimientos.tipoEtiqueta')} />
        <Buscador
          id="buscar-pedido"
          etiqueta={t('acopio.movimientos.numeroPedido')}
          placeholder={t('acopio.movimientos.buscarPedido')}
          maxLength={20}
          value={pedido}
          onChange={(e) => setPedido(e.target.value)}
          onBuscar={buscar}
        />
        <Boton tamano="M" onClick={abrir}>{t('movimiento.titulo')}</Boton>
      </div>

      {registrado && (
        <Alerta
          tono="exito"
          titulo={t(`movimiento.registrado.${registrado.movimiento.tipo}`, {
            cantidad: unidades(registrado.movimiento.cantidad), lote: registrado.movimiento.lote,
          })}
          descripcion={t('movimiento.saldo', { cantidad: unidades(registrado.saldoLote) })}
          onCerrar={() => setRegistrado(null)}
        />
      )}

      {errorLotes && (
        <Alerta tono="error" titulo={t('movimiento.errorLotes')} descripcion={mensajeError(errorLotes, 'comun.errorConexion')} onCerrar={() => setErrorLotes(null)} />
      )}

      {error && (
        <EstadoVacio titulo={t('acopio.movimientos.errorCargar')} descripcion={mensajeError(error, 'comun.errorConexion')}>
          <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('carrito.reintentar')}</Boton>
        </EstadoVacio>
      )}

      {!error && datos && buscado && (
        <Alerta
          tono="info"
          titulo={t('acopio.movimientos.delPedido', { numero: buscado.toUpperCase() })}
          descripcion={datos.total === 0 ? t('acopio.movimientos.sinMovimientosPedido') : undefined}
          onCerrar={limpiar}
        />
      )}

      {!error && datos?.items.length === 0 && !buscado && (
        <EstadoVacio titulo={t('acopio.movimientos.vacio')} descripcion={t('acopio.movimientos.vacioTexto')} />
      )}

      {!error && datos?.items.length > 0 && (
        <>
          <TablaMovimientos items={datos.items} />
          <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} onCambiar={setPagina} />
        </>
      )}

      {dialogo && (
        <DialogoMovimiento
          key={dialogo.clave}
          conProductor
          lotes={dialogo.lotes}
          motivos={MOTIVOS_PUNTO}
          registrar={registrarMovimiento}
          onRegistrado={alRegistrar}
          onCancelar={() => setDialogo(null)}
        />
      )}
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
