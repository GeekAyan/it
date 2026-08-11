package com.aiimsk.it.controller;

import com.aiimsk.it.model.Event;
import com.aiimsk.it.model.EventImage;
import com.aiimsk.it.repository.EventRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.List;
import java.util.Optional;
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

        // Server-side phone number validation
        String phone = event.getContactPhone() != null ? event.getContactPhone().trim() : "";
        if (!phone.matches("^\\+?[0-9\\s\\-()]{10,15}$")) {
            redirectAttributes.addFlashAttribute("error",
                    "Please enter a valid phone number (10-15 digits, optionally starting with +).");
            redirectAttributes.addFlashAttribute("event", event);
            return "redirect:/";
        }
        event.setContactPhone(phone);

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
        String email = authentication.getName();
        model.addAttribute("loggedInUser", email);
        model.addAttribute("events", eventRepository.findByCreatedByEmailOrderByEventDateDesc(email));
        return "event-list";
    }

    @GetMapping("/events/{id}")
    public String viewEvent(@PathVariable Long id,
            Authentication authentication,
            Model model) {

        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid event id: " + id));

        // Only allow the owner to view their own event
        if (!event.getCreatedByEmail().equalsIgnoreCase(authentication.getName())) {
            return "redirect:/events";
        }

        model.addAttribute("loggedInUser", authentication.getName());
        model.addAttribute("event", event);
        return "event-detail";
    }

    @GetMapping("/files/view/{fileName:.+}")
    public ResponseEntity<Resource> viewFile(@PathVariable String fileName,
            Authentication authentication) throws Exception {

        // Find the event that owns this file and verify the current user owns it
        Optional<Event> ownerEvent = eventRepository.findAll().stream()
                .filter(e -> e.getImages().stream()
                        .anyMatch(img -> img.getImagePath().equals(fileName)))
                .findFirst();

        if (ownerEvent.isEmpty() || !ownerEvent.get().getCreatedByEmail().equalsIgnoreCase(authentication.getName())) {
            return ResponseEntity.notFound().build();
        }

        Path filePath = Paths.get(uploadDir).resolve(fileName).normalize();
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(resource);
    }

    @GetMapping("/files/download/{fileName:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName,
            Authentication authentication) throws Exception {

        // Find the event that owns this file and verify the current user owns it
        Optional<Event> ownerEvent = eventRepository.findAll().stream()
                .filter(e -> e.getImages().stream()
                        .anyMatch(img -> img.getImagePath().equals(fileName)))
                .findFirst();

        if (ownerEvent.isEmpty() || !ownerEvent.get().getCreatedByEmail().equalsIgnoreCase(authentication.getName())) {
            return ResponseEntity.notFound().build();
        }

        Path filePath = Paths.get(uploadDir).resolve(fileName).normalize();
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }

    @GetMapping("/events/{id}/download-all")
    public ResponseEntity<byte[]> downloadAllImages(@PathVariable Long id,
            Authentication authentication) throws IOException {

        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid event id: " + id));

        // Only allow the owner to download their own event's images
        if (!event.getCreatedByEmail().equalsIgnoreCase(authentication.getName())) {
            return ResponseEntity.notFound().build();
        }

        List<EventImage> images = event.getImages();

        if (images == null || images.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (int i = 0; i < images.size(); i++) {
                String imagePath = images.get(i).getImagePath();
                Path filePath = Paths.get(uploadDir).resolve(imagePath).normalize();
                File file = filePath.toFile();

                if (!file.exists() || !file.isFile()) {
                    continue;
                }

                String entryName = (i + 1) + "_" + file.getName();
                zos.putNextEntry(new java.util.zip.ZipEntry(entryName));

                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }

        byte[] zipBytes = baos.toByteArray();
        String zipName = "event_" + id + "_images.zip";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zipBytes);
    }
}
