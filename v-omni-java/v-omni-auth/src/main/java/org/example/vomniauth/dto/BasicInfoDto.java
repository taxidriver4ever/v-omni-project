package org.example.vomniauth.dto;

import lombok.Data;

@Data
public class BasicInfoDto {
    private Long userId;
    private Integer sex;
    private Integer birthYear;
    private String country;
    private String province;
    private String city;
}
