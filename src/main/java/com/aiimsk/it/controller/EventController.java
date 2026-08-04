package com.aiimsk.it.controller;

import com.aiimsk.it.model.Event;
import com.aiimsk.it.model.EventImage;
import com.aiimsk.it.repository.EventRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Controller
public class EventController {

    private final EventRepository eventRepository;

    @Value("${file.upload-dir}")
    private String uploadDir;

    public EventController(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping("/")
    public String showForm(Model model, Authentication authentication, HttpSession session) {
        System.out.println("GET / session id = " + session.getId());
        System.out.println("GET / auth = " + (authentication != null ? authentication.getName() : "null"));

        if (!model.containsAttribute("event")) {
            model.addAttribute("event", new Event());
        }

        String formToken = UUID.randomUUID().toString();
        session.setAttribute("eventFormToken", formToken);

        System.out.println("GET / generated token = " + formToken);
        System.out.println("GET / session token stored = " + session.getAttribute("eventFormToken"));

        model.addAttribute("formToken", formToken);
        model.addAttribute("loggedInUser", authentication.getName());
        return "event-form";
    }

    @PostMapping("/save")
    public String saveEvent(@ModelAttribute Event event,
            @RequestParam("imageFiles") MultipartFile[] imageFiles,
            @RequestParam("formToken") String formToken,
            HttpSession session,
            Authentication authentication,
            RedirectAttributes redirectAttributes) throws IOException {

        System.out.println("POST /save session id = " + session.getId());
        System.out.println("POST /save auth = " + (authentication != null ? authentication.getName() : "null"));
        System.out.println("POST /save formToken = " + formToken);
        System.out.println("POST /save session token = " + session.getAttribute("eventFormToken"));

        String sessionToken = (String) session.getAttribute("eventFormToken");
        if (sessionToken == null || !sessionToken.equals(formToken)) {
            System.out.println("TOKEN MISMATCH");
            redirectAttributes.addFlashAttribute("error", "Duplicate or invalid form submission detected.");
            return "redirect:/";
        }

        session.removeAttribute("eventFormToken");
        System.out.println("Token accepted and removed from session");

        int nonEmptyFiles = 0;
        for (MultipartFile file : imageFiles) {
            if (!file.isEmpty()) {
                nonEmptyFiles++;
            }
        }

        if (nonEmptyFiles > 5) {
            redirectAttributes.addFlashAttribute("error", "You can upload maximum 5 images only.");
            redirectAttributes.addFlashAttribute("event", event);
            return "redirect:/";
        }

        File dir = new File(uploadDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        event.setCreatedByEmail(authentication.getName());

        for (MultipartFile file : imageFiles) {
            if (!file.isEmpty()) {
                String contentType = file.getContentType();
                if (contentType == null || !contentType.startsWith("image/")) {
                    redirectAttributes.addFlashAttribute("error", "Only image files are allowed.");
                    redirectAttributes.addFlashAttribute("event", event);
                    return "redirect:/";
                }

                String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
                Path path = Paths.get(uploadDir, fileName);
                Files.copy(file.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);

                EventImage image = new EventImage();
                image.setImagePath(fileName);
                event.addImage(image);
            }
        }

        try {
            eventRepository.save(event);
            redirectAttributes.addFlashAttribute("message", "Event saved successfully.");
            System.out.println("Event saved successfully");
        } catch (DataIntegrityViolationException ex) {
            redirectAttributes.addFlashAttribute("error",
                    "This event already exists for the same title, date, venue, and user.");
            System.out.println("DB constraint prevented save");
        }

        return "redirect:/";
    }

    @GetMapping("/events")
    public String listEvents(Model model, Authentication authentication) {
        System.out.println("GET /events auth = " + (authentication != null ? authentication.getName() : "null"));
        model.addAttribute("loggedInUser", authentication.getName());
        model.addAttribute("events", eventRepository.findAll());
        return "event-list";
    }
}