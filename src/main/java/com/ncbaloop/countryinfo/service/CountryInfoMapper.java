package com.ncbaloop.countryinfo.service;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ncbaloop.countryinfo.integration.CountryDetails;
import com.ncbaloop.countryinfo.model.CountryInfo;
import com.ncbaloop.countryinfo.model.Language;
import com.ncbaloop.countryinfo.web.dto.CountryInfoResponse;
import com.ncbaloop.countryinfo.web.dto.CountryUpdateRequest;
import com.ncbaloop.countryinfo.web.dto.LanguageDto;

/**
 * Maps between upstream details, JPA entities and API representations. Entities never leave
 * the service layer, so lazy-loading and persistence concerns cannot leak into JSON.
 */
@Component
public class CountryInfoMapper {

	public CountryInfoResponse toResponse(CountryInfo entity) {
		List<LanguageDto> languages = entity.getLanguages()
			.stream()
			.map(language -> new LanguageDto(language.getIsoCode(), language.getName()))
			.toList();
		return new CountryInfoResponse(entity.getId(), entity.getIsoCode(), entity.getName(), entity.getCapitalCity(),
				entity.getPhoneCode(), entity.getContinentCode(), entity.getCurrencyIsoCode(), entity.getCountryFlag(),
				languages, entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt());
	}

	public void apply(CountryDetails details, CountryInfo entity) {
		entity.setName(details.name());
		entity.setCapitalCity(details.capitalCity());
		entity.setPhoneCode(details.phoneCode());
		entity.setContinentCode(details.continentCode());
		entity.setCurrencyIsoCode(details.currencyIsoCode());
		entity.setCountryFlag(details.countryFlag());
		entity.replaceLanguages(details.languages()
			.stream()
			.map(language -> new Language(language.isoCode(), language.name()))
			.toList());
	}

	public void apply(CountryUpdateRequest request, CountryInfo entity) {
		entity.setName(request.name().trim());
		entity.setCapitalCity(request.capitalCity());
		entity.setPhoneCode(request.phoneCode());
		entity.setContinentCode(request.continentCode());
		entity.setCurrencyIsoCode(request.currencyIsoCode());
		entity.setCountryFlag(request.countryFlag());
		entity.replaceLanguages(request.languages()
			.stream()
			.map(language -> new Language(language.isoCode().trim(), language.name().trim()))
			.toList());
	}

}
