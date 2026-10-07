package com.ncbaloop.countryinfo.web.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public record CountryInfoResponse(
		@Schema(example = "1") Long id,
		@Schema(example = "TZ") String isoCode,
		@Schema(example = "Tanzania") String name,
		@Schema(example = "Dar es Salaam") String capitalCity,
		@Schema(example = "255") String phoneCode,
		@Schema(example = "AF") String continentCode,
		@Schema(example = "TZS") String currencyIsoCode,
		@Schema(example = "http://www.oorsprong.org/WebSamples.CountryInfo/Flags/Tanzania.jpg") String countryFlag,
		List<LanguageDto> languages,
		@Schema(description = "Optimistic-lock version, incremented on every update") long version,
		Instant createdAt,
		Instant updatedAt) {
}
