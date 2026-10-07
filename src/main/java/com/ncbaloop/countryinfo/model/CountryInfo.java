package com.ncbaloop.countryinfo.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A country record sourced from the upstream FullCountryInfo operation. The ISO code is the
 * natural key: it is unique and immutable once stored, and is used to upsert on re-import.
 */
@Entity
@Table(name = "country_info")
public class CountryInfo {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "iso_code", nullable = false, unique = true, length = 2, updatable = false)
	private String isoCode;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "capital_city", length = 100)
	private String capitalCity;

	@Column(name = "phone_code", length = 10)
	private String phoneCode;

	@Column(name = "continent_code", length = 5)
	private String continentCode;

	@Column(name = "currency_iso_code", length = 5)
	private String currencyIsoCode;

	@Column(name = "country_flag", length = 255)
	private String countryFlag;

	@OneToMany(mappedBy = "country", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("name ASC")
	@BatchSize(size = 50)
	private List<Language> languages = new ArrayList<>();

	@Version
	private long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CountryInfo() {
		// for JPA
	}

	public CountryInfo(String isoCode) {
		this.isoCode = isoCode;
	}

	/**
	 * Replace the language set, matching existing rows by ISO code. Rows are updated in place
	 * rather than deleted and re-inserted: Hibernate flushes inserts before deletes, so a naive
	 * clear-and-add would violate the (country_id, iso_code) unique constraint.
	 */
	public void replaceLanguages(Collection<Language> incoming) {
		Map<String, Language> wanted = new LinkedHashMap<>();
		incoming.forEach(language -> wanted.put(language.getIsoCode(), language));

		this.languages.removeIf(existing -> !wanted.containsKey(existing.getIsoCode()));
		for (Language existing : this.languages) {
			existing.setName(wanted.remove(existing.getIsoCode()).getName());
		}
		wanted.values().forEach(this::addLanguage);
	}

	private void addLanguage(Language language) {
		language.setCountry(this);
		this.languages.add(language);
	}

	public Long getId() {
		return this.id;
	}

	public String getIsoCode() {
		return this.isoCode;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getCapitalCity() {
		return this.capitalCity;
	}

	public void setCapitalCity(String capitalCity) {
		this.capitalCity = capitalCity;
	}

	public String getPhoneCode() {
		return this.phoneCode;
	}

	public void setPhoneCode(String phoneCode) {
		this.phoneCode = phoneCode;
	}

	public String getContinentCode() {
		return this.continentCode;
	}

	public void setContinentCode(String continentCode) {
		this.continentCode = continentCode;
	}

	public String getCurrencyIsoCode() {
		return this.currencyIsoCode;
	}

	public void setCurrencyIsoCode(String currencyIsoCode) {
		this.currencyIsoCode = currencyIsoCode;
	}

	public String getCountryFlag() {
		return this.countryFlag;
	}

	public void setCountryFlag(String countryFlag) {
		this.countryFlag = countryFlag;
	}

	public List<Language> getLanguages() {
		return Collections.unmodifiableList(this.languages);
	}

	public long getVersion() {
		return this.version;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

}
