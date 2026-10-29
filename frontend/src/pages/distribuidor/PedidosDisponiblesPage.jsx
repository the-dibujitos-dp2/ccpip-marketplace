import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCheck from '../../assets/icons/check.svg'
import { asumirEntrega, listarPedidosDisponibles } from '../../api/distribucion.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutDistribuidor from '../../components/layout/LayoutDistribuidor.jsx'
import Paginacion from '../../components/Paginacion.jsx'
import { fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import usePaginacionLocal from '../../hooks/usePaginacionLocal.js'
import '../../components/Chips.css'
import './PedidosDisponiblesPage.css'

const MINUTOS_DIA = 24 * 60

/** "45 min", "6 h" o "2d 4h": lo que lleva esperando el pedido (DW-01). */
export function textoEspera(minutos, t) {
  if (minutos < 60) return t('distribuidor.pedidos.espera.minutos', { m: minutos })
  const horas = Math.floor(minutos / 60)
  if (horas < 24) return t('distribuidor.pedidos.espera.horas', { h: horas })
  return t('distribuidor.pedidos.espera.dias', { d: Math.floor(horas / 24), h: horas % 24 })
}

/**
 * DW-01 · CCPIP-107 (RF-54): los pedidos preparados que el distribuidor puede asumir, con el punto donde
 * recogerlos, el distrito de destino y cuanto llevan esperando; el que lleva mas esperando va primero. La cola
 * llega por paginas ("Cargar mas") y se filtra por punto de acopio. La direccion y el contacto de quien recibe
 * no se muestran: los consulta quien asumio la entrega (CCPIP-110), igual que el mapa y las rutas.
 *
 * CCPIP-108 (RF-55): el distribuidor elige pedidos con sus casillas y los toma con "Tomar N pedidos". Se asume
 * uno por uno (un POST por pedido): si otro distribuidor se adelanto con alguno, se avisa cuales y el resto
 * queda tomado igual. Despues la cola se vuelve a leer, sin los que ya se tomaron.
 */
export default function PedidosDisponiblesPage({ path }) {
  const { t } = useTranslation()
  const [punto, setPunto] = useState(null)
  const consultar = useCallback(() => listarPedidosDisponibles({ punto }), [punto])
  const { datos, error, recargar } = useConsulta(consultar)
  // Las paginas que se piden despues de la primera; se descartan al volver a consultar la cola
  const [mas, setMas] = useState(null)
  const [cargandoMas, setCargandoMas] = useState(false)
  const [errorMas, setErrorMas] = useState(null)
  // CCPIP-108: los numeros elegidos para tomar, y lo que paso al tomarlos
  const [elegidos, setElegidos] = useState([])
  const [tomando, setTomando] = useState(false)
  const [avisos, setAvisos] = useState([])
  useEffect(() => {
    setMas(null)
    setErrorMas(null)
    // Al releer la cola solo quedan elegidos los pedidos que siguen en ella
    setElegidos((actuales) => actuales.filter((n) => datos?.items.some((p) => p.numero === n)))
  }, [datos])
  // Si el punto filtrado ya no tiene pedidos al recargar, se vuelve a ver todos
  useEffect(() => {
    if (datos && punto !== null && !datos.puntos.some((p) => p.id === punto)) setPunto(null)
  }, [datos, punto])

  const items = datos ? [...datos.items, ...(mas?.items ?? [])] : []
  const paginacion = usePaginacionLocal(items, punto)
  const siguiente = mas ? mas.siguiente : datos?.siguiente
  const hayPedidos = (datos?.puntos.length ?? 0) > 0
  const todos = datos?.puntos.reduce((suma, p) => suma + p.total, 0) ?? 0

  const cargarMas = () => {
    setCargandoMas(true)
    setErrorMas(null)
    listarPedidosDisponibles({ punto, cursor: siguiente })
      .then((pagina) => setMas({ items: [...(mas?.items ?? []), ...pagina.items], siguiente: pagina.siguiente }))
      .catch((e) => e.status !== 401 && setErrorMas(e))
      .finally(() => setCargandoMas(false))
  }

  const elegir = (numero, marcado) =>
    setElegidos((actuales) => (marcado ? [...actuales, numero] : actuales.filter((n) => n !== numero)))

  /** Asume los elegidos uno por uno y avisa cuales se tomaron, cuales gano otro y cuales fallaron. */
  async function tomar() {
    setTomando(true)
    setAvisos([])
    const tomados = []
    const yaTomados = []
    const noDisponibles = []
    const fallidos = []
    for (const numero of elegidos) {
      try {
        await asumirEntrega(numero)
        tomados.push(numero)
      } catch (e) {
        // Un 401 ya lo maneja la app (vuelve a ingresar)
        if (e.status === 401) return setTomando(false)
        if (e.code === 'PEDIDO_YA_ASUMIDO') yaTomados.push(numero)
        else if (e.status === 409 || e.status === 404) noDisponibles.push(numero)
        else fallidos.push(numero)
      }
    }
    const numeros = (lista) => lista.join(', ')
    setAvisos([
      tomados.length > 0 && { tono: 'exito', titulo: t('distribuidor.pedidos.tomados', { count: tomados.length, numeros: numeros(tomados) }) },
      yaTomados.length > 0 && { tono: 'aviso', titulo: t('distribuidor.pedidos.yaTomados', { count: yaTomados.length, numeros: numeros(yaTomados) }) },
      noDisponibles.length > 0 && { tono: 'aviso', titulo: t('distribuidor.pedidos.noDisponibles', { count: noDisponibles.length, numeros: numeros(noDisponibles) }) },
      fallidos.length > 0 && { tono: 'error', titulo: t('distribuidor.pedidos.errorTomar', { count: fallidos.length, numeros: numeros(fallidos) }) },
    ].filter(Boolean))
    // Los que fallaron por la red siguen elegidos para reintentar
    setElegidos(fallidos)
    setTomando(false)
    recargar()
  }

  const titulo = t('distribuidor.pedidos.titulo')
  return (
    <LayoutDistribuidor path={path} titulo={titulo}>
      <section className="pedidos-disponibles" aria-busy={!datos && !error}>
        {avisos.map((a) => (
          <Alerta key={a.tono + a.titulo} {...a} onCerrar={() => setAvisos((actuales) => actuales.filter((x) => x !== a))} />
        ))}
        {error && (
          <EstadoVacio titulo={t('distribuidor.pedidos.error')} descripcion={mensajeError(error)}>
            <Boton tamano="M" onClick={recargar}>{t('distribuidor.pedidos.reintentar')}</Boton>
          </EstadoVacio>
        )}
        {!datos && !error && <Cargando texto={t('distribuidor.pedidos.cargando')} />}

        {datos && !hayPedidos && (
          <EstadoVacio titulo={t('distribuidor.pedidos.vacio')} descripcion={t('distribuidor.pedidos.vacioTexto')}>
            <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('distribuidor.pedidos.actualizar')}</Boton>
          </EstadoVacio>
        )}
        {datos && <Paginacion pagina={paginacion.pagina} totalPaginas={paginacion.totalPaginas} total={datos.total} onCambiar={paginacion.onCambiar} />}

        {datos && hayPedidos && (
          <>
            <div className="pedidos-disponibles__barra">
              <div className="chips__lista" role="group" aria-label={t('distribuidor.pedidos.filtro')}>
                <button type="button" className={`chip${punto === null ? ' chip--activo' : ''}`} aria-pressed={punto === null} onClick={() => setPunto(null)}>
                  {t('distribuidor.pedidos.todos', { total: todos })}
                </button>
                {datos.puntos.map((p) => (
                  <button key={p.id} type="button" className={`chip${punto === p.id ? ' chip--activo' : ''}`} aria-pressed={punto === p.id} onClick={() => setPunto(p.id)}>
                    {`${p.nombre} (${p.total})`}
                  </button>
                ))}
              </div>
              <Boton tamano="S" tipo="secundario" onClick={recargar}>{t('distribuidor.pedidos.actualizar')}</Boton>
            </div>
            <p className="caption" role="status">{t('distribuidor.pedidos.total', { count: datos.total })}</p>
            <table className="pedidos-disponibles__tabla">
              <caption className="visually-hidden">{titulo}</caption>
              <thead>
                <tr>
                  <th scope="col" className="pedidos-disponibles__elegir"><span className="visually-hidden">{t('distribuidor.pedidos.col.elegir')}</span></th>
                  <th scope="col">{t('distribuidor.pedidos.col.pedido')}</th>
                  <th scope="col">{t('distribuidor.pedidos.col.recoger')}</th>
                  <th scope="col">{t('distribuidor.pedidos.col.destino')}</th>
                  <th scope="col">{t('distribuidor.pedidos.col.espera')}</th>
                </tr>
              </thead>
              <tbody>
                {paginacion.filas.map((p) => (
                  <tr key={p.numero}>
                    <td className="pedidos-disponibles__elegir">
                      <input
                        type="checkbox"
                        checked={elegidos.includes(p.numero)}
                        disabled={tomando}
                        aria-label={t('distribuidor.pedidos.elegir', { numero: p.numero })}
                        onChange={(e) => elegir(p.numero, e.target.checked)}
                      />
                    </td>
                    <th scope="row" className="pedidos-disponibles__numero">{p.numero}</th>
                    <td>
                      {p.origen.nombre}
                      <span className="caption">{[p.origen.direccion, p.origen.distrito].filter(Boolean).join(' · ')}</span>
                    </td>
                    <td>
                      {p.destino.distrito}
                      <span className="caption">{`${p.destino.provincia}, ${p.destino.departamento}`}</span>
                    </td>
                    <td>
                      <span title={t('distribuidor.pedidos.disponibleDesde', { fecha: fechaHora(p.disponibleDesde) })}>
                        <Badge tono={p.esperaMinutos >= MINUTOS_DIA ? 'aviso' : 'neutro'}>{textoEspera(p.esperaMinutos, t)}</Badge>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {elegidos.length > 0 && (
              <div className="pedidos-disponibles__tomar">
                <p>{t('distribuidor.pedidos.seleccionados', { count: elegidos.length })}</p>
                <Boton tamano="M" icono={iconoCheck} onClick={() => void tomar()} cargando={tomando}>
                  {tomando ? t('distribuidor.pedidos.tomando') : t('distribuidor.pedidos.tomar', { count: elegidos.length })}
                </Boton>
              </div>
            )}
            {siguiente && (
              <div className="pedidos-disponibles__mas">
                <p className="caption">{t('distribuidor.pedidos.mostrando', { n: items.length, total: datos.total })}</p>
                <Boton tamano="M" tipo="secundario" onClick={cargarMas} cargando={cargandoMas}>
                  {t(cargandoMas ? 'distribuidor.pedidos.cargandoMas' : 'distribuidor.pedidos.cargarMas')}
                </Boton>
              </div>
            )}
            {errorMas && (
              <Alerta tono="error" titulo={t('distribuidor.pedidos.errorMas')} descripcion={mensajeError(errorMas)} />
            )}
          </>
        )}
      </section>
    </LayoutDistribuidor>
  )
}
