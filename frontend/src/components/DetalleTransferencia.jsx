import { useTranslation } from 'react-i18next'
import { fechaCorta, fechaHora, unidades } from '../formato.js'
import Badge from './Badge.jsx'
import './DetalleTransferencia.css'

/** Color de cada estado de una transferencia (RNF-47). */
export const TONO_ESTADO = { INICIADA: 'neutro', SOLICITADA: 'aviso', PREPARADA: 'info', DESPACHADA: 'info', RECIBIDA: 'exito' }

/** El estado de una transferencia como etiqueta. */
export function EstadoTransferencia({ estado }) {
  const { t } = useTranslation()
  return <Badge tono={TONO_ESTADO[estado] ?? 'neutro'}>{t(`transferencias.estado.${estado}`)}</Badge>
}

/** Su almacen se llama "Mi almacén"; un punto, por su nombre. */
export function nombreLugar(u, t) {
  return u.tipo === 'ALMACEN_PRODUCTOR' ? t('transferencias.miAlmacen') : u.nombre
}

/** "Centro Nacional Lima · Lima, Lima, Cercado" (RNF-31: solo la division politica, sin rutas). */
export function lugar(u, t) {
  if (!u) return ''
  const nombre = nombreLugar(u, t)
  const zona = [u.departamento, u.provincia, u.distrito].filter(Boolean).join(', ')
  return zona ? `${nombre} · ${zona}` : nombre
}

/**
 * Detalle de una transferencia (CCPIP-278, RF-178): origen, destino, producto, lote, cantidad, estado
 * e historial. Lo usan el productor (PW-17) y el administrador del punto (A-03).
 */
export default function DetalleTransferencia({ transferencia: tr }) {
  const { t } = useTranslation()
  return (
    <div className="detalle-transferencia">
      <dl className="detalle-transferencia__ruta">
        <div>
          <dt className="caption">{t('transferencias.origen')}</dt>
          <dd className="label-m">{lugar(tr.origen, t)}</dd>
        </div>
        <div>
          <dt className="caption">{t('transferencias.destino')}</dt>
          <dd className="label-m">{lugar(tr.destino, t)}</dd>
        </div>
        {tr.productor && (
          <div>
            <dt className="caption">{t('transferencias.productor')}</dt>
            <dd className="label-m">{tr.productor}</dd>
          </div>
        )}
      </dl>

      <table className="detalle-transferencia__tabla">
        <caption className="visually-hidden">{t('transferencias.lotesDe', { codigo: tr.codigo })}</caption>
        <thead>
          <tr>
            <th scope="col">{t('transferencias.col.producto')}</th>
            <th scope="col">{t('transferencias.col.lote')}</th>
            <th scope="col">{t('transferencias.col.vence')}</th>
            <th scope="col">{t('transferencias.col.cantidad')}</th>
            <th scope="col">{t('transferencias.col.loteDestino')}</th>
          </tr>
        </thead>
        <tbody>
          {tr.items.map((i) => (
            <tr key={i.id}>
              <td>{i.producto}</td>
              <td>{i.loteOrigen}</td>
              <td>{i.fechaVencimiento ? fechaCorta(i.fechaVencimiento) : t('transferencias.sinVencimiento')}</td>
              <td>{t('comun.und', { cantidad: unidades(i.cantidad) })}</td>
              <td>{i.loteDestino ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {tr.observacion && <p className="body-s">{t('transferencias.observacion')}: {tr.observacion}</p>}

      {tr.historial && (
        <section aria-label={t('transferencias.historial')}>
          <h3 className="label-m">{t('transferencias.historial')}</h3>
          <ol className="detalle-transferencia__historial">
            {tr.historial.map((p) => (
              <li key={`${p.estado}-${p.fecha}`}>
                <EstadoTransferencia estado={p.estado} />
                <span className="caption">{fechaHora(p.fecha)}</span>
                {p.usuario && <span className="caption">{p.usuario}</span>}
              </li>
            ))}
          </ol>
        </section>
      )}
    </div>
  )
}
