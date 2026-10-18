import { useTranslation } from 'react-i18next'
import { mensajeError } from '../i18n/errores.js'
import Alerta from './Alerta.jsx'
import Dialogo from './Dialogo.jsx'

/**
 * Un dialogo con un formulario: el boton de confirmar y Enter en un campo lo envian (`onEnviar`),
 * mientras se envia queda ocupado, y un error general (`error`) se muestra al final con `tituloError`.
 */
export default function DialogoFormulario({ titulo, confirmar, enviando, onEnviar, onCancelar, className, error, tituloError, children }) {
  const { t } = useTranslation()
  return (
    <Dialogo
      abierto
      titulo={titulo}
      confirmar={enviando ? t('productor.lote.registrando') : confirmar}
      onConfirmar={onEnviar}
      onCancelar={onCancelar}
      ocupado={enviando}
    >
      <form className={className} onSubmit={onEnviar} noValidate>
        {children}
        {error && <Alerta tono="error" titulo={tituloError} descripcion={mensajeError(error)} />}
        {/* Enter en un campo envia el formulario, como el boton del dialogo */}
        <button type="submit" hidden />
      </form>
    </Dialogo>
  )
}
