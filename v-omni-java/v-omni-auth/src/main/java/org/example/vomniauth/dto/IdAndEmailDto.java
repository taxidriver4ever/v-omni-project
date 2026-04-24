package org.example.vomniauth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.checkerframework.checker.units.qual.A;

import java.io.Serial;
import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class IdAndEmailDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String email;
}
