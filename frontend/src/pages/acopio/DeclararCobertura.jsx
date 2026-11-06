import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { declararCoberturaAcopio } from '../../api/acopioCobertura.js'
import { listarDepartamentos, listarDistritos, listarProvincias } from '../../api/territorio.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'
import Cargando from '../../components/Cargando.jsx'
import DialogoFormulario from '../../components/DialogoFormulario.jsx'

/**
 * CCPIP-149 (RF-118): el punto declara los distritos que atiende, elegidos del catalogo territorial por
 * departamento y provincia. Los ya activos se muestran marcados y sin cambio; uno inactivo se puede volver a
 * declarar. Declarar no altera pedidos ni entregas previas.
 */
export default function DeclararCobertura({ punto, onDeclarado, onCancelar }) {
  const { t } = useTranslation()
  const c = (k, v) => t(`acopio.cobertura401.declarar.${k}`, v)
  const activos = new Set(punto.zonas.filter((z) => z.activa).map((z) => z.ubigeo))
  const [departamentos, setDepartamentos] = useState(null)
  const [provincias, setProvincias] = useState([])
  const [distritos, setDistritos] = useState(null)
  const [departamento, setDepartamento] = useState('')
  const [provincia, setProvincia] = useState('')
  const [elegidos, setElegidos] = useState([])
  const [errorCarga, setErrorCarga] = useState(null)
  const [aviso, setAviso] = useState(null)
  const [enviando, setEnviando] = useState(false)
  const [errorEnvio, setErrorEnvio] = useState(null)

  useEffect(() => {
    listarDepartamentos().then(setDepartamentos).catch(setErrorCarga)
  }, [])

  const elegirDepartamento = async (e) => {
    const valor = e.target.value
    setDepartamento(valor)
    setProvincia('')
    setProvincias([])
    setDistritos(null)
    if (!valor) return
    try {
      setProvincias(await listarProvincias(valor))
    } catch (err) {
      setErrorCarga(err)
    }
  }

  const elegirProvincia = async (e) => {
    const valor = e.target.value
    setProvincia(valor)
    setDistritos(null)
    if (!valor) return
    try {
      setDistritos(await listarDistritos(departamento, valor))
    } catch (err) {
      setErrorCarga(err)
    }
  }

  const alternar = (ubigeo) => {
    setAviso(null)
    setElegidos((actual) => (actual.includes(ubigeo) ? actual.filter((u) => u !== ubigeo) : [...actual, ubigeo]))
  }

  const enviar = async (e) => {
    e?.preventDefault()
    if (elegidos.length === 0) {
      setAviso(c('elegirUno'))
      return
    }
    setEnviando(true)
    setErrorEnvio(null)
    try {
      const actualizado = await declararCoberturaAcopio(punto.puntoId, elegidos)
      onDeclarado(actualizado, elegidos.length)
    } catch (err) {
      setErrorEnvio(err)
    } finally {
      setEnviando(false)
    }
  }

  return (
    <DialogoFormulario
      titulo={c('titulo', { punto: punto.punto })}
      confirmar={c('confirmar')}
      enviando={enviando}
      onEnviar={enviar}
      onCancelar={onCancelar}
      className="cobertura149__form"
      error={errorEnvio}
      tituloError={c('error')}
    >
      <p className="body-s">{c('ayuda')}</p>
      {errorCarga && <Alerta tono="error" titulo={c('errorCatalogo')} />}
      {!departamentos && !errorCarga && <Cargando />}
      {departamentos && (
        <>
          <Campo etiqueta={t('acopio.cobertura401.departamento')} tipo="selector" placeholder={c('elegir')} value={departamento}
            onChange={elegirDepartamento} opciones={departamentos.map((d) => ({ valor: d, etiqueta: d }))} />
          <Campo etiqueta={t('acopio.cobertura401.provincia')} tipo="selector" placeholder={c('elegir')} value={provincia}
            onChange={elegirProvincia} disabled={!departamento} opciones={provincias.map((p) => ({ valor: p, etiqueta: p }))} />
        </>
      )}
      {distritos && (
        <fieldset className="cobertura149__distritos">
          <legend className="label-m">{t('acopio.cobertura401.distrito')}</legend>
          {distritos.map((d) => {
            const yaActivo = activos.has(d.ubigeo)
            return (
              <label key={d.ubigeo} className="cobertura149__distrito">
                <input type="checkbox" checked={yaActivo || elegidos.includes(d.ubigeo)} disabled={yaActivo}
                  onChange={() => alternar(d.ubigeo)} />
                <span>{d.distrito} · {d.ubigeo}</span>
                {yaActivo && <span className="caption">{c('yaDeclarado')}</span>}
              </label>
            )
          })}
        </fieldset>
      )}
      {aviso && <p className="caption cobertura149__aviso" role="alert">{aviso}</p>}
      {elegidos.length > 0 && <p className="caption">{c('elegidos', { count: elegidos.length })}</p>}
    </DialogoFormulario>
  )
}
