package org.example.vomniauth.domain.statemachine;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.function.Consumer;

@Data
@Builder
public class AuthRule implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private AuthState from;
    private AuthEvent on;
    private AuthState to;

    private transient Consumer<AuthEventContext> action;
}
