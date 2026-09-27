package com.sportsbetting.settlement.messaging;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class JsonMessages {
    private final JsonMapper mapper;
    private final Validator validator;

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Cannot encode message", ex);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            T value = mapper.readValue(json, type);
            if (value == null || !validator.validate(value).isEmpty()) {
                throw new IllegalArgumentException("Invalid message fields");
            }
            return value;
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Malformed message JSON", ex);
        }
    }
}
