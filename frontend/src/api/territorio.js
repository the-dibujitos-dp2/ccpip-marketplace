// Catalogo territorial para elegir el destino de una entrega a domicilio (CCPIP-61).
import { apiFetch } from './http.js'

/** Departamentos del catalogo, ordenados: ['Cusco', 'Junín', ...]. */
export const listarDepartamentos = () => apiFetch('/api/territorio/departamentos')

/** Provincias de un departamento: ['Lima', ...]. */
export const listarProvincias = (departamento) =>
  apiFetch(`/api/territorio/provincias?departamento=${encodeURIComponent(departamento)}`)

/** Distritos de una provincia: [{ ubigeo, departamento, provincia, distrito }]. */
export const listarDistritos = (departamento, provincia) =>
  apiFetch(`/api/territorio/distritos?departamento=${encodeURIComponent(departamento)}&provincia=${encodeURIComponent(provincia)}`)
