package com.ncbaloop.countryinfo.web.controller;

import java.net.URI;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.ncbaloop.countryinfo.exception.InvalidSortException;
import com.ncbaloop.countryinfo.service.CountryInfoService;
import com.ncbaloop.countryinfo.service.ImportResult;
import com.ncbaloop.countryinfo.web.dto.CountryInfoResponse;
import com.ncbaloop.countryinfo.web.dto.CountryLookupRequest;
import com.ncbaloop.countryinfo.web.dto.CountryUpdateRequest;
import com.ncbaloop.countryinfo.web.dto.PageResponse;

/**
 * REST entry point. Kept thin: validates input, delegates to the service and maps outcomes
 * to HTTP semantics. All error responses are produced by the global exception handler.
 */
@RestController
@Validated
@RequestMapping("/api/v1/countries")
@Tag(name = "Countries", description = "Import countries from the CountryInfo SOAP service and manage stored records")
public class CountryController {

	/** Response header telling clients the body came from our store because upstream was down. */
	public static final String DATA_SOURCE_HEADER = "X-Data-Source";

	/** Properties clients may sort by; anything else is rejected with 400 before reaching JPA. */
	static final Set<String> SORTABLE_PROPERTIES = Set.of("id", "isoCode", "name", "capitalCity", "continentCode",
			"currencyIsoCode", "createdAt", "updatedAt");

	private final CountryInfoService countryInfoService;

	public CountryController(CountryInfoService countryInfoService) {
		this.countryInfoService = countryInfoService;
	}

	@PostMapping
	@Operation(summary = "Import a country by name",
			description = "Normalises the name, resolves its ISO code via SOAP, fetches full country info and stores it. "
					+ "Re-importing an existing country refreshes it.")
	@ApiResponse(responseCode = "201", description = "Country imported and stored")
	@ApiResponse(responseCode = "200", description = "Country already stored; refreshed (or served from store if upstream is down)")
	@ApiResponse(responseCode = "400", description = "Invalid request body")
	@ApiResponse(responseCode = "404", description = "Upstream does not recognise the country")
	@ApiResponse(responseCode = "503", description = "Upstream unavailable and no stored copy exists")
	public ResponseEntity<CountryInfoResponse> importCountry(@Valid @RequestBody CountryLookupRequest request) {
		ImportResult result = this.countryInfoService.importCountry(request.name());
		CountryInfoResponse body = result.country();
		return switch (result.outcome()) {
			case CREATED -> ResponseEntity.created(locationOf(body.id())).body(body);
			case UPDATED -> ResponseEntity.ok().header(DATA_SOURCE_HEADER, "upstream").body(body);
			case SERVED_STALE -> ResponseEntity.ok().header(DATA_SOURCE_HEADER, "local-store").body(body);
		};
	}

	@GetMapping
	@Operation(summary = "List stored countries (paginated)",
			description = "Query params: page (0-based), size (max 100), sort=property,asc|desc. Sortable: "
					+ "id, isoCode, name, capitalCity, continentCode, currencyIsoCode, createdAt, updatedAt.")
	@ApiResponse(responseCode = "400", description = "Invalid sort property")
	public PageResponse<CountryInfoResponse> findAll(
			@ParameterObject @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
		pageable.getSort().forEach(order -> {
			if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
				throw new InvalidSortException(order.getProperty(), SORTABLE_PROPERTIES);
			}
		});
		return this.countryInfoService.findAll(pageable);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get a stored country by id")
	@ApiResponse(responseCode = "404", description = "No country with that id")
	public CountryInfoResponse findById(@PathVariable @Positive Long id) {
		return this.countryInfoService.findById(id);
	}

	@PutMapping("/{id}")
	@Operation(summary = "Replace a stored country's details",
			description = "The ISO code is the natural key and cannot be changed.")
	@ApiResponse(responseCode = "404", description = "No country with that id")
	@ApiResponse(responseCode = "409", description = "Concurrent modification")
	public CountryInfoResponse update(@PathVariable @Positive Long id, @Valid @RequestBody CountryUpdateRequest request) {
		return this.countryInfoService.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete a stored country and its languages")
	@ApiResponse(responseCode = "204", description = "Deleted")
	@ApiResponse(responseCode = "404", description = "No country with that id")
	public void delete(@PathVariable @Positive Long id) {
		this.countryInfoService.delete(id);
	}

	private static URI locationOf(Long id) {
		return ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(id).toUri();
	}

}
