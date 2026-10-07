package com.ncbaloop.countryinfo.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CountryLookupRequest(
		@Schema(description = "Country name in any casing; normalised before lookup", example = "tanzania")
		@NotBlank(message = "name is required")
		@Size(max = 100, message = "name must be at most 100 characters")
		@Pattern(regexp = "^[\\p{L} .,'()\\-]+$", message = "name may only contain letters, spaces and . , ' ( ) -")
		String name) {
}
