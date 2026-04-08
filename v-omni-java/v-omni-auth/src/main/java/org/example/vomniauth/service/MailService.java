package org.example.vomniauth.service;

import jakarta.mail.MessagingException;

public interface MailService {
    void sendHtmlMail(String to, String subject, String content) throws MessagingException;
}
