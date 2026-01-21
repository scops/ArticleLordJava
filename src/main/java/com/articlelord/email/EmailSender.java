package com.articlelord.email;

import com.articlelord.config.Settings;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class EmailSender {
    private final Settings settings;

    public EmailSender(Settings settings) {
        this.settings = settings;
    }

    public Map<String, Object> sendEmail(String toEmail,
                                         String subject,
                                         String body,
                                         String bodyHtml) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", settings.getSmtpHost());
            props.put("mail.smtp.port", String.valueOf(settings.getSmtpPort()));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(settings.getSmtpUsername(), settings.getSmtpPassword());
                }
            });

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(settings.getEmailFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
            message.setSubject(subject, StandardCharsets.UTF_8.name());

            MimeMultipart multipart = new MimeMultipart("alternative");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body, StandardCharsets.UTF_8.name());
            multipart.addBodyPart(textPart);

            if (bodyHtml != null && !bodyHtml.isBlank()) {
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(bodyHtml, "text/html; charset=UTF-8");
                multipart.addBodyPart(htmlPart);
            }

            message.setContent(multipart);

            Transport.send(message);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "Email sent successfully to " + toEmail);
            return result;
        } catch (MessagingException ex) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", "Failed to send email: " + ex.getMessage());
            return result;
        }
    }
}
