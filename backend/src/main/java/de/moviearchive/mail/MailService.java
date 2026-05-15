package de.moviearchive.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Service
@Slf4j
public class MailService {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;

    @Value("${spring.mail.from}")
    private String fromAddress;

    @Value("${app.base-url}")
    private String baseUrl;

    public MailService(JavaMailSender mailSender, SpringTemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
    }

    public void sendVerificationEmail(String to, String rawToken) {
        Context ctx = new Context();
        ctx.setVariable("verificationUrl", baseUrl + "/verify-email?token=" + rawToken);

        String htmlContent = templateEngine.process("mail/welcome-verify", ctx);

        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("Verify your MovieArchive email");
            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Verification email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send verification email", e);
        }
    }

    public void sendPasswordResetEmail(String to, String rawToken) {
        Context ctx = new Context();
        ctx.setVariable("resetUrl", baseUrl + "/reset-password?token=" + rawToken);

        String htmlContent = templateEngine.process("mail/password-reset", ctx);

        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject("Reset your MovieArchive password");
            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Password reset email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send password reset email", e);
        }
    }
}
