package com.verityai.service.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Properties;

/**
 * Thin SMTP wrapper. Mail is optional: when {@code MAIL_HOST} is empty the
 * service is a no-op, so development and tests work without an SMTP server and
 * password-reset requests still get the same generic response.
 */
@Service
@Slf4j
public class MailService {

    private final JavaMailSenderImpl sender;
    private final String from;
    private final long ttlMinutes;

    public MailService(
            @Value("${verityai.mail.host:}") String host,
            @Value("${verityai.mail.port:587}") int port,
            @Value("${verityai.mail.username:}") String username,
            @Value("${verityai.mail.password:}") String password,
            @Value("${verityai.mail.from:}") String from,
            @Value("${verityai.mail.starttls:true}") boolean starttls,
            @Value("${verityai.mail.ssl:false}") boolean ssl,
            @Value("${verityai.password-reset.token-ttl-minutes:30}") long ttlMinutes) {
        this.from = StringUtils.hasText(from) ? from : "no-reply@verityai.local";
        this.ttlMinutes = ttlMinutes;
        if (!StringUtils.hasText(host)) {
            this.sender = null;
            log.info("Mail is not configured (MAIL_HOST empty); password-reset emails will be skipped");
            return;
        }

        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(host);
        impl.setPort(port);
        impl.setDefaultEncoding("UTF-8");
        boolean auth = StringUtils.hasText(username);
        if (auth) {
            impl.setUsername(username);
            impl.setPassword(password);
        }
        Properties props = impl.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        this.sender = impl;
    }

    public boolean isConfigured() {
        return sender != null;
    }

    /** Sends the reset link. Never throws to the caller; failures are logged. */
    public void sendPasswordReset(String to, String link) {
        if (sender == null) {
            log.warn("Password reset email skipped (mail not configured) for {}", to);
            return;
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject("Reset your VerityAI password");
            helper.setText("""
                Someone requested a password reset for your VerityAI account.

                Open this link within %d minutes to choose a new password:
                %s

                If you did not request this, you can ignore this email — your
                password will not change.
                """.formatted(ttlMinutes, link), false);
            sender.send(message);
            log.info("Sent password reset email to {}", to);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}", to, e);
        }
    }
}
