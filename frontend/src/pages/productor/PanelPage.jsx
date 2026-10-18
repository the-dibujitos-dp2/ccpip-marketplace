import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoCajaBoton from '../../assets/icons/caja-boton.svg'
import iconoCajaGlifo from '../../assets/icons/caja-glifo.svg'
import iconoEtiquetaGlifo from '../../assets/icons/etiqueta-glifo.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoMas from '../../assets/icons/mas.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import iconoUsuario from '../../assets/icons/usuario.svg'
import { listarIntereses, obtenerPanel } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import Icono from '../../components/Icono.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import { fecha, unidades } from '../../formato.js'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'

/** PW-01: inicio del productor, solo con lo que la plataforma ya maneja. */
export default function PanelPage({ path }) {
  const { t } = useTranslation()
  const { user } = useSesion()
  const [panel, setPanel] = useState(null)
  const [error, setError] = useState(false)
  const [alertaVisible, setAlertaVisible] = useState(true)
  const [intereses, setIntereses] = useState([])

  useEffect(() => {
    obtenerPanel().then(setPanel).catch(() => setError(true))
    // CCPIP-106: si no carga, el panel sigue sin la tarjeta de intereses
    listarIntereses().then(setIntereses).catch(() => setIntereses([]))
  }, [])

  const nombre = user.fullName.split(' ')[0]
  const acciones = (
    <div className="productor__acciones-barra">
      <Boton tipo="secundario" tamano="M" icono={iconoCajaBoton} to={RUTAS.productorLotes}>
        {t('productor.panel.registrarLote')}
      </Boton>
      <Boton tamano="M" icono={iconoMas} to={RUTAS.productorNuevo}>
        {t('productor.panel.publicarProducto')}
      </Boton>
    </div>
  )

  return (
    <PaginaProductor path={path} seccion={t('productor.nav.inicio')} titulo={t('cuenta.hola', { nombre })}>
      {error && <Alerta tono="error" titulo={t('cuenta.error')} descripcion={t('comun.errorConexion')} />}
      {panel && (
        <>
          <div className="productor__encabezado">
            <div>
              <h2 className="heading-h2">
                {panel.tienda} · {panel.tiendaPublicada ? t('productor.panel.tiendaPublicada') : t('productor.panel.tiendaSinPublicar')}
              </h2>
              {panel.verificadoEn && <p className="body-s">{t('productor.panel.verificadoDesde', { fecha: fecha(panel.verificadoEn) })}</p>}
            </div>
            {acciones}
          </div>

          {!panel.tiendaPublicada && (
            <Alerta
              tono="info"
              titulo={t('productor.panel.publicaTienda')}
              descripcion={t('productor.panel.publicaTiendaTexto')}
            />
          )}
          {!panel.tiendaPublicada && (
            <div>
              <Boton tipo="secundario" tamano="M" to={RUTAS.productorTienda}>
                {t('productor.panel.armarTienda')}
              </Boton>
            </div>
          )}

          <div className="productor__kpis">
            <TarjetaKpi titulo={t('productor.panel.kpi.publicados')} valor={panel.publicados} detalle={t('productor.panel.kpi.borradores', { count: panel.borradores })} icono={iconoEtiquetaGlifo} />
            <TarjetaKpi titulo={t('productor.panel.kpi.enVenta')} valor={t('comun.und', { cantidad: unidades(panel.stockEnVenta) })} detalle={t('productor.panel.kpi.enAcopios')} icono={iconoCajaGlifo} />
            <TarjetaKpi titulo={t('productor.panel.kpi.porVencer')} valor={panel.lotesPorVencer.length} detalle={t('productor.panel.kpi.proximos15')} icono={iconoReloj} />
            <TarjetaKpi titulo={t('productor.panel.kpi.despublicados')} valor={panel.retirados} detalle={t('productor.panel.kpi.republicar')} icono={iconoGrafico} />
          </div>

          {alertaVisible && panel.lotesPorVencer.length > 0 && (
            <Alerta
              tono="aviso"
              titulo={t('productor.panel.porVencer', { count: panel.lotesPorVencer.length })}
              descripcion={t('productor.panel.porVencerTexto', { lotes: panel.lotesPorVencer.map((l) => t('productor.panel.loteVence', { producto: l.producto, codigo: l.codigo, ubicacion: l.ubicacion, fecha: fecha(l.vence) })).join('; ') })}
              onCerrar={() => setAlertaVisible(false)}
            />
          )}

          {panel.publicados + panel.borradores + panel.retirados === 0 ? (
            <EstadoVacio titulo={t('productor.panel.sinProductos')} descripcion={t('productor.panel.sinProductosTexto')}>
              <Boton tamano="M" icono={iconoMas} to={RUTAS.productorNuevo}>
                {t('productor.panel.publicarProducto')}
              </Boton>
            </EstadoVacio>
          ) : (
            (panel.publicadosSinStock.length > 0 || intereses.length > 0) && (
              <div className="panel__fila">
                {panel.publicadosSinStock.length > 0 && (
                  <section className="productor__seccion panel__sin-stock" aria-labelledby="titulo-sin-stock">
                    <h2 id="titulo-sin-stock" className="heading-h4">{t('productor.panel.sinStock')}</h2>
                    <div className="tabla">
                      <div className="tabla__fila tabla__fila--encabezado">
                        <span className="tabla__celda tabla__celda--flex">{t('productor.tabla.producto')}</span>
                        <span className="tabla__celda tabla__celda--accion">{t('productor.tabla.accion')}</span>
                      </div>
                      {panel.publicadosSinStock.map((p) => (
                        <div key={p.id} className="tabla__fila">
                          <span className="tabla__celda tabla__celda--flex label-m">{p.nombre}</span>
                          <span className="tabla__celda tabla__celda--accion">
                            <Boton tipo="secundario" tamano="S" to={`${RUTAS.productorLotes}?producto=${p.id}`}>
                              {t('productor.panel.reponer')}
                            </Boton>
                          </span>
                        </div>
                      ))}
                    </div>
                  </section>
                )}
                {intereses.length > 0 && <Intereses intereses={intereses} />}
              </div>
            )
          )}
        </>
      )}
    </PaginaProductor>
  )
}

/**
 * PW-01 (CCPIP-106): cuantas personas registraron interes en cada oferta agotada o pausada y cuanto
 * piden. Sin datos de los compradores. Al volver a venderse se les avisa y salen de la lista (CCPIP-273).
 */
function Intereses({ intereses }) {
  const { t } = useTranslation()
  return (
    <section className="panel__intereses" aria-labelledby="titulo-intereses">
      <h2 id="titulo-intereses" className="heading-h4 panel__intereses-titulo">
        <Icono src={iconoUsuario} tamano={20} />
        {t('productor.panel.intereses.titulo')}
      </h2>
      <ul className="panel__intereses-lista">
        {intereses.map((i) => (
          <li key={i.productoId}>
            <span className="panel__interes-producto">
              <span className="label-m">{i.nombre}</span>
              {i.estado !== 'AGOTADO' && <span className="caption">{t(`productor.panel.intereses.estado.${i.estado}`)}</span>}
            </span>
            <span className="panel__interes-cifra">
              {t('productor.panel.intereses.cifra', { count: i.interesados, cantidad: i.cantidad })}
            </span>
          </li>
        ))}
      </ul>
      <Boton tipo="secundario" tamano="M" anchoCompleto icono={iconoMas} to={RUTAS.productorLotes}>
        {t('productor.panel.intereses.reponer')}
      </Boton>
      <p className="caption">{t('productor.panel.intereses.nota')}</p>
    </section>
  )
}
