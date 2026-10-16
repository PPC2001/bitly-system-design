package com.bitly.clone.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ShortenUrlRequest(

        @NotBlank(message = "URL must not be blank")
        @Pattern(
                regexp = "^(https?://)[\\w.-]+(\\.[\\w.-]+)+[/#?]?.*$",
                message = "Invalid URL format"
        )
        String originalUrl,

        @Pattern(regexp = "^[a-zA-Z0-9_-]{3,20}$", message = "Alias must be 3-20 alphanumeric chars")
        String customAlias,

        Long expiresInDays
) {

}