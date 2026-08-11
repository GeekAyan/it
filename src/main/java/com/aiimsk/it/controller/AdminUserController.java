package com.aiimsk.it.controller;

import com.aiimsk.it.model.AppUser;
import com.aiimsk.it.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

@Controller
@RequestMapping("/admin/users")
public class AdminUserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public String list(Authentication authentication, Model model) {
        model.addAttribute("adminUser", authentication.getName());
        model.addAttribute("users", userRepository.findAll());
        return "admin/users";
    }

    @GetMapping("/new")
    public String addForm(Authentication authentication, Model model) {
        model.addAttribute("adminUser", authentication.getName());
        model.addAttribute("userForm", new AppUser());
        return "admin/user-form";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute("userForm") AppUser userForm,
            RedirectAttributes redirectAttributes) {

        String email = userForm.getEmail() != null ? userForm.getEmail().trim().toLowerCase() : "";

        if (email.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Email is required.");
            return "redirect:/admin/users/new";
        }

        Optional<AppUser> existing = userRepository.findByEmail(email);

        if (userForm.getId() != null) {
            // Editing an existing admin
            AppUser existingUser = userRepository.findById(userForm.getId()).orElse(null);
            if (existingUser == null) {
                redirectAttributes.addFlashAttribute("error", "Admin user not found.");
                return "redirect:/admin/users";
            }
            existingUser.setEmail(email);
            existingUser.setRole("ADMIN");
            userRepository.save(existingUser);
            redirectAttributes.addFlashAttribute("message", "Admin updated successfully.");
        } else {
            // Adding a new admin
            if (existing.isPresent()) {
                // Promote existing user (USER) to ADMIN
                AppUser user = existing.get();
                user.setRole("ADMIN");
                userRepository.save(user);
                redirectAttributes.addFlashAttribute("message", "User promoted to ADMIN successfully.");
            } else {
                AppUser newAdmin = new AppUser();
                newAdmin.setEmail(email);
                newAdmin.setRole("ADMIN");
                userRepository.save(newAdmin);
                redirectAttributes.addFlashAttribute("message", "Admin added successfully.");
            }
        }

        return "redirect:/admin/users";
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable Long id, Authentication authentication, Model model) {
        model.addAttribute("adminUser", authentication.getName());
        model.addAttribute("userForm", userRepository.findById(id).orElseThrow());
        return "admin/user-form";
    }

    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id,
            Authentication authentication,
            RedirectAttributes redirectAttributes) {

        AppUser currentUser = userRepository.findByEmail(authentication.getName()).orElse(null);

        if (currentUser != null && currentUser.getId().equals(id)) {
            redirectAttributes.addFlashAttribute("error", "You cannot delete your own admin account.");
            return "redirect:/admin/users";
        }

        userRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("message", "Admin deleted successfully.");
        return "redirect:/admin/users";
    }

    @GetMapping("/admin-access-denied")
    public String adminAccessDenied() {
        return "admin-access-denied";
    }

    @GetMapping("/error")
    public String adminError() {
        return "admin-error";
    }

    @ExceptionHandler(Exception.class)
    public String handleAdminException(Exception exception) {
        return "admin-error";
    }
}