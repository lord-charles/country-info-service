package com.ncbaloop.countryinfo.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised settings for the upstream CountryInfo SOAP service. Every value can be
 * overridden per environment (ConfigMap / env vars) without rebuilding the image.
 */
@Validated
@ConfigurationProperties(prefix = "countryinfo.soap")
public record SoapClientProperties(
		@NotBlank String endpoint,
		@NotNull Duration connectTimeout,
		@NotNull Duration readTimeout,
		@Min(1) int maxConnections) {
}
