package dev.portfolio.opspilot.workflow;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Set;

/** Bind-local is the deployment boundary; reject browser cross-origin mutations as an additional guard. */
@Component
public class LocalRequestGuard extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        if (request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            String host = request.getServerName();
            if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(host)) { reject(response); return; }
            if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
                String origin = request.getHeader("Origin");
                try {
                    if (origin != null) {
                        var uri = URI.create(origin);
                        int port = uri.getPort() == -1 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort();
                        if (!uri.getScheme().equals(request.getScheme()) || !uri.getHost().equals(host) || port != request.getServerPort()) { reject(response); return; }
                    }
                    if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) { reject(response); return; }
                    String contentType = request.getContentType();
                    if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) { reject(response); return; }
                } catch (RuntimeException e) { reject(response); return; }
            }
        }
        chain.doFilter(request, response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(403); response.setContentType("application/json;charset=UTF-8"); response.getWriter().write("{\"error\":\"仅接受本地同源 JSON 请求\"}");
    }
}
