package pe.ccpip.ia;

/**
 * Proveedor por defecto mientras el equipo define que IA analizara las evidencias (CCPIP-50):
 * responde que no esta disponible y la solicitud se revisa a mano.
 */
public class AnalistaNoConfigurado implements AnalistaEvidencias {

    @Override
    public Analisis analizar(Solicitud solicitud) {
        throw IaNoDisponible.paraRevision();
    }

    @Override
    public String proveedor() {
        return "ninguno";
    }

    @Override
    public boolean simulado() {
        return false;
    }
}
