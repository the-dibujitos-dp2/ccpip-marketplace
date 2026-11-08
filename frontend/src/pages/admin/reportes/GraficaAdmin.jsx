import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { BarrasComparativas, SerieTemporal } from '../../../components/reportes-visuales/index.jsx'
import './GraficaAdmin.css'

const numero = (valor) => valor == null || !Number.isFinite(Number(valor)) ? null : Number(valor)
const idiomas = {
  es: {
    b2c: 'B2C', b2b: 'B2B', total: 'Total', desglose: 'Desglose B2C/B2B', ventas: 'Ventas', pedidos: 'Pedidos',
    productores: 'Productores con ventas', productos: 'Productos nuevos', compradores: 'Compradores',
    distribucion: 'Distribución de calificaciones', productosTitulo: 'Calificaciones por producto',
    puntos: 'Por punto de acopio', productoresOrigen: 'Por productor', destinos: 'Por destino', rutas: 'Rutas',
    sinDireccion: 'Sin dirección registrada', estrellas: (n) => `${n} ${n === 1 ? 'estrella' : 'estrellas'}`,
  },
  en: {
    b2c: 'B2C', b2b: 'B2B', total: 'Total', desglose: 'B2C/B2B breakdown', ventas: 'Sales', pedidos: 'Orders',
    productores: 'Producers with sales', productos: 'New products', compradores: 'Buyers',
    distribucion: 'Rating distribution', productosTitulo: 'Ratings by product',
    puntos: 'By collection point', productoresOrigen: 'By producer', destinos: 'By destination', rutas: 'Routes',
    sinDireccion: 'Address not registered', estrellas: (n) => `${n} ${n === 1 ? 'star' : 'stars'}`,
  },
}

/** Presenta exclusivamente campos de la respuesta agregada ya cargada. */
export function GraficaAdmin({ tipo, datos, titulo }) {
  const { i18n } = useTranslation()
  const texto = idiomas[i18n.resolvedLanguage?.startsWith('en') ? 'en' : 'es']
  const finanzas = tipo === 'finanzas'
  const opciones = finanzas ? ['desglose', 'b2c', 'b2b', 'total'] : ['ventas', 'pedidos', 'productores', 'productos', 'compradores']
  const dataset = JSON.stringify([datos?.clave, datos?.aplicado, datos?.desde, datos?.hasta, datos?.agrupacion])
  const [magnitud, setMagnitud] = useState(finanzas ? 'desglose' : 'ventas')
  const [dimension, setDimension] = useState('destino')
  useEffect(() => {
    setMagnitud(finanzas ? 'desglose' : 'ventas')
    setDimension('destino')
  }, [dataset, finanzas])
  const campo = opciones.includes(magnitud) ? magnitud : opciones[0]
  const periodos = datos?.periodos ?? []
  const camposApi = { ventas: 'ventas', pedidos: 'pedidos', productores: 'productoresConVentas', productos: 'productosNuevos', compradores: 'compradores' }
  const series = useMemo(() => {
    if (finanzas) {
      const claves = campo === 'desglose' ? ['b2c', 'b2b'] : [campo]
      return claves.map((key) => ({
        id: key, nombre: texto[key], unidad: 'S/',
        puntos: periodos.map((periodo) => ({ etiqueta: periodo.inicio, valor: numero(periodo[key]) })),
      }))
    }
    return [{
      id: campo, nombre: texto[campo], unidad: campo === 'ventas' ? 'S/' : '',
      puntos: periodos.map((periodo) => ({ etiqueta: periodo.inicio, valor: numero(periodo[camposApi[campo]]) })),
    }]
  }, [campo, finanzas, periodos, texto])

  if (finanzas || tipo === 'indicadores') {
    return (
      <section className="reportes-visual" aria-label={titulo}>
        <div className="reportes-visual__controls" role="group" aria-label={titulo}>
          {opciones.map((opcion) => (
            <button type="button" aria-pressed={campo === opcion} key={opcion} onClick={() => setMagnitud(opcion)}>
              {texto[opcion]}
            </button>
          ))}
        </div>
        <SerieTemporal key={`serie-${dataset}-${campo}`} titulo={titulo} series={series} />
      </section>
    )
  }

  if (tipo === 'productores' || tipo === 'puntos') {
    const filas = tipo === 'productores' ? datos.productores ?? [] : datos.puntos ?? []
    const items = filas.map((fila) => ({
      id: tipo === 'productores' ? fila.productorId : fila.puntoId,
      etiqueta: tipo === 'productores' ? fila.productor : fila.punto,
      valor: numero(fila.total),
    }))
    return <section className="reportes-visual" key={dataset} aria-label={titulo}>
      <BarrasComparativas key={`barras-${dataset}`} titulo={titulo} items={items} unidad="S/" />
    </section>
  }

  if (tipo === 'calificaciones') {
    const distribucion = (datos.resumen?.distribucion ?? []).map((fila) => ({
      id: fila.estrellas, etiqueta: texto.estrellas(fila.estrellas), valor: numero(fila.cantidad),
    }))
    const productos = (datos.productos ?? []).filter((fila) => fila.promedio != null).map((fila) => ({
      id: fila.productoId, etiqueta: fila.producto, valor: numero(fila.promedio),
    }))
    return <section className="reportes-visual" key={dataset} aria-label={titulo}>
      <BarrasComparativas key={`estrellas-${dataset}`} titulo={texto.distribucion} items={distribucion} />
      <BarrasComparativas key={`productos-${dataset}`} titulo={texto.productosTitulo} items={productos} />
    </section>
  }

  if (tipo === 'origen-destino') {
    const dimensiones = [
      ['destino', texto.destinos, datos.porDestino ?? [], (fila) => fila.departamento ?? texto.sinDireccion],
      ['punto', texto.puntos, datos.porPunto ?? [], (fila) => fila.nombre],
      ['productor', texto.productoresOrigen, datos.porProductor ?? [], (fila) => fila.nombre],
      ['ruta', texto.rutas, datos.rutas ?? [], (fila) => `${fila.punto} — ${fila.destino ?? texto.sinDireccion}`],
    ]
    const [id, label, filas, nombre] = dimensiones.find(([key]) => key === dimension) ?? dimensiones[0]
    return <section className="reportes-visual" key={dataset} aria-label={titulo}>
      <div className="reportes-visual__controls" role="group" aria-label={titulo}>
        {dimensiones.map(([key, textoDimension]) => (
          <button key={key} type="button" aria-pressed={id === key} onClick={() => setDimension(key)}>{textoDimension}</button>
        ))}
      </div>
      <BarrasComparativas key={`${id}-${dataset}`} titulo={label} unidad="S/"
        items={filas.map((fila, indice) => ({ id: `${id}-${indice}`, etiqueta: nombre(fila), valor: numero(fila.total) }))} />
    </section>
  }
  return null
}
