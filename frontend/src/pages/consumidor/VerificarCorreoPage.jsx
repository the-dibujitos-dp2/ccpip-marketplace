import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import iconoSobre from '../../assets/icons/sobre.svg'
import { reenviarVerificacion, verificarCorreo } from '../../api/auth.js'
import { ApiError } from '../../api/http.js'
import Alerta from '../../components/Alerta.jsx'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'
import { navigate, RUTAS } from '../../router.js'
import { CORREO_VALIDO } from './acceso.js'
import LayoutAcceso from './LayoutAcceso.jsx'

// Una sola peticion por token aunque React monte el efecto dos veces (StrictMode) o la persona
// vuelva atras: el enlace sirve una vez y una segunda llamada responderia "ya se uso".
const enCurso = new Map()

function usarEnlace(token) {
  if (!enCurso.has(token)) enCurso.set(token, verificarCorreo(token))
  return enCurso.get(token)
}

/** Solo para pruebas: olvida los enlaces ya usados en esta pestana. */
export function olvidarEnlacesUsados() {
  enCurso.clear()
}

const ESTADO_POR_CODIGO = { ENLACE_USADO: 'usado', ENLACE_VENCIDO: 'vencido' }

/**
 * CCPIP-328: destino del enlace del correo, /verificar-correo?token=... Confirma el correo y
 * ofrece ingresar; el enlace no inicia sesion. Quita el token de la barra de direcciones para que
 * no quede en el historial. Si el enlace vencio o no sirve, permite pedir otro con el correo.
 */
export default function VerificarCorreoPage({ search, onVerificado }) {
  const { t } = useTranslation()
  const [token] = useState(() => new URLSearchParams(search).get('token') ?? '')
  const [estado, setEstado] = useState(token ? 'verificando' : 'invalido')
  const [correo, setCorreo] = useState('')
  const [pedido, setPedido] = useState({ correo: '', error: null, enviando: false, enviado: false })

  useEffect(() => {
    if (token) window.history.replaceState(null, '', RUTAS.verificarCorreo)
    if (!token) return undefined
    let vigente = true
    usarEnlace(token)
      .then(({ email }) => {
        if (!vigente) return
        setCorreo(email)
        setEstado('exito')
        onVerificado?.(email)
      })
      .catch((error) => {
        if (!vigente) return
        setEstado(error instanceof ApiError ? ESTADO_POR_CODIGO[error.code] ?? 'invalido' : 'conexion')
      })
    return () => {
      vigente = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token])

  async function pedirNuevo(e) {
    e.preventDefault()
    if (!CORREO_VALIDO.test(pedido.correo.trim())) {
      setPedido((p) => ({ ...p, error: t('ingresar.validacion.correo') }))
      return
    }
    setPedido((p) => ({ ...p, error: null, enviando: true }))
    try {
      await reenviarVerificacion(pedido.correo.trim())
      setPedido((p) => ({ ...p, enviando: false, enviado: true }))
    } catch {
      setPedido((p) => ({ ...p, enviando: false, error: t('comun.errorConexion') }))
    }
  }

  let contenido
  if (estado === 'verificando') {
    contenido = <p className="revisa__texto" role="status">{t('verificarCorreo.verificando')}</p>
  } else if (estado === 'exito') {
    contenido = (
      <>
        <Alerta tono="exito" titulo={t('verificarCorreo.exito.titulo')} descripcion={t('verificarCorreo.exito.descripcion', { correo })} />
        <Boton anchoCompleto onClick={() => navigate(RUTAS.ingresar)}>{t('ingresar.iniciarSesion')}</Boton>
      </>
    )
  } else if (estado === 'usado') {
    contenido = (
      <>
        <Alerta tono="info" titulo={t('verificarCorreo.usado.titulo')} descripcion={t('verificarCorreo.usado.descripcion')} />
        <Boton anchoCompleto onClick={() => navigate(RUTAS.ingresar)}>{t('ingresar.iniciarSesion')}</Boton>
      </>
    )
  } else if (estado === 'conexion') {
    contenido = <Alerta tono="error" titulo={t('ingresar.errores.conexion.titulo')} descripcion={t('comun.errorConexion')} />
  } else {
    const clave = estado === 'vencido' ? 'vencido' : 'invalido'
    contenido = (
      <>
        <Alerta tono="aviso" titulo={t(`verificarCorreo.${clave}.titulo`)} descripcion={t(`verificarCorreo.${clave}.descripcion`)} />
        {pedido.enviado ? (
          <Alerta tono="exito" titulo={t('verificarCorreo.nuevoEnviado')} />
        ) : (
          <form className="ingresar__formulario" onSubmit={pedirNuevo} noValidate>
            <Campo
              etiqueta={t('ingresar.correo')}
              type="email"
              autoComplete="email"
              placeholder={t('ingresar.correoEjemplo')}
              value={pedido.correo}
              onChange={(e) => setPedido((p) => ({ ...p, correo: e.target.value }))}
              error={pedido.error}
            />
            <Boton type="submit" anchoCompleto disabled={pedido.enviando}>
              {pedido.enviando ? t('verificarCorreo.enviando') : t('verificarCorreo.pedirNuevo')}
            </Boton>
          </form>
        )}
      </>
    )
  }

  return (
    <LayoutAcceso bajada={t('registro.bajada')} etiqueta="titulo-acceso">
      <div className="revisa">
        <span className="revisa__icono" aria-hidden="true">
          <img src={iconoSobre} width="24" height="24" alt="" />
        </span>
        <h1 id="titulo-acceso" className="heading-h2">{t('verificarCorreo.titulo')}</h1>
        {contenido}
      </div>
    </LayoutAcceso>
  )
}
