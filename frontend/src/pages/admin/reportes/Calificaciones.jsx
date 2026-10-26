import { useTranslation } from 'react-i18next'
import iconoAlerta from '../../../assets/icons/alerta-aviso.svg'
import iconoEstrella from '../../../assets/icons/estrella.svg'
import iconoUsuario from '../../../assets/icons/usuario.svg'
import Badge from '../../../components/Badge.jsx'
import { Barra, Kpis } from '../../../components/TablaReporte.jsx'
import { fecha, unidades } from '../../../formato.js'
import { columnas, rango, TablaReporte } from './comun.jsx'

const TEXTOS = 'admin.reportes.calificaciones'

/** Tono de la tendencia: siempre con su texto (Sube, Estable, En caida). */
const TONO_TENDENCIA = { SUBE: 'exito', ESTABLE: 'neutro', BAJA: 'error' }

/**
 * CCPIP-123 (RF-87, SA-05): calificaciones de los productos en el rango, con la linea como filtro. El
 * promedio solo se muestra desde el minimo de calificaciones configurado (CCPIP-82) y la tendencia compara
 * con el periodo anterior de igual duracion.
 */
export default function Calificaciones({ datos }) {
  const { t } = useTranslation()
  const { texto, cantidad } = columnas(t)
  const { resumen, minimo } = datos
  const anterior = { desde: fecha(datos.anteriorDesde), hasta: fecha(datos.anteriorHasta) }
  const promedio = { clave: 'promedio', etiqueta: t('admin.reportes.col.promedio'), valor: (f) => (f.promedio == null ? '—' : unidades(f.promedio)) }
  const tendencia = {
    clave: 'tendencia',
    etiqueta: t('admin.reportes.col.tendencia'),
    valor: (f) => (f.tendencia ? <Badge tono={TONO_TENDENCIA[f.tendencia]}>{t(`${TEXTOS}.tendencia.${f.tendencia}`)}</Badge> : '—'),
  }
  const mayor = Math.max(...resumen.distribucion.map((e) => Number(e.cantidad)), 0)
  const enCaida = datos.productos.filter((p) => p.tendencia === 'BAJA').length
  return (
    <>
      <Kpis textos={TEXTOS} tarjetas={[
        resumen.promedio == null
          ? { clave: 'promedio', valor: '—', icono: iconoEstrella, detalle: 'sinPromedio', vars: { minimo } }
          : { clave: 'promedio', valor: unidades(resumen.promedio), icono: iconoEstrella },
        { clave: 'resenas', valor: unidades(resumen.resenas), icono: iconoUsuario, vars: rango(datos) },
        { clave: 'enCaida', valor: unidades(enCaida), icono: iconoAlerta, vars: anterior, tono: enCaida > 0 ? 'aviso' : 'neutro' },
      ]} />
      <TablaReporte id="reportes-distribucion" titulo={t(`${TEXTOS}.distribucion`)} filas={resumen.distribucion}
        clave={(e) => e.estrellas}
        columnas={[
          { clave: 'estrellas', etiqueta: t('admin.reportes.col.estrellas'), texto: true, valor: (e) => t(`${TEXTOS}.estrellas`, { count: e.estrellas }) },
          { clave: 'barra', etiqueta: t('admin.reportes.col.distribucion'), barra: true, valor: (e) => <Barra partes={[Number(e.cantidad)]} mayor={mayor} /> },
          cantidad('cantidad'),
        ]} />
      <TablaReporte id="reportes-calificaciones-productos" titulo={t(`${TEXTOS}.productos`)} filas={datos.productos}
        clave={(p) => p.productoId} vacio={t(`${TEXTOS}.vacioProductos`)} nota={t(`${TEXTOS}.nota`, { minimo, ...anterior })}
        columnas={[
          texto('producto', { fuerte: true }), texto('productor'), promedio, cantidad('resenas'), cantidad('denuncias'), tendencia,
        ]} />
    </>
  )
}
