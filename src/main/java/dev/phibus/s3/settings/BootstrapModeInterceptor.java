package dev.phibus.s3.settings;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class BootstrapModeInterceptor implements HandlerInterceptor {
    private final ApplicationStateService stateService;

    public BootstrapModeInterceptor(ApplicationStateService stateService) {
        this.stateService = stateService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (allowed(path) || stateService.current() == ApplicationStateService.State.READY) {
            return true;
        }
        if (path.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"PostgreSQL is not configured or unavailable\",\"settings\":\"/settings\"}");
        } else {
            response.sendRedirect("/settings");
        }
        return false;
    }

    private static boolean allowed(String path) {
        return path.equals("/settings") || path.startsWith("/api/settings")
                || path.equals("/actuator/health") || path.startsWith("/static/")
                || path.equals("/app.css") || path.equals("/settings.js")
                || path.equals("/favicon.ico") || path.equals("/error");
    }
}
