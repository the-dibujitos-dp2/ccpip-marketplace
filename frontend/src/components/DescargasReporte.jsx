import { useTranslation } from 'react-i18next'

/**
 * Botones de descarga de un reporte (PDF, Excel...). `url(formato, idioma)` arma el enlace con los filtros
 * que se ven; el idioma de los textos es el de la interfaz.
 */
export default function DescargasReporte({ formatos, url, etiqueta, className }) {
  const { i18n } = useTranslation()
  const idioma = i18n.resolvedLanguage === 'en' ? 'en' : 'es'
  return (
    <div className={className}>
      {formatos.map((f) => (
        <a key={f} className="boton boton--secundario boton--M" download href={url(f, idioma)}>
          {etiqueta(f)}
        </a>
      ))}
    </div>
  )
}
