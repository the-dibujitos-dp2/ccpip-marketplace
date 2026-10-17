package pe.ccpip.productor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.imagenes.ImagenService;
import pe.ccpip.imagenes.ImagenService.Imagen;
import pe.ccpip.traduccion.InvalidacionTraducciones;
import pe.ccpip.traduccion.Traducciones.Entidad;

/** CCPIP-30: el productor arma y publica la tienda de su comunidad (PW-08). */
@Service
public class TiendaService {

    public static final int MAX_DESTACADOS = 4;

    public record DatosTienda(
            @NotBlank(message = "Escribe el nombre de tu tienda") @Size(max = 120, message = "Máximo 120 caracteres") String nombreTienda,
            @Size(max = 200, message = "Máximo 200 caracteres") String descripcionCorta,
            @Size(max = 4000, message = "Máximo 4000 caracteres") String historia,
            @Size(max = 200, message = "Máximo 200 caracteres") String cita,
            @Size(max = 80, message = "Máximo 80 caracteres") String pueblo,
            @Size(max = 80, message = "Máximo 80 caracteres") String localidad,
            @Positive(message = "Debe ser un número positivo") Integer socios,
            @Size(max = MAX_DESTACADOS, message = "Puedes destacar hasta 4 productos") List<Long> destacados) {
    }

    public record ProductoOpcion(long id, String nombre) {
    }

    public record TiendaEdicion(long id, String comunidad, String region, boolean origenVerificado, LocalDate verificadoEn,
                                String nombreTienda, String descripcionCorta, String historia, String cita, String pueblo,
                                String localidad, Integer socios, boolean publicada, Imagen portada, List<Imagen> galeria,
                                List<Long> destacados, List<ProductoOpcion> productosPublicados) {
    }

    private final JdbcTemplate jdbc;
    private final ImagenService imagenes;
    private final InvalidacionTraducciones invalidacion;

    public TiendaService(JdbcTemplate jdbc, ImagenService imagenes, InvalidacionTraducciones invalidacion) {
        this.jdbc = jdbc;
        this.imagenes = imagenes;
        this.invalidacion = invalidacion;
    }

    public TiendaEdicion obtener(long productorId) {
        List<Imagen> todas = imagenes.deTienda(productorId);
        Imagen portada = todas.stream().filter(i -> i.tipo() == ImagenService.Tipo.PORTADA).findFirst().orElse(null);
        List<Imagen> galeria = todas.stream().filter(i -> i.tipo() == ImagenService.Tipo.GALERIA).toList();
        List<Long> destacados = jdbc.queryForList(
                "SELECT id FROM producto WHERE productor_id = ? AND destacado_tienda ORDER BY nombre", Long.class, productorId);
        List<ProductoOpcion> publicados = jdbc.query(
                "SELECT id, nombre FROM producto WHERE productor_id = ? AND estado = 'PUBLICADO' ORDER BY nombre",
                (rs, row) -> new ProductoOpcion(rs.getLong("id"), rs.getString("nombre")), productorId);
        return jdbc.queryForObject("""
                SELECT id, comunidad, region, origen_verificado, verificado_en, nombre_tienda, descripcion_corta,
                       historia, cita, pueblo, localidad, socios, tienda_publicada
                FROM productor WHERE id = ?""",
                (rs, row) -> new TiendaEdicion(rs.getLong("id"), rs.getString("comunidad"), rs.getString("region"),
                        rs.getBoolean("origen_verificado"), rs.getObject("verificado_en", LocalDate.class),
                        rs.getString("nombre_tienda"), rs.getString("descripcion_corta"), rs.getString("historia"),
                        rs.getString("cita"), rs.getString("pueblo"), rs.getString("localidad"),
                        (Integer) rs.getObject("socios"), rs.getBoolean("tienda_publicada"), portada, galeria,
                        destacados, publicados),
                productorId);
    }

    @Transactional
    public TiendaEdicion guardar(long productorId, DatosTienda datos) {
        TiendaEdicion antes = obtener(productorId);
        jdbc.update("""
                UPDATE productor SET nombre_tienda = ?, descripcion_corta = ?, historia = ?, cita = ?, pueblo = ?,
                       localidad = ?, socios = ?, actualizado_en = now()
                WHERE id = ?""",
                limpio(datos.nombreTienda()), limpio(datos.descripcionCorta()), limpio(datos.historia()),
                limpio(datos.cita()), limpio(datos.pueblo()), limpio(datos.localidad()), datos.socios(), productorId);
        if (datos.destacados() != null) {
            fijarDestacados(productorId, datos.destacados());
        }
        TiendaEdicion despues = obtener(productorId);
        // CCPIP-373: la tienda vive en la fila del productor; la descripcion es de TIENDA y la historia de COMUNIDAD
        invalidacion.siCambio(Entidad.TIENDA, productorId, "nombre", antes.nombreTienda(), despues.nombreTienda());
        invalidacion.siCambio(Entidad.TIENDA, productorId, "descripcion", antes.descripcionCorta(), despues.descripcionCorta());
        invalidacion.siCambio(Entidad.COMUNIDAD, productorId, "historia", antes.historia(), despues.historia());
        invalidacion.siCambio(Entidad.COMUNIDAD, productorId, "cita", antes.cita(), despues.cita());
        return despues;
    }

    /** Publica la tienda si tiene lo minimo que el comprador necesita para conocer a la comunidad. */
    @Transactional
    public TiendaEdicion publicar(long productorId) {
        TiendaEdicion t = obtener(productorId);
        List<String> faltan = new ArrayList<>();
        if (vacio(t.nombreTienda())) {
            faltan.add("El nombre de la tienda");
        }
        if (vacio(t.descripcionCorta())) {
            faltan.add("La descripción de la tienda");
        }
        if (vacio(t.historia())) {
            faltan.add("La historia de tu comunidad");
        }
        if (vacio(t.pueblo())) {
            faltan.add("El pueblo de pertenencia");
        }
        if (!faltan.isEmpty()) {
            throw new ErrorNegocio(HttpStatus.UNPROCESSABLE_ENTITY, "TIENDA_INCOMPLETA",
                    "Para publicar tu tienda completa estos datos.", faltan);
        }
        jdbc.update("""
                UPDATE productor SET tienda_publicada = true, tienda_publicada_en = coalesce(tienda_publicada_en, now())
                WHERE id = ?""", productorId);
        return obtener(productorId);
    }

    private void fijarDestacados(long productorId, List<Long> ids) {
        List<Long> unicos = ids.stream().distinct().toList();
        if (!unicos.isEmpty()) {
            Integer propios = jdbc.queryForObject(
                    "SELECT count(*) FROM producto WHERE productor_id = ? AND id = ANY (?)",
                    Integer.class, productorId, unicos.toArray(Long[]::new));
            if (propios == null || propios != unicos.size()) {
                throw ErrorNegocio.noEncontrado("PRODUCTO_NO_ENCONTRADO", "Uno de los productos elegidos no existe.");
            }
        }
        jdbc.update("UPDATE producto SET destacado_tienda = false WHERE productor_id = ? AND destacado_tienda", productorId);
        if (!unicos.isEmpty()) {
            jdbc.update("UPDATE producto SET destacado_tienda = true WHERE productor_id = ? AND id = ANY (?)",
                    productorId, unicos.toArray(Long[]::new));
        }
    }

    static String limpio(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }
}
