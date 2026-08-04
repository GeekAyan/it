package com.aiimsk.it.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void sendOtp(String toEmail, String otp) {
        log.info("sendOtp started for {}", toEmail);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(toEmail);
        message.setSubject("Your OTP for AIIMS Kalyani Event Portal");
        message.setText("Your OTP is: " + otp + "\nThis OTP will expire soon.");

        log.info("About to call mailSender.send for {}", toEmail);
        mailSender.send(message);
        log.info("mailSender.send completed for {}", toEmail);
    }
}