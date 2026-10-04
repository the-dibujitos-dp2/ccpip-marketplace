package pe.ccpip.ia;

/**
 * Proveedor por defecto mientras no se integre el modelo real: responde que la IA no esta
 * disponible y la plataforma sigue funcionando con el texto escrito a mano (CCPIP-48).
 */
public class AsistenteNoConfigurado implements AsistenteRedaccion {

    @Override
    public Propuesta proponerDescripcion(DatosDescripcion datos) {
        throw new IaNoDisponible();
    }

    @Override
    public Propuesta proponerHistoria(DatosHistoria datos) {
        throw new IaNoDisponible();
    }

    @Override
    public String proveedor() {
        return "ninguno";
    }
}
