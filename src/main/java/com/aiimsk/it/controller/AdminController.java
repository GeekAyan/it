package com.aiimsk.it.controller;

import com.aiimsk.it.model.Event;
import com.aiimsk.it.model.EventImage;
import com.aiimsk.it.repository.EventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.IntStream;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final EventRepository eventRepository;

    @Value("${file.upload-dir}")
    private String uploadDir;

    public AdminController(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping("/dashboard")
    public String dashboard(Authentication authentication, Model model) {
        model.addAttribute("adminUser", authentication.getName());

        int currentYear = LocalDate.now().getYear();

        List<String> monthlyLabels = List.of(
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec");

        List<Long> monthlyValues = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            monthlyValues.add(eventRepository.countByMonthAndYear(month, currentYear));
        }

        List<String> yearlyLabels = new ArrayList<>();
        List<Long> yearlyValues = new ArrayList<>();
        for (int year = currentYear - 4; year <= currentYear; year++) {
            yearlyLabels.add(String.valueOf(year));
            yearlyValues.add(eventRepository.countByYear(year));
        }

        model.addAttribute("monthlyLabels", monthlyLabels);
        model.addAttribute("monthlyValues", monthlyValues);
        model.addAttribute("yearlyLabels", yearlyLabels);
        model.addAttribute("yearlyValues", yearlyValues);

        return "admin/dashboard";
    }

    @GetMapping("/events")
    public String adminEvents(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            Authentication authentication,
            Model model) {

        List<Event> events = eventRepository.searchByMonthAndYear(month, year);

        model.addAttribute("adminUser", authentication.getName());
        model.addAttribute("events", events);
        model.addAttribute("selectedMonth", month);
        model.addAttribute("selectedYear", year);
        model.addAttribute("months", IntStream.rangeClosed(1, 12).boxed().toList());
        model.addAttribute("years", IntStream.rangeClosed(2024, 2035).boxed().toList());

        // Reliable month options for Thymeleaf (list of {num, name})
        List<Map<String, Object>> monthOptions = new ArrayList<>();
        String[] monthNamesArr = {
                "January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"
        };
        for (int i = 0; i < 12; i++) {
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("num", i + 1);
            opt.put("name", monthNamesArr[i]);
            monthOptions.add(opt);
        }
        model.addAttribute("monthOptions", monthOptions);

        model.addAttribute("monthNames", Map.ofEntries(
                Map.entry(1, "January"),
                Map.entry(2, "February"),
                Map.entry(3, "March"),
                Map.entry(4, "April"),
                Map.entry(5, "May"),
                Map.entry(6, "June"),
                Map.entry(7, "July"),
                Map.entry(8, "August"),
                Map.entry(9, "September"),
                Map.entry(10, "October"),
                Map.entry(11, "November"),
                Map.entry(12, "December")));

        return "admin/events";
    }

    @GetMapping("/events/{id}")
    public String showEventDetails(@PathVariable Long id,
            Authentication authentication,
            Model model) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid event id: " + id));

        model.addAttribute("adminUser", authentication.getName());
        model.addAttribute("event", event);
        return "admin/event-details";
    }

    @GetMapping("/files/view/{fileName:.+}")
    public ResponseEntity<Resource> viewFile(@PathVariable String fileName) throws Exception {
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
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName) throws Exception {
        Path filePath = Paths.get(uploadDir).resolve(fileName).normalize();
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists() || !resource.isReadable()) {
            throw new RuntimeException("File not found: " + fileName);
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }

    @GetMapping("/events/{id}/download-all")
    public ResponseEntity<byte[]> downloadAllImages(@PathVariable Long id) throws IOException {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid event id: " + id));

        List<EventImage> images = event.getImages();

        if (images == null || images.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (int i = 0; i < images.size(); i++) {
                String imagePath = images.get(i).getImagePath();
                Path filePath = Paths.get(uploadDir).resolve(imagePath).normalize();
                File file = filePath.toFile();

                if (!file.exists() || !file.isFile()) {
                    continue;
                }

                String entryName = (i + 1) + "_" + file.getName();
                zos.putNextEntry(new ZipEntry(entryName));

                try (FileInputStream fis = new FileInputStream(file)) {
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

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/events/{id}/delete")
    public String deleteEvent(@PathVariable Long id,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            RedirectAttributes redirectAttributes) {

        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid event id: " + id));

        // Remove uploaded image files from disk; the event_images rows are removed
        // automatically through the cascade mapping when the event is deleted.
        if (event.getImages() != null) {
            for (EventImage image : event.getImages()) {
                try {
                    Path filePath = Paths.get(uploadDir).resolve(image.getImagePath()).normalize();
                    Files.deleteIfExists(filePath);
                } catch (IOException ex) {
                    // Keep going - the event itself must still be removed from the DB
                }
            }
        }

        eventRepository.delete(event);

        redirectAttributes.addFlashAttribute("message",
                "Event \"" + event.getTitle() + "\" deleted successfully.");

        String redirectUrl = "/admin/events";
        if (month != null) {
            redirectUrl += "?month=" + month;
            if (year != null) {
                redirectUrl += "&year=" + year;
            }
        } else if (year != null) {
            redirectUrl += "?year=" + year;
        }

        return "redirect:" + redirectUrl;
    }

    @GetMapping("/events/export")
    public ResponseEntity<byte[]> exportEventsToExcel(
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year) throws IOException {

        List<Event> events = eventRepository.searchByMonthAndYear(month, year);

        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Events");

            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);

            CellStyle bodyStyle = workbook.createCellStyle();
            bodyStyle.setWrapText(true);

            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(
                    workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));

            String[] headers = { "ID", "Event Type", "Title", "Organizing Department", "Event Date",
                    "Target Audience", "Description", "Contact Name", "Contact Phone",
                    "Google Drive Link", "Created By", "Images" };

            Row headerRow = sheet.createRow(0);
            for (int col = 0; col < headers.length; col++) {
                Cell cell = headerRow.createCell(col);
                cell.setCellValue(headers[col]);
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            for (Event event : events) {
                Row row = sheet.createRow(rowIndex++);

                row.createCell(0).setCellValue(event.getId() != null ? event.getId() : 0);
                row.createCell(1).setCellValue(orBlank(event.getEventType()));
                row.createCell(2).setCellValue(orBlank(event.getTitle()));
                row.createCell(3).setCellValue(orBlank(event.getOrganizer()));

                Cell dateCell = row.createCell(4);
                if (event.getEventDate() != null) {
                    dateCell.setCellValue(event.getEventDate());
                    dateCell.setCellStyle(dateStyle);
                }

                row.createCell(5).setCellValue(orBlank(event.getVenue()));

                Cell descriptionCell = row.createCell(6);
                descriptionCell.setCellValue(orBlank(event.getDescription()));
                descriptionCell.setCellStyle(bodyStyle);

                row.createCell(7).setCellValue(orBlank(event.getContactName()));
                row.createCell(8).setCellValue(orBlank(event.getContactPhone()));
                row.createCell(9).setCellValue(orBlank(event.getGoogleDriveLink()));
                row.createCell(10).setCellValue(orBlank(event.getCreatedByEmail()));
                row.createCell(11).setCellValue(event.getImages() != null ? event.getImages().size() : 0);
            }

            int[] columnWidths = { 6, 16, 32, 24, 14, 22, 50, 20, 16, 34, 28, 9 };
            for (int col = 0; col < columnWidths.length; col++) {
                sheet.setColumnWidth(col, columnWidths[col] * 256);
            }

            workbook.write(baos);

            String fileName;
            if (month != null && year != null) {
                fileName = "events_" + year + "_" + String.format("%02d", month) + ".xlsx";
            } else if (year != null) {
                fileName = "events_" + year + ".xlsx";
            } else if (month != null) {
                fileName = "events_month_" + String.format("%02d", month) + ".xlsx";
            } else {
                fileName = "events_all.xlsx";
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .contentType(MediaType
                            .parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(baos.toByteArray());
        }
    }

    private String orBlank(String value) {
        return value != null ? value : "";
    }

    @GetMapping("/admin-access-denied")
    public String adminAccessDenied() {
        return "admin-access-denied";
    }

    @GetMapping("/error")
    public String adminError() {
        return "admin-error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied() {
        // Non-admin users (e.g. editors) get the dedicated access-denied page
        return "redirect:/admin-access-denied";
    }

    @ExceptionHandler(Exception.class)
    public String handleAdminException(Exception exception) {
        return "admin-error";
    }
}