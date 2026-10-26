package pe.ccpip.entrega;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.entrega.EntregaModelos.Cobertura;
import pe.ccpip.entrega.EntregaModelos.Destino;
import pe.ccpip.entrega.EntregaModelos.Distrito;
import pe.ccpip.entrega.EntregaModelos.Entrega;

/**
 * CCPIP-59 (RF-40): la entrega a domicilio de un pedido ENVIO. El pedido sale de un punto de acopio y
 * solo llega a distritos que ese punto cubre (cobertura activa); el productor no entrega al comprador. La
 * declaracion de la cobertura por parte del punto es de CCPIP-149 y el reparto, de CCPIP-107 a 110.
 */
@Service
public class EntregaService {

    private final NamedParameterJdbcTemplate jdbc;

    public EntregaService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** La cobertura activa de los puntos, ordenada por distrito para elegirlo en una lista. */
    public List<Cobertura> coberturas(Collection<Long> puntos) {
        if (puntos.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT g.codigo, g.departamento, g.provincia, g.distrito, c.ubicacion_id
                FROM cobertura_entrega c JOIN ubigeo g ON g.codigo = c.ubigeo
                WHERE c.activa AND c.ubicacion_id IN (:puntos)
                ORDER BY g.departamento, g.provincia, g.distrito, c.ubicacion_id""".replace('"', '"'),
                new MapSqlParameterSource("puntos", puntos),
                (rs, row) -> new Cobertura(distrito(rs), rs.getLong("ubicacion_id")));
    }

    /** El distrito de destino debe existir y estar en la cobertura activa del punto, si no 409. */
    public Distrito exigirCobertura(long puntoId, String ubigeo) {
        return jdbc.query("""
                SELECT g.codigo, g.departamento, g.provincia, g.distrito
                FROM cobertura_entrega c JOIN ubigeo g ON g.codigo = c.ubigeo
                WHERE c.activa AND c.ubicacion_id = :punto AND c.ubigeo = :ubigeo""",
                new MapSqlParameterSource("punto", puntoId).addValue("ubigeo", ubigeo), (rs, row) -> distrito(rs))
                .stream().findFirst()
                .orElseThrow(() -> new ErrorNegocio(HttpStatus.CONFLICT, "SIN_COBERTURA",
                        "Este punto de acopio no entrega a domicilio en ese distrito. Elige otro punto o el recojo."));
    }

    /**
     * CCPIP-61 (RF-42): la direccion es texto que escribe el comprador, sin mapas que la confirmen, asi que
     * se pide completa: unos cuantos caracteres y al menos una letra (calle, avenida, manzana...).
     */
    public static void validarDireccion(String direccion) {
        String texto = direccion.strip();
        if (texto.length() < 5 || texto.chars().noneMatch(Character::isLetter)) {
            throw ErrorNegocio.invalido("DIRECCION_INVALIDA",
                    "Indica una dirección completa: calle y número, por ejemplo Av. Larco 123.");
        }
    }

    /** El celular de quien recibe, normalizado a 9 digitos. */
    public static String celularDeContacto(String celular) {
        return PersonaDeRecojo.celular(celular).orElseThrow(() ->
                ErrorNegocio.invalido("CELULAR_INVALIDO", "Ingresa un celular de 9 dígitos que empiece con 9."));
    }

    /**
     * Registra la entrega del pedido, DISPONIBLE y sin distribuidor. El distrito se valida antes con
     * {@link #exigirCobertura}; aqui ya llega resuelto y el celular, normalizado.
     */
    public void registrar(long pedidoId, Distrito distrito, Destino destino, String celular) {
        String referencia = destino.referencia() == null || destino.referencia().isBlank() ? null : destino.referencia().strip();
        jdbc.update("""
                INSERT INTO entrega (pedido_id, ubigeo, direccion, referencia, contacto_nombre, contacto_celular)
                VALUES (:pedido, :ubigeo, :direccion, :referencia, :nombre, :celular)""",
                new MapSqlParameterSource("pedido", pedidoId).addValue("ubigeo", distrito.ubigeo())
                        .addValue("direccion", destino.direccion().strip()).addValue("referencia", referencia)
                        .addValue("nombre", destino.contactoNombre().strip()).addValue("celular", celular));
    }

    /** La entrega del pedido, o vacio si es para recojo. */
    public Optional<Entrega> delPedido(long pedidoId) {
        return jdbc.query("""
                SELECT e.ubigeo, g.departamento, g.provincia, g.distrito, e.direccion, e.referencia,
                       e.contacto_nombre, e.contacto_celular, e.estado, e.asignada_en, e.retirada_en
                FROM entrega e JOIN ubigeo g ON g.codigo = e.ubigeo
                WHERE e.pedido_id = :pedido""", new MapSqlParameterSource("pedido", pedidoId),
                (rs, row) -> new Entrega(rs.getString("ubigeo"), rs.getString("departamento"), rs.getString("provincia"),
                        rs.getString("distrito"), rs.getString("direccion"), rs.getString("referencia"),
                        rs.getString("contacto_nombre"), rs.getString("contacto_celular"), rs.getString("estado"),
                        instante(rs.getObject("asignada_en", Timestamp.class)),
                        instante(rs.getObject("retirada_en", Timestamp.class))))
                .stream().findFirst();
    }

    static Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Distrito distrito(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Distrito(rs.getString("codigo"), rs.getString("departamento"), rs.getString("provincia"),
                rs.getString("distrito"));
    }
}
