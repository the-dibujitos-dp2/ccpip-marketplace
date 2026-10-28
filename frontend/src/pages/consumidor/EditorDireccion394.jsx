import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarDepartamentos, listarProvincias, listarDistritos } from '../../api/territorio.js'
import Boton from '../../components/Boton.jsx'
import Campo from '../../components/Campo.jsx'

function useOpciones(cargar, version) {
  const [estado, setEstado] = useState({ opciones: [], cargando: true, error: false })
  useEffect(() => {
    let activo = true
    setEstado({ opciones: [], cargando: true, error: false })
    Promise.resolve().then(cargar).then(opciones => {
      if (activo) setEstado({ opciones, cargando: false, error: false })
    }).catch(() => { if (activo) setEstado({ opciones: [], cargando: false, error: true }) })
    return () => { activo = false }
  }, [cargar, version])
  return estado
}
const opciones = lista => lista.map(valor => ({ valor, etiqueta: valor }))

export default function EditorDireccion394({ direccion, guardando, error, onGuardar, onCancelar }) {
  const { t } = useTranslation()
  const [form, setForm] = useState({ ...direccion, alias: direccion.alias ?? '', referencia: direccion.referencia ?? '' })
  const [version, setVersion] = useState(0)
  const departamentos = useOpciones(listarDepartamentos, version)
  const cargarProvincias = useCallback(() => form.departamento ? listarProvincias(form.departamento) : Promise.resolve([]), [form.departamento])
  const cargarDistritos = useCallback(() => form.departamento && form.provincia ? listarDistritos(form.departamento, form.provincia) : Promise.resolve([]), [form.departamento, form.provincia])
  const provincias = useOpciones(cargarProvincias, version)
  const distritos = useOpciones(cargarDistritos, version)
  const cargando = departamentos.cargando || provincias.cargando || distritos.cargando
  const errorCatalogo = departamentos.error || provincias.error || distritos.error
  const distritoValido = distritos.opciones.some(d => d.ubigeo === form.ubigeo)
  function escribir(campo) {
    return e => { const valor = e.target.value; setForm(prev => ({ ...prev, [campo]: valor,
      ...(campo === 'departamento' ? { provincia: '', ubigeo: '' } : {}),
      ...(campo === 'provincia' ? { ubigeo: '' } : {}) })) }
  }
  function enviar(e) { e.preventDefault(); if (!guardando && !cargando && !errorCatalogo && distritoValido) onGuardar(form) }
  return <form className="mis-direcciones__editor" aria-label={t('misDirecciones.editar')} aria-busy={guardando || cargando} onSubmit={enviar}>
    <h3>{t('misDirecciones.editar')}</h3>
    <fieldset disabled={guardando}>
      <Campo etiqueta={t('misDirecciones.alias')} value={form.alias} onChange={escribir('alias')} maxLength={40} />
      <Campo tipo="selector" etiqueta={t('compra.domicilio.departamento')} value={form.departamento} onChange={escribir('departamento')} opciones={opciones(departamentos.opciones)} disabled={departamentos.cargando} required />
      <Campo tipo="selector" etiqueta={t('compra.domicilio.provincia')} value={form.provincia} onChange={escribir('provincia')} opciones={opciones(provincias.opciones)} disabled={!form.departamento || provincias.cargando} required />
      <Campo tipo="selector" etiqueta={t('compra.domicilio.distrito')} value={form.ubigeo} onChange={escribir('ubigeo')} opciones={distritos.opciones.map(d => ({ valor: d.ubigeo, etiqueta: d.distrito }))} disabled={!form.provincia || distritos.cargando} required />
      <Campo etiqueta={t('misDirecciones.direccionCampo')} ayuda={t('misDirecciones.reglaDireccion')} value={form.direccion} onChange={escribir('direccion')} minLength={5} maxLength={255} pattern={'(?=.*\\p{L}).{5,}'} title={t('misDirecciones.reglaDireccion')} autoComplete="street-address" required />
      <Campo etiqueta={t('misDirecciones.referenciaCampo')} value={form.referencia} onChange={escribir('referencia')} maxLength={255} />
    </fieldset>
    {errorCatalogo && <div role="alert"><p>{t('misDirecciones.errorCatalogo')}</p><Boton tipo="secundario" onClick={() => setVersion(v => v + 1)} disabled={guardando}>{t('carrito.reintentar')}</Boton></div>}
    {error && <p role="alert">{t('misDirecciones.errorEditar')}</p>}
    <div className="mis-direcciones__editor-acciones">
      <Boton type="submit" cargando={guardando} disabled={cargando || errorCatalogo || !distritoValido}>{t('misDirecciones.guardarCambios')}</Boton>
      <Boton tipo="secundario" disabled={guardando} onClick={onCancelar}>{t('comun.cancelar')}</Boton>
    </div>
  </form>
}
