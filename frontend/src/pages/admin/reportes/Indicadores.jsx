import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoAlerta from '../../../assets/icons/alerta-aviso.svg'
import iconoComunidad from '../../../assets/icons/comunidad.svg'
import iconoEstrella from '../../../assets/icons/estrella.svg'
import iconoEtiqueta from '../../../assets/icons/etiqueta.svg'
import iconoGrafico from '../../../assets/icons/grafico.svg'
import iconoPaquete from '../../../assets/icons/paquete.svg'
import iconoTienda from '../../../assets/icons/tienda.svg'
import iconoUsuario from '../../../assets/icons/usuario.svg'
import { Barra, Kpis, nombrePeriodo } from '../../../components/TablaReporte.jsx'
import { fecha, precio, unidades } from '../../../formato.js'
import { columnas, TablaReporte } from './comun.jsx'
import './Indicadores.css'
import { GraficaAdmin } from './GraficaAdmin.jsx'

const TEXTOS = 'admin.reportes.indicadores'

/**
 * Detalle de una tarjeta frente al periodo anterior: el cambio con su signo. Una caida (negativa) va
 * siempre en rojo (CCPIP-454); una subida, en verde, o en rojo si subir es malo (`inverso`). Sin anterior
 * (cero) no hay variacion.
 */
function variacion({ variacion: cambio }, inverso = false) {
  if (cambio == null) return { detalle: 'sinAnterior' }
  const valor = Number(cambio)
  const signo = valor > 0 ? '+' : ''
  let tono = 'neutro'
  if (valor < 0) tono = 'error'
  else if (valor > 0) tono = inverso ? 'error' : 'exito'
  return { detalle: 'variacion', vars: { valor: `${signo}${unidades(valor)}%` }, tono }
}

/**
 * Un grupo de tarjetas con su titulo `{TEXTOS}.{id}.titulo`. `desglose` es una segunda fila (B2C y B2B), para
 * que las tarjetas no se partan de forma dispareja.
 */
function Grupo({ id, tarjetas, desglose }) {
  const { t } = useTranslation()
  const conClave = (fila) => fila.map((k) => ({ ...k, clave: `${id}.${k.clave}` }))
  return (
    <section className="productor__seccion" aria-labelledby={`indicadores-${id}`}>
      <h2 id={`indicadores-${id}`} className="heading-h3">{t(`${TEXTOS}.${id}.titulo`)}</h2>
      <Kpis textos={TEXTOS} tarjetas={conClave(tarjetas)} />
      {desglose && <Kpis textos={TEXTOS} tarjetas={conClave(desglose)} />}
    </section>
  )
}

/**
 * Indicadores agregados de la plataforma en el rango, cada uno frente al periodo anterior de igual
 * duracion, y su serie por dia, semana o mes. Solo cifras agregadas: ningun comprador.
 * - CCPIP-348 (RF-238): ventas, pedidos y ticket promedio, con el desglose B2C y B2B
 * - CCPIP-349 (RF-239): productores activos, con ventas y nuevos
 * - CCPIP-350 (RF-240): productos publicados, nuevos y con ventas, y los publicados por linea
 * - CCPIP-94 (RF-81): actividad: compradores (sin decir quienes), registros, calificaciones y denuncias
 */
export default function Indicadores({ datos }) {
  const { t } = useTranslation()
  const { texto, cantidad, importe } = columnas(t)
  const { ventas, productores, productos, actividad } = datos
  const mayor = Math.max(...datos.periodos.map((p) => Number(p.ventas)), 0)
  return (
    <>
      <p className="caption">{t(`${TEXTOS}.frente`, {
        desde: fecha(datos.desde), hasta: fecha(datos.hasta), anteriorDesde: fecha(datos.anteriorDesde), anteriorHasta: fecha(datos.anteriorHasta),
      })}</p>
      <Grupo id="ventas" tarjetas={[
        { clave: 'total', valor: precio(ventas.total.valor), icono: iconoGrafico, ...variacion(ventas.total) },
        { clave: 'pedidos', valor: unidades(ventas.pedidos.valor), icono: iconoPaquete, ...variacion(ventas.pedidos) },
        { clave: 'ticket', valor: precio(ventas.ticketPromedio.valor), icono: iconoGrafico, ...variacion(ventas.ticketPromedio) },
      ]} desglose={[
        { clave: 'b2c', valor: precio(ventas.desglose.b2c), icono: iconoTienda },
        { clave: 'b2b', valor: precio(ventas.desglose.b2b), icono: iconoComunidad },
      ]} />
      <Grupo id="productores" tarjetas={[
        { clave: 'activos', valor: unidades(productores.activos), icono: iconoComunidad },
        { clave: 'conVentas', valor: unidades(productores.conVentas.valor), icono: iconoGrafico, ...variacion(productores.conVentas) },
        { clave: 'nuevos', valor: unidades(productores.nuevos.valor), icono: iconoUsuario, ...variacion(productores.nuevos) },
      ]} />
      <Grupo id="productos" tarjetas={[
        { clave: 'publicados', valor: unidades(productos.publicados), icono: iconoEtiqueta },
        { clave: 'nuevos', valor: unidades(productos.nuevos.valor), icono: iconoEtiqueta, ...variacion(productos.nuevos) },
        { clave: 'conVentas', valor: unidades(productos.conVentas.valor), icono: iconoGrafico, ...variacion(productos.conVentas) },
      ]} />
      <Grupo id="actividad" tarjetas={[
        { clave: 'compradores', valor: unidades(actividad.compradores.valor), icono: iconoUsuario, ...variacion(actividad.compradores) },
        { clave: 'registros', valor: unidades(actividad.registros.valor), icono: iconoUsuario, ...variacion(actividad.registros) },
        { clave: 'calificaciones', valor: unidades(actividad.calificaciones.valor), icono: iconoEstrella, ...variacion(actividad.calificaciones) },
        // Mas denuncias es peor
        { clave: 'denuncias', valor: unidades(actividad.denuncias.valor), icono: iconoAlerta, ...variacion(actividad.denuncias, true) },
      ]} />
      <GraficaAdmin tipo="indicadores" datos={datos} titulo={t(`${TEXTOS}.porPeriodo`)} />
      <TablaReporte id="indicadores-periodos" titulo={t(`${TEXTOS}.porPeriodo`)} filas={datos.periodos} clave={(p) => p.inicio}
        nota={t(`${TEXTOS}.nota`)}
        columnas={[
          { clave: 'periodo', etiqueta: t('admin.reportes.col.periodo'), periodo: true, valor: (p) => nombrePeriodo(p, datos.agrupacion, t, 'admin.reportes.semana') },
          { clave: 'barra', etiqueta: t('admin.reportes.col.barraVentas'), barra: true, valor: (p) => <Barra partes={[Number(p.ventas)]} mayor={mayor} /> },
          importe('ventas'), cantidad('pedidos'), cantidad('productoresConVentas'), cantidad('productosNuevos'), cantidad('compradores'),
        ]} />
      <LineasPublicadas lineas={productos.porLinea} />
    </>
  )
}

/**
 * CCPIP-350 y CCPIP-450: los productos publicados hoy por linea. Cada linea con publicaciones se despliega
 * en sus productos, con cuantas publicaciones tiene cada uno y que productores los ofrecen.
 */
function LineasPublicadas({ lineas }) {
  const { t } = useTranslation()
  const [abiertas, setAbiertas] = useState(() => new Set())
  const alternar = (codigo) => setAbiertas((actual) => {
    const nuevas = new Set(actual)
    if (nuevas.has(codigo)) nuevas.delete(codigo)
    else nuevas.add(codigo)
    return nuevas
  })
  return (
    <section className="productor__seccion" aria-labelledby="indicadores-lineas">
      <h2 id="indicadores-lineas" className="heading-h3">{t(`${TEXTOS}.productos.porLinea`)}</h2>
      {lineas.length === 0 ? (
        <p className="body-s">{t(`${TEXTOS}.productos.sinLineas`)}</p>
      ) : (
        <ul className="lineas-publicadas" aria-label={t(`${TEXTOS}.productos.porLinea`)}>
          {lineas.map((l) => {
            const abierta = abiertas.has(l.codigo)
            const productos = l.productos ?? []
            const idDetalle = `linea-${l.codigo}-productos`
            return (
              <li key={l.codigo} className="lineas-publicadas__linea">
                <div className="lineas-publicadas__fila">
                  {productos.length > 0 ? (
                    <button type="button" className="lineas-publicadas__alternar label-m" aria-expanded={abierta}
                      aria-controls={idDetalle} onClick={() => alternar(l.codigo)}>
                      <span aria-hidden="true" className="lineas-publicadas__flecha">{abierta ? '▾' : '▸'}</span>
                      {l.nombre}
                    </button>
                  ) : (
                    <span className="lineas-publicadas__nombre label-m">{l.nombre}</span>
                  )}
                  <span className="lineas-publicadas__cantidad">{t(`${TEXTOS}.productos.publicaciones`, { count: Number(l.publicados) })}</span>
                </div>
                {abierta && (
                  <ul id={idDetalle} className="lineas-publicadas__productos" aria-label={t(`${TEXTOS}.productos.deLinea`, { linea: l.nombre })}>
                    {productos.map((p) => (
                      <li key={p.producto} className="lineas-publicadas__producto">
                        <span className="body-m">{p.producto}</span>
                        <span className="caption">
                          {t(`${TEXTOS}.productos.detalleProducto`, {
                            publicaciones: t(`${TEXTOS}.productos.publicaciones`, { count: Number(p.publicaciones) }),
                            productores: t(`${TEXTOS}.productos.productores`, { count: p.productores.length }),
                          })}
                        </span>
                        <span className="caption lineas-publicadas__productores">{p.productores.join(' · ')}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
