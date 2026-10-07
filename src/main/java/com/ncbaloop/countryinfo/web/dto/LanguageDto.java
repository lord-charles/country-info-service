package com.ncbaloop.countryinfo.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LanguageDto(
		@Schema(example = "swa") @NotBlank @Size(max = 10) String isoCode,
		@Schema(example = "Swahili") @NotBlank @Size(max = 100) String name) {
}
