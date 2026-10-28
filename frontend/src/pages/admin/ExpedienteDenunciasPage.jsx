import { useCallback, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { cambiarEstadoDenuncia, expedienteDenuncias } from '../../api/valoracion.js'
import { useConsulta } from '../../api/useConsulta.js'
import Alerta from '../../components/Alerta.jsx'
import Badge from '../../components/Badge.jsx'
import BadgeDenuncia, { abierta, ESTADOS_DENUNCIA } from '../../components/BadgeDenuncia.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import FiltrosDenuncias, { pasaFiltros, SIN_FILTROS } from '../../components/FiltrosDenuncias.jsx'
import { SinCoincidencias } from '../../components/LimpiarFiltros.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { diaLima, fechaHora } from '../../formato.js'
import { mensajeError } from '../../i18n/errores.js'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import BloqueoVenta from './BloqueoVenta.jsx'
import { PestanasEstado } from './revision.jsx'
import '../productor/productor.css'
import '../consumidor/Valoraciones.css'
import './admin.css'

/**
 * CCPIP-84: historial de denuncias de un productor. El sub administrador las pasa a revision o las cierra
 * con una nota, que queda en el historial con su nombre; el superadministrador solo consulta. CCPIP-432:
 * cada denuncia muestra a quien se denuncio, quien denuncio (si recibio algun pedido de este productor), la
 * fecha, el motivo y las denuncias anteriores sobre lo mismo; van en pestanas por estado, con busqueda y
 * filtros. CCPIP-85 y CCPIP-322 (Figma S-03): desde aqui el sub administrador bloquea o desbloquea sus ventas.
 */
export default function ExpedienteDenunciasPage({ path, productorId }) {
  const { t } = useTranslation()
  const { user } = useSesion()
  const consultar = useCallback(() => expedienteDenuncias(productorId), [productorId])
  const { datos, setDatos, error } = useConsulta(consultar)
  const [pestana, setPestana] = useState(null)
  const [filtros, setFiltros] = useState(SIN_FILTROS)
  const [aviso, setAviso] = useState(null)
  const gestiona = user?.role === 'SUB_ADMIN'

  // CCPIP-432: una pestana por estado; sin elegir, la primera que tenga denuncias
  const todas = datos?.denuncias ?? []
  const cuantas = (estado) => todas.filter((d) => d.estado === estado).length
  const activa = pestana ?? ESTADOS_DENUNCIA.find((e) => cuantas(e) > 0) ?? ESTADOS_DENUNCIA[0]
  const enPestana = todas.filter((d) => d.estado === activa)
  const visibles = enPestana.filter((d) => pasaFiltros(d, filtros, diaLima(d.creadaEn), d.denunciante))

  function cambiarPestana(estado) {
    setPestana(estado)
    setAviso(null)
  }

  function actualizar(denuncia) {
    // La pestana queda donde estaba: la denuncia pasa a la de su nuevo estado y el aviso dice cual
    setPestana(activa)
    setAviso({ id: denuncia.id, estado: denuncia.estado })
    setDatos((d) => {
      const denuncias = d.denuncias.map((x) => (x.id === denuncia.id ? denuncia : x))
      return { productor: { ...d.productor, abiertas: denuncias.filter((x) => abierta(x.estado)).length }, denuncias }
    })
  }

  const p = datos?.productor
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.denuncias')} titulo={p ? p.organizacion : t('denunciasAdmin.titulo')}>
      <Boton tipo="terciario" tamano="S" to={RUTAS.adminDenuncias}>{t('denunciasAdmin.volver')}</Boton>
      {error?.status === 404 && <EstadoVacio titulo={t('denunciasAdmin.sinDenuncias')} />}
      {error && error.status !== 404 && <Alerta tono="error" titulo={mensajeError(error, 'denunciasAdmin.error')} />}
      {!datos && !error && <Cargando />}
      {p && (
        <p className="body-m">
          {t('denunciasAdmin.resumenProductor', { total: p.total, abiertas: p.abiertas })}
          {p.ventaBloqueada && <> <Badge tono="error">{t('productores.venta.bloqueada')}</Badge></>}
        </p>
      )}
      {p && gestiona && (
        <BloqueoVenta productorId={p.productorId} organizacion={p.organizacion} bloqueada={p.ventaBloqueada}
          onDecidido={(ficha) => setDatos((d) => ({ ...d, productor: { ...d.productor, ventaBloqueada: ficha.ventaBloqueada } }))} />
      )}
      {todas.length > 0 && (
        <>
          <PestanasEstado textos="denunciasAdmin" estados={ESTADOS_DENUNCIA} valor={activa} onCambiar={cambiarPestana} cuantas={cuantas} />
          <FiltrosDenuncias id="buscar-denuncia" denuncias={todas} filtros={filtros} onCambiar={setFiltros}
            buscar={t('denunciasAdmin.buscar')} comunidad={t('denunciasAdmin.contraComunidad')} />
          {aviso && (
            <Alerta tono="exito" titulo={t('denunciasAdmin.movida', { id: aviso.id, estado: t(`denuncia.estado.${aviso.estado}`) })}
              descripcion={t('denunciasAdmin.movidaTexto', { pestana: t(`denunciasAdmin.pestana.${aviso.estado}`) })}
              onCerrar={() => setAviso(null)} />
          )}
          <section className="productor__seccion" role="tabpanel" aria-label={t(`denunciasAdmin.pestana.${activa}`)}>
            {enPestana.length === 0 && <EstadoVacio titulo={t('denunciasAdmin.vacioPestana', { estado: t(`denuncia.estado.${activa}`) })} />}
            {enPestana.length > 0 && visibles.length === 0 && <SinCoincidencias />}
            {visibles.length > 0 && (
              <ul className="valoraciones__resenas" aria-label={t('denunciasAdmin.historial')}>
                {visibles.map((d) => (
                  <li key={d.id} className="valoraciones__resena">
                    <Denuncia denuncia={d} organizacion={p.organizacion} anteriores={anterioresA(d, todas)}
                      gestiona={gestiona} onActualizada={actualizar} />
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </LayoutAdmin>
  )
}

/** CCPIP-432: las denuncias previas sobre lo mismo: el mismo producto o, si es contra la comunidad, cualquiera suya. */
function anterioresA(d, denuncias) {
  const creada = Date.parse(d.creadaEn)
  return denuncias.filter((x) => Date.parse(x.creadaEn) < creada && (!d.producto || x.producto?.id === d.producto.id))
}

function Denuncia({ denuncia: d, organizacion, anteriores, gestiona, onActualizada }) {
  const { t } = useTranslation()
  const titulo = d.producto ? d.producto.nombre : t('denunciasAdmin.contraComunidad')
  const sobre = d.producto ? 'producto' : 'comunidad'
  return (
    <article aria-label={t('denunciasAdmin.denuncia', { id: d.id, titulo })}>
      <div className="calificar__cabecera">
        <span className="label-m">{titulo}</span>
        <span className="calificar__acciones">
          {d.compraVerificada && <Badge tono="marca">{t('denunciasAdmin.compraVerificada')}</Badge>}
          <BadgeDenuncia estado={d.estado} />
        </span>
      </div>
      {/* CCPIP-432: el detalle completo para decidir; el nombre de quien denuncio solo lo ve la administracion */}
      <dl className="moderacion__datos" aria-label={t('denunciasAdmin.detalleDe', { id: d.id })}>
        <dt>{t('denunciasAdmin.detalle.denunciado')}</dt>
        <dd>{d.producto ? t('denunciasAdmin.detalle.productoDe', { producto: d.producto.nombre, organizacion }) : t('denunciasAdmin.detalle.laComunidad', { organizacion })}</dd>
        <dt>{t('denunciasAdmin.detalle.denuncio')}</dt>
        <dd>{d.denunciante ?? t('denunciasAdmin.compradorProtegido')}</dd>
        <dt>{t('denunciasAdmin.detalle.fecha')}</dt>
        <dd><time dateTime={d.creadaEn}>{fechaHora(d.creadaEn)}</time></dd>
        <dt>{t('denunciasAdmin.detalle.motivo')}</dt>
        <dd>{t(`denuncia.motivos.${d.motivo}`)}</dd>
      </dl>
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
      <p className="label-m">{t(`denunciasAdmin.anteriores.${sobre}`, { n: anteriores.length })}</p>
      {anteriores.length === 0 ? (
        <p className="caption">{t(`denunciasAdmin.anteriores.sin.${sobre}`)}</p>
      ) : (
        <ul className="valoraciones__historial" aria-label={t('denunciasAdmin.anteriores.de', { id: d.id })}>
          {anteriores.map((a) => (
            <li key={a.id}>
              <span>
                {t('denunciasAdmin.anteriores.item', { id: a.id, titulo: a.producto ? a.producto.nombre : t('denunciasAdmin.contraComunidad'), motivo: t(`denuncia.motivos.${a.motivo}`) })}
                {' '}<BadgeDenuncia estado={a.estado} />
              </span>
              <time dateTime={a.creadaEn} className="caption">{fechaHora(a.creadaEn)}</time>
            </li>
          ))}
        </ul>
      )}
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
        <Boton type="submit" tamano="S" cargando={enviando}>{t('denunciasAdmin.guardar')}</Boton>
      </div>
    </form>
  )
}
