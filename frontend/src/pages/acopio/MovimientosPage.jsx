import { useCallback, useEffect, useState } from 'react'
import { consultarMovimientos } from '../../api/acopio.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Buscador from '../../components/Buscador.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import Pestanas from '../../components/Pestanas.jsx'
import LayoutAcopio from '../../components/layout/LayoutAcopio.jsx'
import { fechaHora } from '../../formato.js'
import '../productor/productor.css'
import './MovimientosPage.css'

const PESTANAS = [
  { valor: '', etiqueta: 'Todos' },
  { valor: 'ENTRADAS', etiqueta: 'Entradas' },
  { valor: 'SALIDAS', etiqueta: 'Salidas' },
  { valor: 'AJUSTES', etiqueta: 'Ajustes' },
]

/** Como se ve cada tipo de movimiento en A-03. El signo de la cantidad lo da el tipo, no el numero. */
const TIPOS = {
  ENTRADA: { tono: 'exito', texto: 'Entrada', signo: '+' },
  TRANSFERENCIA_ENTRADA: { tono: 'exito', texto: 'Transferencia', signo: '+' },
  // Una venta es una salida; la fila muestra su numero de pedido
  SALIDA_VENTA: { tono: 'info', texto: 'Salida', signo: '−' },
  TRANSFERENCIA_SALIDA: { tono: 'info', texto: 'Transferencia', signo: '−' },
  AJUSTE: { tono: 'neutro', texto: 'Ajuste', signo: '' },
}

const unidades = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 3 })

/**
 * A-03 · CCPIP-158 (RF-127): movimientos de existencias del punto, entre ellos el descuento que
 * genero cada compra confirmada, con su numero de pedido. Solo consulta: registrar entradas y
 * salidas (CCPIP-121, CCPIP-287) y recibir transferencias (CCPIP-277) llegan con sus historias.
 */
export default function MovimientosPage({ path }) {
  const [tipo, setTipo] = useState('')
  const [pedido, setPedido] = useState('')
  const [buscado, setBuscado] = useState('')
  const [pagina, setPagina] = useState(0)
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)

  const cargar = useCallback(() => {
    let vigente = true
    setError(null)
    consultarMovimientos({ tipo, pedido: buscado, pagina })
      .then((d) => vigente && setDatos(d))
      .catch((e) => vigente && e.status !== 401 && setError(e))
    return () => {
      vigente = false
    }
  }, [tipo, buscado, pagina])

  useEffect(cargar, [cargar])

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
    <LayoutAcopio path={path} punto={datos?.ubicacion} titulo="Movimientos">
      <div className="movimientos__filtros">
        <Pestanas opciones={PESTANAS} valor={tipo} onCambiar={cambiarTipo} etiqueta="Tipo de movimiento" />
        <Buscador
          id="buscar-pedido"
          etiqueta="Número de pedido"
          placeholder="Buscar por N.º de pedido"
          maxLength={20}
          value={pedido}
          onChange={(e) => setPedido(e.target.value)}
          onBuscar={buscar}
        />
      </div>

      {error && (
        <EstadoVacio titulo="No pudimos cargar los movimientos" descripcion={error.message}>
          <Boton tamano="M" tipo="secundario" onClick={cargar}>Reintentar</Boton>
        </EstadoVacio>
      )}

      {!error && datos && buscado && (
        <Alerta
          tono="info"
          titulo={`Movimientos del pedido ${buscado.toUpperCase()}`}
          descripcion={datos.total === 0 ? 'Ese pedido no tiene movimientos en tu punto.' : undefined}
          onCerrar={limpiar}
        />
      )}

      {!error && datos?.items.length === 0 && !buscado && (
        <EstadoVacio titulo="Aún no hay movimientos" descripcion="Aquí verás las entradas, las ventas y los ajustes de tu punto." />
      )}

      {!error && datos?.items.length > 0 && (
        <>
          <TablaMovimientos items={datos.items} />
          <Paginacion pagina={datos.pagina} totalPaginas={datos.totalPaginas} onCambiar={setPagina} />
        </>
      )}
    </LayoutAcopio>
  )
}

function TablaMovimientos({ items }) {
  return (
    <div className="tabla">
      <div className="tabla__fila tabla__fila--encabezado" aria-hidden="true">
        <span className="tabla__celda movimientos__col-fecha">Fecha</span>
        <span className="tabla__celda movimientos__col-tipo">Tipo</span>
        <span className="tabla__celda tabla__celda--flex">Producto y lote</span>
        <span className="tabla__celda movimientos__col-cantidad">Cantidad</span>
        <span className="tabla__celda movimientos__col-registro">Registró</span>
      </div>
      <ul className="movimientos__lista" aria-label="Movimientos del punto">
        {items.map((m) => {
          const tipo = TIPOS[m.tipo] ?? { tono: 'neutro', texto: m.tipo, signo: '' }
          return (
            <li key={m.id} className="tabla__fila">
              <span className="tabla__celda movimientos__col-fecha label-m">{fechaHora(m.registradoEn)}</span>
              <span className="tabla__celda movimientos__col-tipo">
                <Badge tono={tipo.tono}>{tipo.texto}</Badge>
              </span>
              <span className="tabla__celda tabla__celda--flex">
                {[m.producto, m.lote, m.pedido].filter(Boolean).join(' · ')}
              </span>
              <span className="tabla__celda movimientos__col-cantidad">{`${tipo.signo}${unidades.format(m.cantidad)}`}</span>
              <span className="tabla__celda movimientos__col-registro">
                {m.registradoPor ?? (m.tipo === 'SALIDA_VENTA' ? 'Compra en línea' : '—')}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
