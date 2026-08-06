package com.aiimsk.it.controller;

import com.aiimsk.it.model.AppUser;
import com.aiimsk.it.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

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
    public String save(@ModelAttribute("userForm") AppUser userForm) {
        /*if (userForm.getPassword() != null && !userForm.getPassword().isBlank()) {
            userForm.setPassword(passwordEncoder.encode(userForm.getPassword()));
        } */

        userForm.setRole("ADMIN");
        userRepository.save(userForm);
        return "redirect:/admin/users";
    }

    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable Long id, Authentication authentication, Model model) {
        model.addAttribute("adminUser", authentication.getName());
        model.addAttribute("userForm", userRepository.findById(id).orElseThrow());
        return "admin/user-form";
    }

    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id) {
        userRepository.deleteById(id);
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