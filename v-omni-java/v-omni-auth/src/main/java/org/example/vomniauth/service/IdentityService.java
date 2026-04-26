package org.example.vomniauth.service;

public interface IdentityService {
    Long getOrCreateUserIdByEmail(String email);
    Long getIdByEmail(String email);
}
