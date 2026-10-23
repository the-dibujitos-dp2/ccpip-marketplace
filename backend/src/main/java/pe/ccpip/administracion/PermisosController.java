package pe.ccpip.administracion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import pe.ccpip.auth.access.RolesPermitidos;
import pe.ccpip.auth.access.Autenticado;
import pe.ccpip.auth.access.Publico;
import pe.ccpip.auth.model.Rol;

/** Consulta de los accesos que el servidor declara actualmente para cada rol. */
@RestController
@RequestMapping("/api/admin/permisos")
@RolesPermitidos(Rol.SUPER_ADMIN)
public class PermisosController {

    private final RequestMappingHandlerMapping mappings;

    public PermisosController(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    @GetMapping
    public List<PermisosRol> listar() {
        Map<Rol, List<Permiso>> porRol = new EnumMap<>(Rol.class);
        for (Rol rol : Rol.values()) {
            porRol.put(rol, new ArrayList<>());
        }

        mappings.getHandlerMethods().forEach((mapping, handler) -> agregar(mapping, handler, porRol));
        Comparator<Permiso> orden = Comparator.comparing(Permiso::ruta).thenComparing(Permiso::metodo);
        return porRol.entrySet().stream()
                .map(e -> new PermisosRol(e.getKey(), e.getValue().stream().distinct().sorted(orden).toList()))
                .toList();
    }

    private static void agregar(RequestMappingInfo mapping, HandlerMethod handler, Map<Rol, List<Permiso>> porRol) {
        if (AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Publico.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), Publico.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Autenticado.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), Autenticado.class) != null) {
            agregarA(mapping, Rol.values(), porRol);
            return;
        }
        RolesPermitidos declaracion = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RolesPermitidos.class);
        if (declaracion == null) {
            declaracion = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), RolesPermitidos.class);
        }
        if (declaracion == null) {
            return;
        }
        agregarA(mapping, declaracion.value(), porRol);
    }

    private static void agregarA(RequestMappingInfo mapping, Rol[] roles, Map<Rol, List<Permiso>> porRol) {
        Set<String> rutas = mapping.getPatternValues();
        Set<String> metodos = new TreeSet<>();
        Set<RequestMethod> declarados = mapping.getMethodsCondition().getMethods();
        if (declarados.isEmpty()) {
            metodos.add("ALL");
        } else {
            declarados.forEach(m -> metodos.add(m.name()));
        }
        for (Rol rol : roles) {
            for (String ruta : rutas) {
                for (String metodo : metodos) {
                    porRol.get(rol).add(new Permiso(metodo, ruta));
                }
            }
        }
    }

    public record PermisosRol(Rol rol, List<Permiso> permisos) {}
    public record Permiso(String metodo, String ruta) {}
}
