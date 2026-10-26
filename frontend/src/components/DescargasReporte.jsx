import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import './DescargasReporte.css'

/**
 * Botones de descarga de un reporte (PDF, Excel...). `url(formato, idioma)` arma el enlace con los filtros
 * aplicados; el idioma de los textos es el de la interfaz. Si el reporte no esta `disponible` (se esta
 * consultando, fallo o hay un filtro editado sin aplicar) los botones se desactivan y un aviso dice por que,
 * para no descargar un periodo distinto al que se ve.
 */
export default function DescargasReporte({ formatos, url, etiqueta, className = '', disponible = true, cargando = false }) {
  const { t, i18n } = useTranslation()
  const aviso = useId()
  const idioma = i18n.resolvedLanguage === 'en' ? 'en' : 'es'
  return (
    <div className={`descargas-reporte ${className}`}>
      {formatos.map((f) => (disponible ? (
        <a key={f} className="boton boton--secundario boton--M" download href={url(f, idioma)}>
          {etiqueta(f)}
        </a>
      ) : (
        <button key={f} type="button" className="boton boton--secundario boton--M" disabled aria-describedby={aviso}>
          {etiqueta(f)}
        </button>
      )))}
      {!disponible && (
        <p id={aviso} className="caption descargas-reporte__aviso">
          {t(cargando ? 'comun.descargas.cargando' : 'comun.descargas.pendiente')}
        </p>
      )}
    </div>
  )
}
