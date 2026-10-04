import { useEffect, useState } from 'react'
import iconoCajaBoton from '../../assets/icons/caja-boton.svg'
import iconoCajaGlifo from '../../assets/icons/caja-glifo.svg'
import iconoEtiquetaGlifo from '../../assets/icons/etiqueta-glifo.svg'
import iconoGrafico from '../../assets/icons/grafico.svg'
import iconoMas from '../../assets/icons/mas.svg'
import iconoReloj from '../../assets/icons/reloj.svg'
import { obtenerPanel } from '../../api/productor.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import EstadoVacio from '../../components/EstadoVacio.jsx'
import TarjetaKpi from '../../components/TarjetaKpi.jsx'
import { fecha } from '../../formato.js'
import { RUTAS } from '../../router.js'
import { useSesion } from '../../sesion.js'
import PaginaProductor from './PaginaProductor.jsx'
import './productor.css'

const unidades = new Intl.NumberFormat('es-PE', { maximumFractionDigits: 3 })

/** PW-01: inicio del productor, solo con lo que la plataforma ya maneja. */
export default function PanelPage({ path }) {
  const { user } = useSesion()
  const [panel, setPanel] = useState(null)
  const [error, setError] = useState(false)
  const [alertaVisible, setAlertaVisible] = useState(true)

  useEffect(() => {
    obtenerPanel().then(setPanel).catch(() => setError(true))
  }, [])

  const nombre = user.fullName.split(' ')[0]
  const acciones = (
    <div className="productor__acciones-barra">
      <Boton tipo="secundario" tamano="M" icono={iconoCajaBoton} to={RUTAS.productorLotes}>
        Registrar lote
      </Boton>
      <Boton tamano="M" icono={iconoMas} to={RUTAS.productorNuevo}>
        Publicar producto
      </Boton>
    </div>
  )

  return (
    <PaginaProductor path={path} seccion="Inicio" titulo={`Hola, ${nombre}`}>
      {error && <Alerta tono="error" titulo="No pudimos cargar tu panel" descripcion="Revisa tu conexión e inténtalo otra vez." />}
      {panel && (
        <>
          <div className="productor__encabezado">
            <div>
              <h2 className="heading-h2">
                {panel.tienda} · {panel.tiendaPublicada ? 'Tienda publicada' : 'Tienda sin publicar'}
              </h2>
              {panel.verificadoEn && <p className="body-s">Origen verificado desde el {fecha(panel.verificadoEn)}</p>}
            </div>
            {acciones}
          </div>

          {!panel.tiendaPublicada && (
            <Alerta
              tono="info"
              titulo="Publica tu tienda para empezar a vender"
              descripcion="Cuéntale al comprador quiénes son y la historia de tu comunidad. Tus productos aparecen en el catálogo cuando tu tienda está publicada."
            />
          )}
          {!panel.tiendaPublicada && (
            <div>
              <Boton tipo="secundario" tamano="M" to={RUTAS.productorTienda}>
                Armar mi tienda
              </Boton>
            </div>
          )}

          <div className="productor__kpis">
            <TarjetaKpi titulo="Productos publicados" valor={panel.publicados} detalle={`${panel.borradores} ${panel.borradores === 1 ? 'borrador' : 'borradores'}`} icono={iconoEtiquetaGlifo} />
            <TarjetaKpi titulo="En venta" valor={`${unidades.format(panel.stockEnVenta)} und`} detalle="En puntos de acopio" icono={iconoCajaGlifo} />
            <TarjetaKpi titulo="Lotes por vencer" valor={panel.lotesPorVencer.length} detalle="En los próximos 15 días" icono={iconoReloj} />
            <TarjetaKpi titulo="Despublicados" valor={panel.retirados} detalle="Puedes volver a publicarlos" icono={iconoGrafico} />
          </div>

          {alertaVisible && panel.lotesPorVencer.length > 0 && (
            <Alerta
              tono="aviso"
              titulo={`${panel.lotesPorVencer.length} ${panel.lotesPorVencer.length === 1 ? 'lote vence' : 'lotes vencen'} pronto (según tu alerta de 15 días)`}
              descripcion={`${panel.lotesPorVencer.map((l) => `${l.producto} (${l.codigo}, en ${l.ubicacion}, vence el ${fecha(l.vence)})`).join('; ')}. Coordina su salida con el punto de acopio.`}
              onCerrar={() => setAlertaVisible(false)}
            />
          )}

          {panel.publicados + panel.borradores + panel.retirados === 0 ? (
            <EstadoVacio titulo="Aún no tienes productos" descripcion="Publica tu primer producto y cuéntale al comprador la historia de tu comunidad.">
              <Boton tamano="M" icono={iconoMas} to={RUTAS.productorNuevo}>
                Publicar producto
              </Boton>
            </EstadoVacio>
          ) : (
            panel.publicadosSinStock.length > 0 && (
              <section className="productor__seccion" aria-labelledby="titulo-sin-stock">
                <h2 id="titulo-sin-stock" className="heading-h4">Publicados sin stock en venta</h2>
                <div className="tabla">
                  <div className="tabla__fila tabla__fila--encabezado">
                    <span className="tabla__celda tabla__celda--flex">Producto</span>
                    <span className="tabla__celda tabla__celda--accion">Acción</span>
                  </div>
                  {panel.publicadosSinStock.map((p) => (
                    <div key={p.id} className="tabla__fila">
                      <span className="tabla__celda tabla__celda--flex label-m">{p.nombre}</span>
                      <span className="tabla__celda tabla__celda--accion">
                        <Boton tipo="secundario" tamano="S" to={`${RUTAS.productorLotes}?producto=${p.id}`}>
                          Registrar lote para reponer
                        </Boton>
                      </span>
                    </div>
                  ))}
                </div>
              </section>
            )
          )}
        </>
      )}
    </PaginaProductor>
  )
}
