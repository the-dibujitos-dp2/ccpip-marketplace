import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { cambiarEstadoDenuncia, expedienteDenuncias } from '../../api/valoracion.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import BadgeDenuncia from '../../components/BadgeDenuncia.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import '../productor/productor.css'
import '../consumidor/Valoraciones.css'
import './admin.css'

/**
 * CCPIP-84: historial de denuncias de un productor. El sub administrador las pasa a revision o las cierra
 * con una nota, que queda en el historial con su nombre; el superadministrador solo consulta. Del
 * denunciante solo se sabe si recibio algun pedido de este productor.
 */
export default function ExpedienteDenunciasPage({ path, productorId }) {
  const { t } = useTranslation()
  const { user } = useSesion()
  const consultar = useCallback(() => expedienteDenuncias(productorId), [productorId])
  const { datos, setDatos, error } = useConsulta(consultar)
  const gestiona = user?.role === 'SUB_ADMIN'

  function actualizar(denuncia) {
    setDatos((d) => {
      const denuncias = d.denuncias.map((x) => (x.id === denuncia.id ? denuncia : x))
      const abiertas = denuncias.filter((x) => x.estado === 'RECIBIDA' || x.estado === 'EN_REVISION').length
      return { productor: { ...d.productor, abiertas }, denuncias }
    })
  }

  const p = datos?.productor
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.denuncias')} titulo={p ? p.organizacion : t('denunciasAdmin.titulo')}>
      <Boton tipo="terciario" tamano="S" to={RUTAS.adminDenuncias}>{t('denunciasAdmin.volver')}</Boton>
      {error?.status === 404 && <EstadoVacio titulo={t('denunciasAdmin.sinDenuncias')} />}
      {error && error.status !== 404 && <Alerta tono="error" titulo={mensajeError(error, 'denunciasAdmin.error')} />}
      {p && (
        <p className="body-m">{t('denunciasAdmin.resumenProductor', { total: p.total, abiertas: p.abiertas })}</p>
      )}
      {datos?.denuncias.length > 0 && (
        <ul className="valoraciones__resenas" aria-label={t('denunciasAdmin.historial')}>
          {datos.denuncias.map((d) => (
            <li key={d.id} className="valoraciones__resena">
              <Denuncia denuncia={d} gestiona={gestiona} onActualizada={actualizar} />
            </li>
          ))}
        </ul>
      )}
    </LayoutAdmin>
  )
}

function Denuncia({ denuncia: d, gestiona, onActualizada }) {
  const { t } = useTranslation()
  const titulo = d.producto ? d.producto.nombre : t('denunciasAdmin.contraComunidad')
  return (
    <article aria-label={t('denunciasAdmin.denuncia', { id: d.id, titulo })}>
      <div className="calificar__cabecera">
        <span className="label-m">{titulo}</span>
        <span className="calificar__acciones">
          {d.compraVerificada && <Badge tono="marca">{t('denunciasAdmin.compraVerificada')}</Badge>}
          <BadgeDenuncia estado={d.estado} />
        </span>
      </div>
      <p className="caption">{t('denunciasAdmin.recibida', { fecha: fechaHora(d.creadaEn), motivo: t(`denuncia.motivos.${d.motivo}`) })}</p>
      <p className="body-s">{d.descripcion}</p>
      {d.respuestaProductor && (
        <div>
          <p className="label-m">{t('denunciasAdmin.respuestaProductor')}</p>
          <p className="body-s">{d.respuestaProductor}</p>
        </div>
      )}
      <ol className="valoraciones__historial" aria-label={t('denunciasAdmin.historialDe', { id: d.id })}>
        {d.historial.map((e, i) => (
          <li key={`${e.tipo}-${i}`}>
            <span>
              {t(`denunciasAdmin.evento.${e.tipo}`, { estado: t(`denuncia.estado.${e.estado}`) })}
              {' · '}
              {e.responsable ?? t('denunciasAdmin.compradorProtegido')}
              {e.nota && e.tipo === 'CAMBIO_ESTADO' && <span className="caption"> — {e.nota}</span>}
            </span>
            <time dateTime={e.registradoEn} className="caption">{fechaHora(e.registradoEn)}</time>
          </li>
        ))}
      </ol>
      {gestiona && d.siguientes.length > 0 && <CambiarEstado denuncia={d} onActualizada={onActualizada} />}
    </article>
  )
}

function CambiarEstado({ denuncia, onActualizada }) {
  const { t } = useTranslation()
  const [estado, setEstado] = useState('')
  const [nota, setNota] = useState('')
  const [error, setError] = useState(null)
  const [enviando, setEnviando] = useState(false)

  async function enviar(e) {
    e.preventDefault()
    if (!estado || nota.trim().length < 5) {
      setError(t('denunciasAdmin.faltaEstadoONota'))
      return
    }
    setEnviando(true)
    setError(null)
    try {
      onActualizada(await cambiarEstadoDenuncia(denuncia.id, estado, nota))
      setEstado('')
      setNota('')
    } catch (err) {
      setError(mensajeError(err, 'denunciasAdmin.errorCambio'))
    } finally {
      setEnviando(false)
    }
  }

  return (
    <form className="calificar" onSubmit={enviar} noValidate aria-label={t('denunciasAdmin.gestionar', { id: denuncia.id })}>
      <Campo tipo="selector" etiqueta={t('denunciasAdmin.nuevoEstado')} value={estado} onChange={(e) => setEstado(e.target.value)}
        opciones={denuncia.siguientes.map((s) => ({ valor: s, etiqueta: t(`denuncia.estado.${s}`) }))} />
      <Campo tipo="area" etiqueta={t('denunciasAdmin.nota')} value={nota} maxLength={1000} rows={2}
        ayuda={t('denunciasAdmin.notaAyuda')} onChange={(e) => setNota(e.target.value)} />
      {error && <Alerta tono="error" titulo={error} />}
      <div className="calificar__acciones">
        <Boton type="submit" tamano="S" disabled={enviando}>{t('denunciasAdmin.guardar')}</Boton>
      </div>
    </form>
  )
}
