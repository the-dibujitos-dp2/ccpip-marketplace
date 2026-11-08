package pe.ccpip.auth.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import pe.ccpip.auth.access.AccessInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AccessInterceptor accessInterceptor;
    private final pe.ccpip.auth.access.AuditoriaHttpInterceptor auditoria;

    public WebConfig(AccessInterceptor accessInterceptor, pe.ccpip.auth.access.AuditoriaHttpInterceptor auditoria) {
        this.accessInterceptor = accessInterceptor;
        this.auditoria = auditoria;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditoria).addPathPatterns("/api/**");
        registry.addInterceptor(accessInterceptor).addPathPatterns("/api/**");
    }
}
