package com.aiimsk.it.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

@Configuration
public class AdminErrorViewResolver {

    @Bean
    public ErrorViewResolver adminErrorResolver() {
        return new ErrorViewResolver() {
            @Override
            public ModelAndView resolveErrorView(
                    HttpServletRequest request,
                    HttpStatus status,
                    Map<String, Object> model) {

                String originalUri = (String) request.getAttribute(
                        RequestDispatcher.ERROR_REQUEST_URI);

                // ERROR_REQUEST_URI is the full URI as sent by the client, so
                // it INCLUDES the context path (e.g. "/event/admin/users" when
                // server.servlet.context-path=/event). Strip it so this check
                // keeps working at the root or under any context path.
                if (originalUri != null) {
                    String contextPath = request.getContextPath();
                    if (contextPath != null && !contextPath.isEmpty()
                            && originalUri.startsWith(contextPath)) {
                        originalUri = originalUri.substring(contextPath.length());
                    }
                    if (originalUri.isEmpty()) {
                        originalUri = "/";
                    }
                }

                if (originalUri != null && originalUri.startsWith("/admin/")) {
                    ModelAndView view = new ModelAndView("admin-error");
                    view.addAllObjects(model);
                    return view;
                }

                return null;
            }
        };
    }
}