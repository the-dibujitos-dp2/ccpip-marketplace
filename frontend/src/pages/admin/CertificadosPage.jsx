import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAviso from '../../assets/icons/alerta-aviso.svg'
import iconoError from '../../assets/icons/alerta-error.svg'
import iconoCheck from '../../assets/icons/check-circulo.svg'
import { vigenciaCertificados } from '../../api/autorizaciones.js'
import Badge from '../../components/Badge.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import LayoutAdmin from '../../components/layout/LayoutAdmin.jsx'
import { fechaCorta } from '../../formato.js'
import { RUTAS } from '../../router.js'
import '../productor/productor.css'
import './admin.css'
import { PestanasEstado, TablaBandeja } from './revision.jsx'

const ESTADOS = ['POR_VENCER', 'VENCIDO', 'VIGENTE']
const HORIZONTES = [30, 60, 90]
const TONO = { VENCIDO: 'error', POR_VENCER: 'aviso', VIGENTE: 'exito' }

/** Cuanto falta para que venza, o desde cuando vencio, siempre con texto. */
function Vencimiento({ certificado: c }) {
  const { t } = useTranslation()
  const cuando = c.diasRestantes < 0
    ? t('certificados.vencioHace', { count: -c.diasRestantes })
    : c.diasRestantes === 0 ? t('certificados.venceHoy') : t('certificados.venceEn', { count: c.diasRestantes })
  return (
    <>
      <span className="label-m">{fechaCorta(c.vigenteHasta)}</span>
      <Badge tono={TONO[c.estado]}>{cuando}</Badge>
    </>
  )
}

/**
 * CCPIP-184 (RF-149): la vigencia de los certificados de los productos autorizados. Alerta lo vencido y lo
 * que vence en el horizonte elegido para revisarlo a tiempo; la renovacion se pide desde la solicitud del
 * producto (CCPIP-321). Solo consulta: lo que pasa con la venta lo decide C5, sin bloqueo permanente.
 */
export default function CertificadosPage({ path }) {
  const { t } = useTranslation()
  const [estado, setEstado] = useState('POR_VENCER')
  const [dias, setDias] = useState(30)
  const [datos, setDatos] = useState(null)
  const [error, setError] = useState(null)

  const cargar = useCallback(async (e, d) => {
    setError(null)
    try {
      setDatos(await vigenciaCertificados(e, d))
    } catch (err) {
      setError(err)
    }
  }, [])

  useEffect(() => {
    cargar(estado, dias)
  }, [cargar, estado, dias])

  const c = datos?.contadores
  const actual = datos && datos.estado === estado && datos.dias === dias ? { solicitudes: datos.certificados } : null
  return (
    <LayoutAdmin path={path} migas={t('admin.nav.certificados')} titulo={t('certificados.titulo')}>
      <p className="body-m">{t('certificados.intro')}</p>
      {c && (
        <div className="productor__kpis">
          <TarjetaKpi titulo={t('certificados.kpi.vencidos')} valor={c.vencidos} detalle={t('certificados.kpi.vencidosDetalle')} tono={c.vencidos ? 'error' : 'neutro'} icono={iconoError} />
          <TarjetaKpi titulo={t('certificados.kpi.porVencer', { dias })} valor={c.porVencer} detalle={t('certificados.kpi.porVencerDetalle')} tono={c.porVencer ? 'aviso' : 'neutro'} icono={iconoAviso} />
          <TarjetaKpi titulo={t('certificados.kpi.vigentes')} valor={c.vigentes} detalle={t('certificados.kpi.vigentesDetalle', { dias })} icono={iconoCheck} />
        </div>
      )}
      <div className="certificados__filtros">
        <PestanasEstado textos="certificados" estados={ESTADOS} valor={estado} onCambiar={setEstado} />
        <Campo
          className="certificados__horizonte"
          etiqueta={t('certificados.horizonte')}
          tipo="selector"
          placeholder={false}
          value={String(dias)}
          onChange={(e) => setDias(Number(e.target.value))}
          opciones={HORIZONTES.map((d) => ({ valor: String(d), etiqueta: t('certificados.dias', { count: d }) }))}
        />
      </div>
      <TablaBandeja textos="certificados" estado={estado} datos={error ? null : actual} error={error} columnas={[
        { clase: 'tabla__celda--flex', texto: t('certificados.col.certificado') },
        { clase: 'autorizaciones__organizacion', texto: t('certificados.col.producto') },
        { clase: 'certificados__vence', texto: t('certificados.col.vence') },
        { clase: 'certificados__renovacion', texto: t('certificados.col.renovacion') },
        { clase: 'autorizaciones__accion', texto: <span className="visually-hidden">{t('certificados.col.accion')}</span> },
      ]} fila={(cert) => (
        <div key={cert.evidenciaId} className="tabla__fila" role="row">
          <span className="tabla__celda tabla__celda--flex" role="cell">
            <span className="label-m">{cert.titulo}</span>
            {cert.emisor && <span className="caption autorizaciones__linea">{cert.emisor}</span>}
          </span>
          <span className="tabla__celda autorizaciones__organizacion" role="cell">
            <span className="label-m">{cert.producto}</span>
            <span className="caption autorizaciones__linea">{cert.organizacion}</span>
          </span>
          <span className="tabla__celda certificados__vence" role="cell"><Vencimiento certificado={cert} /></span>
          <span className="tabla__celda certificados__renovacion" role="cell">
            {cert.renovacionPlazo
              ? <Badge tono="info">{t('certificados.renovacionPedida', { fecha: fechaCorta(cert.renovacionPlazo) })}</Badge>
              : <span className="caption">{t('certificados.sinRenovacion')}</span>}
          </span>
          <span className="tabla__celda autorizaciones__accion" role="cell">
            <Boton tipo="secundario" tamano="S" to={`${RUTAS.adminAutorizaciones}/${cert.autorizacionId}`}
              aria-label={t('certificados.revisarDe', { titulo: cert.titulo, producto: cert.producto })}>
              {t('certificados.revisar')}
            </Boton>
          </span>
        </div>
      )} />
      <p className="caption">{t('certificados.nota')}</p>
    </LayoutAdmin>
  )
}
