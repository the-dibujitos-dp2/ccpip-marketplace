import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarPedidosDisponibles } from '../../api/distribucion.js'
import { useConsulta } from '../../api/useConsulta.js'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutDistribuidor from '../../components/layout/LayoutDistribuidor.jsx'
import { fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
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
 * recogerlos, el distrito de destino y cuanto llevan esperando; el que lleva mas esperando va primero. Se
 * filtran por punto de acopio. La direccion y el contacto de quien recibe no se muestran: llegan al asumir la
 * entrega (CCPIP-108, CCPIP-110), igual que el mapa, las rutas y la seleccion para tomar pedidos.
 */
export default function PedidosDisponiblesPage({ path }) {
  const { t } = useTranslation()
  const { datos, error, recargar } = useConsulta(listarPedidosDisponibles)
  const [punto, setPunto] = useState(null)

  // Los puntos donde hay pedidos, con cuantos: son las opciones del filtro
  const puntos = useMemo(() => {
    const porId = new Map()
    ;(datos ?? []).forEach((p) => {
      const actual = porId.get(p.origen.id)
      porId.set(p.origen.id, { id: p.origen.id, nombre: p.origen.nombre, total: (actual?.total ?? 0) + 1 })
    })
    return [...porId.values()].sort((a, b) => a.nombre.localeCompare(b.nombre))
  }, [datos])
  // Si el punto filtrado ya no tiene pedidos al recargar, se vuelve a ver todos
  const filtro = puntos.some((p) => p.id === punto) ? punto : null
  const visibles = filtro === null ? (datos ?? []) : datos.filter((p) => p.origen.id === filtro)

  const titulo = t('distribuidor.pedidos.titulo')
  return (
    <LayoutDistribuidor path={path} titulo={titulo}>
      <section className="pedidos-disponibles" aria-busy={!datos && !error}>
        {error && (
          <EstadoVacio titulo={t('distribuidor.pedidos.error')} descripcion={mensajeError(error)}>
            <Boton tamano="M" onClick={recargar}>{t('distribuidor.pedidos.reintentar')}</Boton>
          </EstadoVacio>
        )}
        {!datos && !error && <p className="visually-hidden">{t('distribuidor.pedidos.cargando')}</p>}

        {datos?.length === 0 && (
          <EstadoVacio titulo={t('distribuidor.pedidos.vacio')} descripcion={t('distribuidor.pedidos.vacioTexto')}>
            <Boton tamano="M" tipo="secundario" onClick={recargar}>{t('distribuidor.pedidos.actualizar')}</Boton>
          </EstadoVacio>
        )}

        {datos?.length > 0 && (
          <>
            <div className="pedidos-disponibles__barra">
              <div className="chips__lista" role="group" aria-label={t('distribuidor.pedidos.filtro')}>
                <button type="button" className={`chip${filtro === null ? ' chip--activo' : ''}`} aria-pressed={filtro === null} onClick={() => setPunto(null)}>
                  {t('distribuidor.pedidos.todos', { total: datos.length })}
                </button>
                {puntos.map((p) => (
                  <button key={p.id} type="button" className={`chip${filtro === p.id ? ' chip--activo' : ''}`} aria-pressed={filtro === p.id} onClick={() => setPunto(p.id)}>
                    {`${p.nombre} (${p.total})`}
                  </button>
                ))}
              </div>
              <Boton tamano="S" tipo="secundario" onClick={recargar}>{t('distribuidor.pedidos.actualizar')}</Boton>
            </div>

            <p className="caption" role="status">{t('distribuidor.pedidos.total', { count: visibles.length })}</p>
            <table className="pedidos-disponibles__tabla">
              <caption className="visually-hidden">{titulo}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('distribuidor.pedidos.col.pedido')}</th>
                  <th scope="col">{t('distribuidor.pedidos.col.recoger')}</th>
                  <th scope="col">{t('distribuidor.pedidos.col.destino')}</th>
                  <th scope="col">{t('distribuidor.pedidos.col.espera')}</th>
                </tr>
              </thead>
              <tbody>
                {visibles.map((p) => (
                  <tr key={p.numero}>
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
          </>
        )}
      </section>
    </LayoutDistribuidor>
  )
}
