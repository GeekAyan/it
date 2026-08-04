package com.aiimsk.it.controller;

import com.aiimsk.it.repository.EventRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final EventRepository eventRepository;

    public AdminController(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping("/dashboard")
    public String dashboard(Authentication authentication, Model model) {
        model.addAttribute("adminUser", authentication.getName());
        return "admin-dashboard";
    }

    @GetMapping("/events")
    public String manageEvents(Authentication authentication, Model model) {
        model.addAttribute("adminUser", authentication.getName());
        model.addAttribute("events", eventRepository.findAll());
        return "admin-event-list";
    }
}