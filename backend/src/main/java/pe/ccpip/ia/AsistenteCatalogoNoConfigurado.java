package pe.ccpip.ia;

import java.util.List;

/** Sin proveedor de IA el asistente no responde; la busqueda y los filtros siguen funcionando. */
public class AsistenteCatalogoNoConfigurado implements AsistenteCatalogo {

    @Override
    public Interpretacion interpretar(String pregunta, List<Opcion> lineas, List<Opcion> regiones) {
        throw IaNoDisponible.catalogo();
    }

    @Override
    public Respuesta responder(String pregunta, List<Candidato> candidatos, boolean ingles) {
        throw IaNoDisponible.catalogo();
    }

    @Override
    public String proveedor() {
        return "ninguno";
    }
}
