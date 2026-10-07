package com.ncbaloop.countryinfo.web.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Full replacement of a country's mutable fields. The ISO code is the natural key and cannot
 * be changed; delete and re-import instead.
 */
public record CountryUpdateRequest(
		@Schema(example = "Tanzania") @NotBlank @Size(max = 100) String name,
		@Schema(example = "Dodoma") @Size(max = 100) String capitalCity,
		@Schema(example = "255") @Size(max = 10) String phoneCode,
		@Schema(example = "AF") @Size(max = 5) String continentCode,
		@Schema(example = "TZS") @Size(max = 5) String currencyIsoCode,
		@Size(max = 255) String countryFlag,
		@NotNull @Size(max = 50) List<@Valid @NotNull LanguageDto> languages) {
}
