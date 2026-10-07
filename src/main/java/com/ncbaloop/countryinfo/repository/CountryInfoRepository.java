package com.ncbaloop.countryinfo.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ncbaloop.countryinfo.model.CountryInfo;

public interface CountryInfoRepository extends JpaRepository<CountryInfo, Long> {

	Optional<CountryInfo> findByIsoCode(String isoCode);

	Optional<CountryInfo> findFirstByNameIgnoreCase(String name);

}
