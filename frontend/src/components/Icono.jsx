import './Icono.css'

/**
 * Icono Lucide del Figma pintado con el color del texto (mascara CSS): el mismo archivo sirve
 * para cualquier estado (activo, deshabilitado, sobre fondo oscuro) sin redibujarlo.
 */
export default function Icono({ src, tamano = 20, className = '', etiqueta }) {
  const mascara = `url("${src}")`
  const estilo = { width: tamano, height: tamano, WebkitMaskImage: mascara, maskImage: mascara }
  return (
    <span
      className={`icono ${className}`}
      style={estilo}
      role={etiqueta ? 'img' : undefined}
      aria-label={etiqueta}
      aria-hidden={etiqueta ? undefined : true}
    />
  )
}
