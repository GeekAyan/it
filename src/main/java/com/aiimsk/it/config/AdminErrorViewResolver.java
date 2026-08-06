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