package pe.ccpip.distribucion;

import java.time.Instant;
import java.util.List;

import pe.ccpip.entrega.EntregaModelos.Distrito;

/** Respuestas de la distribucion de pedidos a domicilio al distribuidor (CCPIP-107, CCPIP-108, CCPIP-317). */
public final class DistribucionModelos {

    private DistribucionModelos() {
    }

    /** El punto de acopio donde el distribuidor recoge el pedido. Sin coordenadas: los mapas son simulados (RNF-31). */
    public record Origen(long id, String nombre, String distrito, String direccion) {
    }

    /**
     * Un pedido preparado y disponible para distribuir. {@code destino} llega solo hasta el distrito: la direccion
     * y el contacto de quien recibe los consulta quien asumio la entrega (CCPIP-110), y el comprador y su celular
     * nunca se muestran aqui (RNF-05). {@code disponibleDesde} es cuando quedo listo; {@code esperaMinutos},
     * lo que lleva esperando.
     */
    public record PedidoDisponible(String numero, Origen origen, Distrito destino, Instant disponibleDesde,
                                   long esperaMinutos) {
    }

    /** Cuantos pedidos disponibles salen de un punto de acopio. */
    public record PuntoConPedidos(long id, String nombre, long total) {
    }

    /**
     * Una pagina de la cola de pedidos disponibles. {@code siguiente} es el cursor opaco de la pagina que sigue,
     * nulo en la ultima; {@code total}, los pedidos disponibles (del punto filtrado, si se filtro); {@code puntos},
     * los puntos con pedidos y cuantos tiene cada uno, sin importar el filtro, para poder cambiar de punto.
     */
    public record Disponibles(List<PedidoDisponible> items, String siguiente, long total, List<PuntoConPedidos> puntos) {
    }

    /**
     * CCPIP-108: la entrega que el distribuidor acaba de asumir, con el punto donde recogerla y el distrito de
     * destino. La direccion exacta y el contacto de quien recibe llegan con CCPIP-110; {@code asignadaEn} es cuando
     * la asumio.
     */
    public record EntregaAsumida(String numero, Origen origen, Distrito destino, Instant asignadaEn) {
    }

    /**
     * CCPIP-317: una entrega asignada al distribuidor, con el punto donde recogerla y el distrito de destino.
     * {@code estado} es el de la entrega (ASIGNADA, RETIRADA, EN_CAMINO o ENTREGADA); {@code asignadaEn}, cuando la
     * asumio, y {@code retiradaEn}, cuando la retiro del punto (nulo hasta entonces). La direccion exacta y el contacto
     * de quien recibe llegan con CCPIP-110.
     */
    public record EntregaDelDistribuidor(String numero, Origen origen, Distrito destino, String estado,
                                         Instant asignadaEn, Instant retiradaEn) {
    }
}