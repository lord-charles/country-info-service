package com.ncbaloop.countryinfo.web;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ncbaloop.countryinfo.exception.CountryNotFoundException;
import com.ncbaloop.countryinfo.exception.ResourceNotFoundException;
import com.ncbaloop.countryinfo.exception.UpstreamServiceException;
import com.ncbaloop.countryinfo.exception.UpstreamServiceException.Reason;
import com.ncbaloop.countryinfo.service.CountryInfoService;
import com.ncbaloop.countryinfo.service.ImportResult;
import com.ncbaloop.countryinfo.service.ImportResult.Outcome;
import com.ncbaloop.countryinfo.web.controller.CountryController;
import com.ncbaloop.countryinfo.web.dto.CountryInfoResponse;
import com.ncbaloop.countryinfo.web.dto.LanguageDto;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CountryController.class)
class CountryControllerTest {

	private static final CountryInfoResponse TANZANIA = new CountryInfoResponse(1L, "TZ", "Tanzania", "Dar es Salaam",
			"255", "AF", "TZS", "http://flags/Tanzania.jpg", List.of(new LanguageDto("swa", "Swahili")), 0,
			Instant.parse("2026-10-07T10:00:00Z"), Instant.parse("2026-10-07T10:00:00Z"));

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CountryInfoService service;

	@Test
	void importReturns201WithLocationForNewCountry() throws Exception {
		given(this.service.importCountry("tanzania")).willReturn(new ImportResult(TANZANIA, Outcome.CREATED));

		this.mockMvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "tanzania"}"""))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", "http://localhost/api/v1/countries/1"))
			.andExpect(header().exists("X-Request-ID"))
			.andExpect(jsonPath("$.isoCode").value("TZ"))
			.andExpect(jsonPath("$.languages[0].name").value("Swahili"));
	}

	@Test
	void importReturns200AndDataSourceHeaderWhenServedFromStore() throws Exception {
		given(this.service.importCountry(anyString())).willReturn(new ImportResult(TANZANIA, Outcome.SERVED_STALE));

		this.mockMvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "tanzania"}"""))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Data-Source", "local-store"));
	}

	@Test
	void importRejectsBlankName() throws Exception {
		this.mockMvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "  "}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("urn:problem-type:country-info:validation-error"))
			.andExpect(jsonPath("$.errors[0].field").value("name"))
			.andExpect(jsonPath("$.requestId").exists());
	}

	@Test
	void importRejectsMalformedJson() throws Exception {
		this.mockMvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content("{name:"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void importReturns404ForUnknownCountry() throws Exception {
		given(this.service.importCountry(anyString())).willThrow(new CountryNotFoundException("Narnia"));

		this.mockMvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "narnia"}"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.title").value("Country not found"))
			.andExpect(jsonPath("$.detail").value("No country found matching 'Narnia'"));
	}

	@Test
	void importReturns503WithRetryAfterWhenUpstreamIsDown() throws Exception {
		given(this.service.importCountry(anyString()))
			.willThrow(new UpstreamServiceException(Reason.UNAVAILABLE, "CountryISOCode", "Provider unavailable", null));

		this.mockMvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "kenya"}"""))
			.andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "30"))
			.andExpect(jsonPath("$.operation").value("CountryISOCode"));
	}

	@Test
	void importReturns504WhenUpstreamTimesOut() throws Exception {
		given(this.service.importCountry(anyString()))
			.willThrow(new UpstreamServiceException(Reason.TIMEOUT, "FullCountryInfo", "Too slow", null));

		this.mockMvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "kenya"}"""))
			.andExpect(status().isGatewayTimeout());
	}

	@Test
	void getByIdReturns404ProblemWhenMissing() throws Exception {
		given(this.service.findById(99L)).willThrow(new ResourceNotFoundException("Country", 99L));

		this.mockMvc.perform(get("/api/v1/countries/99"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.detail").value("Country with id 99 was not found"));
	}

	@Test
	void getByIdRejectsNonPositiveId() throws Exception {
		this.mockMvc.perform(get("/api/v1/countries/0")).andExpect(status().isBadRequest());
	}

	@Test
	void updateValidatesBody() throws Exception {
		this.mockMvc.perform(put("/api/v1/countries/1").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "", "languages": null}"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.length()").value(2));
	}

	@Test
	void updateReturnsUpdatedCountry() throws Exception {
		given(this.service.update(eq(1L), any())).willReturn(TANZANIA);

		this.mockMvc.perform(put("/api/v1/countries/1").contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Tanzania", "capitalCity": "Dodoma", "languages": [{"isoCode": "swa", "name": "Swahili"}]}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(1));
	}

	@Test
	void listRejectsUnknownSortPropertyWith400() throws Exception {
		// Swagger UI's placeholder value used to reach JPA and surface as a 500.
		this.mockMvc.perform(get("/api/v1/countries").param("sort", "[\"string\"]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.type").value("urn:problem-type:country-info:invalid-sort"));
	}

	@Test
	void deleteReturns204() throws Exception {
		this.mockMvc.perform(delete("/api/v1/countries/1")).andExpect(status().isNoContent());
	}

	@Test
	void deleteReturns404WhenMissing() throws Exception {
		willThrow(new ResourceNotFoundException("Country", 5L)).given(this.service).delete(5L);

		this.mockMvc.perform(delete("/api/v1/countries/5")).andExpect(status().isNotFound());
	}

	@Test
	void echoesSafeIncomingRequestId() throws Exception {
		given(this.service.findById(1L)).willReturn(TANZANIA);

		this.mockMvc.perform(get("/api/v1/countries/1").header("X-Request-ID", "abc-123"))
			.andExpect(header().string("X-Request-ID", "abc-123"));
	}

}
