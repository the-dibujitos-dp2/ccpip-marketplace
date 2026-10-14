import { useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoSubir from '../assets/icons/subir.svg'
import Icono from './Icono.jsx'
import './ZonaArchivos.css'

/** Zona para arrastrar o elegir fotos (PW-04, PW-08). Valida tipo y peso antes de subir. */
export default function ZonaArchivos({ onArchivos, multiple = true, texto, disabled }) {
  const { t } = useTranslation()
  const id = useId()
  const input = useRef(null)
  const [encima, setEncima] = useState(false)

  function entregar(lista) {
    const archivos = Array.from(lista ?? [])
    if (archivos.length) onArchivos(multiple ? archivos : archivos.slice(0, 1))
  }

  return (
    <div
      className={`zona-archivos${encima ? ' zona-archivos--encima' : ''}${disabled ? ' zona-archivos--deshabilitada' : ''}`}
      onDragOver={(e) => {
        e.preventDefault()
        if (!disabled) setEncima(true)
      }}
      onDragLeave={() => setEncima(false)}
      onDrop={(e) => {
        e.preventDefault()
        setEncima(false)
        if (!disabled) entregar(e.dataTransfer.files)
      }}
    >
      <Icono src={iconoSubir} tamano={24} />
      <label htmlFor={id} className="zona-archivos__texto">
        {texto ?? t('zonaArchivos.texto')}
      </label>
      <p className="caption">{t('zonaArchivos.formatos')}</p>
      <input
        ref={input}
        id={id}
        className="visually-hidden"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple={multiple}
        disabled={disabled}
        onChange={(e) => {
          entregar(e.target.files)
          e.target.value = ''
        }}
      />
    </div>
  )
}
