import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { listarDepartamentos, listarDistritos, listarProvincias } from '../../api/territorio.js'
import Alerta from '../../components/Alerta.jsx'
import Campo from '../../components/Campo.jsx'

const opciones = (valores) => valores.map((v) => ({ valor: v, etiqueta: v }))

/**
 * CW-05, entrega a domicilio (CCPIP-59 y CCPIP-61): el destino se indica con departamento, provincia y
 * distrito del catalogo territorial, mas la direccion y su referencia, sin mapas. El punto que lo entrega se
 * elige aparte, entre los que cubren el distrito con todo el carrito ({@code distritos}); si ninguno lo
 * cubre, se avisa aqui para elegir otro distrito o el recojo.
 */
export default function EntregaDomicilio({ distritos, form, errores, alEscribir }) {
  const { t } = useTranslation()
  const [departamentos, setDepartamentos] = useState([])
  const [provincias, setProvincias] = useState([])
  const [distritosDe, setDistritosDe] = useState([])
  const [errorCatalogo, setErrorCatalogo] = useState(false)

  // Cada nivel se carga con la eleccion del anterior; si cambia, el nivel de abajo se vacia
  useEffect(() => {
    listarDepartamentos().then(setDepartamentos).catch(() => setErrorCatalogo(true))
  }, [])
  useEffect(() => {
    setProvincias([])
    if (form.departamento) listarProvincias(form.departamento).then(setProvincias).catch(() => setErrorCatalogo(true))
  }, [form.departamento])
  useEffect(() => {
    setDistritosDe([])
    if (form.departamento && form.provincia) {
      listarDistritos(form.departamento, form.provincia).then(setDistritosDe).catch(() => setErrorCatalogo(true))
    }
  }, [form.departamento, form.provincia])

  const elegido = distritosDe.find((d) => d.ubigeo === form.ubigeo)
  const sinPunto = form.ubigeo && !distritos.some((d) => d.distrito.ubigeo === form.ubigeo)
  return (
    <>
      {errorCatalogo && <Alerta tono="error" titulo={t('compra.domicilio.errorCatalogo')} />}
      <div className="compra__fila compra__fila--territorio">
        <Campo
          tipo="selector"
          etiqueta={t('compra.domicilio.departamento')}
          opciones={opciones(departamentos)}
          placeholder={t('compra.domicilio.eligeDepartamento')}
          value={form.departamento}
          onChange={alEscribir('departamento')}
          error={errores.departamento}
        />
        <Campo
          tipo="selector"
          etiqueta={t('compra.domicilio.provincia')}
          opciones={opciones(provincias)}
          placeholder={t('compra.domicilio.eligeProvincia')}
          value={form.provincia}
          onChange={alEscribir('provincia')}
          error={errores.provincia}
          disabled={!form.departamento}
        />
        <Campo
          tipo="selector"
          etiqueta={t('compra.domicilio.distrito')}
          opciones={distritosDe.map((d) => ({ valor: d.ubigeo, etiqueta: d.distrito }))}
          placeholder={t('compra.domicilio.eligeDistrito')}
          value={form.ubigeo}
          onChange={alEscribir('ubigeo')}
          error={errores.ubigeo}
          disabled={!form.provincia}
        />
      </div>
      {sinPunto && (
        <Alerta
          tono="aviso"
          titulo={t('compra.domicilio.sinEntregaTitulo', { distrito: elegido?.distrito ?? '' })}
          descripcion={t('compra.domicilio.sinEntregaTexto')}
        />
      )}
      <Campo
        etiqueta={t('compra.domicilio.direccion')}
        ayuda={t('compra.domicilio.direccionAyuda')}
        value={form.direccion}
        onChange={alEscribir('direccion')}
        error={errores.direccion}
        maxLength={255}
        autoComplete="street-address"
      />
      <Campo
        etiqueta={t('compra.domicilio.referencia')}
        value={form.referencia}
        onChange={alEscribir('referencia')}
        error={errores.referencia}
        maxLength={255}
      />
    </>
  )
}
