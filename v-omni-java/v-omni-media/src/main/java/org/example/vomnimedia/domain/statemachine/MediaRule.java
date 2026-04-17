package org.example.vomnimedia.domain.statemachine;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.function.Consumer;

@Data
@Builder
public class MediaRule implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private MediaState from;
    private MediaEvent on;
    private MediaState to;

    private transient Consumer<MediaEventContext> action;
}
