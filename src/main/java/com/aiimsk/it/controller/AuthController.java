package com.aiimsk.it.controller;

import com.aiimsk.it.model.AppUser;
import com.aiimsk.it.repository.UserRepository;
import com.aiimsk.it.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Only addresses in this domain may sign in. */
    private final String allowedEmailDomain;

    public AuthController(UserRepository userRepository,
                          EmailService emailService,
                          @Value("${app.allowed-email-domain}") String allowedEmailDomain) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.allowedEmailDomain = allowedEmailDomain.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns true only for addresses in the allowed domain. Comparison is
     * case-insensitive and ignores surrounding whitespace, so "USER@AIIMSKALYANI.EDU.IN "
     * is accepted while "user@gmail.com" is not.
     */
    private boolean isAllowedEmail(String email) {
        if (email == null) {
            return false;
        }
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        int at = normalised.lastIndexOf('@');
        if (at < 0 || at == normalised.length() - 1) {
            return false;
        }
        return normalised.substring(at + 1).equals(allowedEmailDomain);
    }

    /**
     * Builds the full address from the part the user typed before the @.
     * The domain always comes from configuration, never from user input.
     *
     * @return the full address, or null when the input cannot be a valid
     *         local part (empty, illegal characters, or a foreign domain
     *         was pasted in).
     */
    private String buildEmailFromLocalPart(String emailLocal) {
        if (emailLocal == null) {
            return null;
        }
        String local = emailLocal.trim().toLowerCase(Locale.ROOT);

        // A full address may have been pasted. Keep only the local part, and
        // only when the pasted domain is the permitted one.
        int at = local.indexOf('@');
        if (at >= 0) {
            if (!local.substring(at + 1).equals(allowedEmailDomain)) {
                return null;
            }
            local = local.substring(0, at);
        }

        if (local.isEmpty() || !local.matches("[a-z0-9._%+-]+")) {
            return null;
        }
        return local + "@" + allowedEmailDomain;
    }

    @GetMapping("/login")
    public String login(Model model) {
        System.out.println("GET /login called");
        model.addAttribute("allowedDomain", allowedEmailDomain);
        return "login";
    }

    @PostMapping("/request-otp")
    public String requestOtp(
            @RequestParam(value = "emailLocal", required = false) String emailLocal,
            @RequestParam(value = "email", required = false) String legacyEmail,
            HttpSession session,
            Model model) {

        System.out.println("POST /request-otp called with emailLocal = " + emailLocal);

        // The login form lets the user type only the part before the @; the
        // domain is appended here so it can never be overridden by the client.
        String email = (emailLocal != null && !emailLocal.isBlank())
                ? buildEmailFromLocalPart(emailLocal)
                : (legacyEmail != null ? legacyEmail.trim().toLowerCase(Locale.ROOT) : null);

        if (email == null) {
            System.out.println("Rejected unusable email local part: " + emailLocal);
            model.addAttribute("error",
                    "Enter the part of your email address before the @ symbol.");
            model.addAttribute("allowedDomain", allowedEmailDomain);
            return "login";
        }

        // Belt and braces: the composed address must still be in the allowed domain.
        if (!isAllowedEmail(email)) {
            System.out.println("Rejected email outside allowed domain: " + email);
            model.addAttribute("error",
                    "Only @" + allowedEmailDomain + " email addresses can sign in.");
            model.addAttribute("allowedDomain", allowedEmailDomain);
            return "login";
        }

        try {
            Optional<AppUser> optionalUser = userRepository.findByEmail(email);
            System.out.println("User present in DB = " + optionalUser.isPresent());

            AppUser user;
            if (optionalUser.isEmpty()) {
                System.out.println("User not found, creating new USER for email = " + email);
                user = new AppUser();
                user.setEmail(email);
                user.setRole("USER");
                userRepository.save(user);
                System.out.println("New user saved successfully for email = " + email);
            } else {
                user = optionalUser.get();
                System.out.println("Existing user found. Role = " + user.getRole());
            }

            String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
            System.out.println("Generated OTP = " + otp + " for email = " + email);

            session.setAttribute("OTP_" + email, otp);
            System.out.println("OTP stored in session with key = OTP_" + email);

            System.out.println("Calling emailService.sendOtp for " + email);
            emailService.sendOtp(email, otp);
            System.out.println("emailService.sendOtp completed for " + email);

            return "redirect:/verify-otp?email=" + email;

        } catch (Exception ex) {
            System.out.println("ERROR in requestOtp for email = " + email);
            ex.printStackTrace();
            model.addAttribute("error", "Failed to send OTP email.");
            model.addAttribute("allowedDomain", allowedEmailDomain);
            return "login";
        }
    }

    @GetMapping("/verify-otp")
    public String verifyOtpPage(@RequestParam String email, Model model) {
        System.out.println("GET /verify-otp called for email = " + email);
        model.addAttribute("email", email);
        return "verify-otp";
    }

    @PostMapping("/verify-otp")
    public String verifyOtp(@RequestParam String email,
            @RequestParam String otp,
            HttpSession session,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {

        System.out.println("POST /verify-otp called with email = " + email + ", otp = " + otp);

        // Defence in depth: a session created before this rule existed must
        // not still be able to complete sign-in with a now-disallowed address.
        if (!isAllowedEmail(email)) {
            System.out.println("Rejected verify-otp for outside domain: " + email);
            model.addAttribute("error",
                    "Only @" + allowedEmailDomain + " email addresses can sign in.");
            model.addAttribute("allowedDomain", allowedEmailDomain);
            return "login";
        }

        String sessionOtp = (String) session.getAttribute("OTP_" + email);
        System.out.println("Session OTP = " + sessionOtp);

        if (sessionOtp == null || !sessionOtp.equals(otp)) {
            System.out.println("OTP mismatch or missing for email = " + email);
            return "redirect:/verify-otp?email=" + email + "&error";
        }

        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found after OTP verification"));

        System.out.println("OTP verified. Logging in user = " + user.getEmail() + ", role = " + user.getRole());

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getEmail(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        HttpSessionSecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
        securityContextRepository.saveContext(context, request, response);

        session.removeAttribute("OTP_" + email);

        if ("ADMIN".equalsIgnoreCase(user.getRole()) || "EDITOR".equalsIgnoreCase(user.getRole())) {
            System.out.println("Redirecting ADMIN/EDITOR to dashboard");
            return "redirect:/admin/dashboard";
        }

        System.out.println("Redirecting USER to home");
        return "redirect:/";
    }

    @GetMapping("/access-denied")
    public String accessDenied() {
        System.out.println("GET /access-denied called");
        return "access-denied";
    }
}
