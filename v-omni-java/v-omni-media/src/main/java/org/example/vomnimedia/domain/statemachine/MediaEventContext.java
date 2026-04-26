package org.example.vomnimedia.domain.statemachine;

import lombok.Getter;
import lombok.ToString;

import java.util.HashMap;
import java.util.Map;

@ToString
public class MediaEventContext {
    @Getter
    private final Long id;

    private final Map<String, Object> attributes = new HashMap<>();

    public MediaEventContext(Long id) {
        this.id = id;
    }

    public MediaEventContext with(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    public String getString(String key) {
        return (String) attributes.get(key);
    }

}
