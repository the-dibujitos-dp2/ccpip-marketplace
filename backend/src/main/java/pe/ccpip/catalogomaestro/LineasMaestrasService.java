package pe.ccpip.catalogomaestro;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import pe.ccpip.catalogomaestro.CatalogoComun.Accion;
import pe.ccpip.catalogomaestro.CatalogoComun.Entidad;
import pe.ccpip.catalogomaestro.CatalogoComun.Tabla;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.Codigo;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.DatosLinea;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.EstadoFiltro;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.Linea;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.Lineas;
import pe.ccpip.catalogomaestro.CatalogoMaestroModelos.NuevaLinea;
import pe.ccpip.comun.ErrorNegocio;
import pe.ccpip.comun.Idioma;
import pe.ccpip.traduccion.Traducciones;

/**
 * Lineas del catalogo maestro: consultar (CCPIP-96), crear (CCPIP-309), modificar (CCPIP-310) y
 * desactivar (CCPIP-311). Una linea inactiva deja de ofrecerse a los productores y en el catalogo publico
 * de lineas, pero las ofertas que ya la tienen la conservan, igual que sus pedidos.
 */
@Service
public class LineasMaestrasService {

    private static final String CAMPO_NOMBRE = "nombre";

    private static final String SELECT = """
            SELECT l.id, l.codigo, l.nombre, t.texto AS nombre_en, l.requiere_vencimiento, l.orden, l.activo,
                   l.merma_umbral_alerta, l.merma_umbral_critico, l.actualizado_en, l.desactivado_en, u.codigo AS unidad_codigo, u.nombre AS unidad_nombre,
                   (SELECT count(*) FROM producto_maestro m WHERE m.linea_id = l.id) AS productos,
                   (SELECT count(*) FROM producto p
                    WHERE p.linea_id = l.id OR EXISTS (SELECT 1 FROM producto_linea pl WHERE pl.producto_id = p.id AND pl.linea_id = l.id)) AS ofertas
            FROM linea_producto l
            LEFT JOIN unidad_medida u ON u.id = l.unidad_sugerida_id
            LEFT JOIN traduccion t ON t.entidad = 'LINEA' AND t.clave = l.codigo AND t.campo = 'nombre'
                 AND t.idioma = 'en' AND t.estado = 'APROBADA'
            WHERE true""";

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogoComun comun;
    private final Traducciones traducciones;

    public LineasMaestrasService(NamedParameterJdbcTemplate jdbc, CatalogoComun comun, Traducciones traducciones) {
        this.jdbc = jdbc;
        this.comun = comun;
        this.traducciones = traducciones;
    }

    /** CCPIP-96: lineas en el orden del catalogo, con cuantos productos maestros y ofertas tiene cada una. */
    @Transactional(readOnly = true)
    public Lineas listar(EstadoFiltro estado, String q) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String sql = SELECT + CatalogoComun.porEstado(estado, "l") + CatalogoComun.porTexto(q, "l.nombre", p)
                + " ORDER BY l.orden, l.nombre";
        return new Lineas(comun.contadores(Tabla.LINEA), jdbc.query(sql, p, (rs, row) -> linea(rs)));
    }

    /**
     * CCPIP-309: la linea nace activa. El codigo y el nombre no se repiten. Sin umbrales de merma, nace con
     * los iniciales de la base (5 % y 10 %, CCPIP-430).
     */
    @Transactional
    public Linea crear(long usuarioId, NuevaLinea nueva) {
        String codigo = nueva.codigo().strip().toUpperCase(Locale.ROOT);
        MapSqlParameterSource p = new MapSqlParameterSource("codigo", codigo);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM linea_producto WHERE codigo = :codigo)", p, Boolean.class))) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "CODIGO_DUPLICADO", "Ya existe una línea con el código " + codigo + ".");
        }
        DatosLinea d = nueva.datos();
        validarNombre(d.nombre(), null);
        Integer id = jdbc.queryForObject("""
                INSERT INTO linea_producto (codigo, nombre, requiere_vencimiento, unidad_sugerida_id, orden)
                VALUES (:codigo, :nombre, :vence, :unidad,
                        coalesce(:orden, (SELECT coalesce(max(orden), 0) + 1 FROM linea_producto)))
                RETURNING id""", parametros(d, p), Integer.class);
        guardarNombreEn(codigo, d.nombreEn());
        guardarUmbrales(obtener(Objects.requireNonNull(id)), d);
        Linea creada = obtener(id);
        comun.auditar(Entidad.LINEA, creada.id(), usuarioId, Accion.CREAR, foto(creada));
        return creada;
    }

    /**
     * CCPIP-310: cambia nombre, vencimiento, unidad sugerida, orden y nombre en ingles. Si cambia el nombre,
     * su traduccion anterior queda desactualizada (CCPIP-373) salvo que se escriba la nueva. Las ofertas no
     * se tocan: muestran la linea por su referencia. Los umbrales de merma (CCPIP-430) cambian el color del
     * tablero desde la siguiente consulta; la merma registrada no cambia.
     */
    @Transactional
    public Linea modificar(long usuarioId, int id, DatosLinea d) {
        Linea antes = obtener(id);
        validarNombre(d.nombre(), id);
        jdbc.update("""
                UPDATE linea_producto SET nombre = :nombre, requiere_vencimiento = :vence, unidad_sugerida_id = :unidad,
                       orden = coalesce(:orden, orden), actualizado_en = now()
                WHERE id = :id""", parametros(d, new MapSqlParameterSource("id", id)));
        guardarUmbrales(antes, d);
        if (!antes.nombre().equals(d.nombre().strip())) {
            traducciones.invalidar(Traducciones.Entidad.LINEA, antes.codigo(), CAMPO_NOMBRE);
        }
        String nombreEn = CatalogoComun.limpio(d.nombreEn());
        if (nombreEn != null && !(nombreEn.equals(antes.nombreEn()) && antes.nombre().equals(d.nombre().strip()))) {
            guardarNombreEn(antes.codigo(), nombreEn);
        }
        Linea despues = obtener(id);
        Map<String, Object> cambios = CatalogoComun.diferencias(foto(antes), foto(despues));
        if (!cambios.isEmpty()) {
            comun.auditar(Entidad.LINEA, id, usuarioId, Accion.EDITAR, cambios);
        }
        return despues;
    }

    /**
     * CCPIP-311: desactivar no borra. La linea sale de los formularios y del catalogo publico de lineas;
     * sus ofertas, productos maestros, pedidos y traducciones se conservan.
     */
    @Transactional
    public Linea desactivar(long usuarioId, int id) {
        obtener(id);
        comun.cambiarActivo(Tabla.LINEA, id, false, "LINEA_YA_INACTIVA", "La línea ya está desactivada.");
        Linea linea = obtener(id);
        comun.auditar(Entidad.LINEA, id, usuarioId, Accion.DESACTIVAR, conservado(linea));
        return linea;
    }

    /** Vuelve a ofrecer una linea desactivada por error. */
    @Transactional
    public Linea reactivar(long usuarioId, int id) {
        obtener(id);
        comun.cambiarActivo(Tabla.LINEA, id, true, "LINEA_YA_ACTIVA", "La línea ya está activa.");
        Linea linea = obtener(id);
        comun.auditar(Entidad.LINEA, id, usuarioId, Accion.REACTIVAR, null);
        return linea;
    }

    Linea obtener(int id) {
        return jdbc.query(SELECT + " AND l.id = :id", new MapSqlParameterSource("id", id), (rs, row) -> linea(rs))
                .stream().findFirst()
                .orElseThrow(() -> ErrorNegocio.noEncontrado("LINEA_NO_ENCONTRADA", "La línea no existe."));
    }

    private void validarNombre(String nombre, Integer excepto) {
        Boolean repetido = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM linea_producto
                               WHERE lower(inmutable_unaccent(nombre)) = lower(inmutable_unaccent(:nombre))
                                 AND id IS DISTINCT FROM :excepto)""",
                new MapSqlParameterSource("nombre", nombre.strip()).addValue("excepto", excepto), Boolean.class);
        if (Boolean.TRUE.equals(repetido)) {
            throw new ErrorNegocio(HttpStatus.CONFLICT, "NOMBRE_DUPLICADO", "Ya existe una línea con ese nombre.");
        }
    }

    /**
     * CCPIP-430: un umbral nulo conserva el actual. La alerta queda siempre por debajo del critico, si no
     * ninguna merma se veria en alerta.
     */
    private void guardarUmbrales(Linea actual, DatosLinea d) {
        BigDecimal alerta = d.mermaUmbralAlerta() == null ? actual.mermaUmbralAlerta() : d.mermaUmbralAlerta();
        BigDecimal critico = d.mermaUmbralCritico() == null ? actual.mermaUmbralCritico() : d.mermaUmbralCritico();
        if (alerta.compareTo(critico) >= 0) {
            throw ErrorNegocio.invalido("UMBRALES_MERMA_INVALIDOS",
                    "El umbral de alerta debe ser menor que el umbral crítico.");
        }
        jdbc.update("UPDATE linea_producto SET merma_umbral_alerta = :alerta, merma_umbral_critico = :critico WHERE id = :id",
                new MapSqlParameterSource("id", actual.id()).addValue("alerta", alerta).addValue("critico", critico));
    }

    private MapSqlParameterSource parametros(DatosLinea d, MapSqlParameterSource p) {
        String unidad = CatalogoComun.limpio(d.unidadSugerida());
        return p.addValue("nombre", d.nombre().strip()).addValue("vence", d.requiereVencimiento())
                .addValue("unidad", unidad == null ? null : comun.idUnidad(unidad))
                .addValue("orden", d.orden(), java.sql.Types.INTEGER);
    }

    /** El nombre en ingles lo escribe la Camara: se publica aprobado sobre la version actual del original. */
    private void guardarNombreEn(String codigo, String nombreEn) {
        String texto = CatalogoComun.limpio(nombreEn);
        if (texto != null) {
            traducciones.aprobarTexto(Traducciones.Entidad.LINEA, codigo, CAMPO_NOMBRE, Idioma.EN, texto,
                    traducciones.version(Traducciones.Entidad.LINEA, codigo, CAMPO_NOMBRE));
        }
    }

    private static Map<String, Object> foto(Linea l) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("codigo", l.codigo());
        f.put(CAMPO_NOMBRE, l.nombre());
        f.put("nombreEn", l.nombreEn());
        f.put("requiereVencimiento", l.requiereVencimiento());
        f.put("unidadSugerida", l.unidadSugerida() == null ? null : l.unidadSugerida().codigo());
        f.put("orden", l.orden());
        f.put("mermaUmbralAlerta", l.mermaUmbralAlerta());
        f.put("mermaUmbralCritico", l.mermaUmbralCritico());
        return f;
    }

    private static Map<String, Object> conservado(Linea l) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("productosConservados", l.productos());
        c.put("ofertasConservadas", l.ofertas());
        return c;
    }

    private static Linea linea(ResultSet rs) throws SQLException {
        String unidad = rs.getString("unidad_codigo");
        return new Linea(rs.getInt("id"), rs.getString("codigo"), rs.getString(CAMPO_NOMBRE), rs.getString("nombre_en"),
                rs.getBoolean("requiere_vencimiento"), unidad == null ? null : new Codigo(unidad, rs.getString("unidad_nombre")),
                rs.getInt("orden"), rs.getBoolean("activo"), rs.getLong("productos"), rs.getLong("ofertas"),
                rs.getBigDecimal("merma_umbral_alerta"), rs.getBigDecimal("merma_umbral_critico"),
                CatalogoComun.instante(rs, "actualizado_en"), CatalogoComun.instante(rs, "desactivado_en"));
    }
}
