package org.example.vomniauth.domain.statemachine;

import lombok.Getter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ToString
public class AuthEventContext {
    @Getter
    private final Long id;

    private final Map<String, Object> attributes = new HashMap<>();

    public AuthEventContext(Long id) {
        this.id = id;
    }

    public AuthEventContext with(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    public String getString(String key) {
        return (String) attributes.get(key);
    }

}
