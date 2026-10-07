package com.ncbaloop.countryinfo.service;

import java.util.Optional;
import java.util.Set;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ncbaloop.countryinfo.exception.CountryNotFoundException;
import com.ncbaloop.countryinfo.exception.ResourceNotFoundException;
import com.ncbaloop.countryinfo.exception.UpstreamServiceException;
import com.ncbaloop.countryinfo.integration.CountryDetails;
import com.ncbaloop.countryinfo.integration.CountryInfoClient;
import com.ncbaloop.countryinfo.model.CountryInfo;
import com.ncbaloop.countryinfo.repository.CountryInfoRepository;
import com.ncbaloop.countryinfo.service.ImportResult.Outcome;
import com.ncbaloop.countryinfo.web.dto.CountryInfoResponse;
import com.ncbaloop.countryinfo.web.dto.CountryUpdateRequest;
import com.ncbaloop.countryinfo.web.dto.PageResponse;

/**
 * Business logic for importing countries from the upstream provider and managing the stored
 * copies.
 *
 * <p>Upstream SOAP calls are made <em>outside</em> any database transaction, so a slow provider
 * never holds a pooled connection or row locks. Only the final upsert runs transactionally.
 */
@Service
public class CountryInfoService {

	private static final Logger log = LoggerFactory.getLogger(CountryInfoService.class);

	private static final String RESOURCE = "Country";

	private final CountryInfoClient countryInfoClient;

	private final CountryInfoRepository repository;

	private final CountryInfoMapper mapper;

	private final TransactionTemplate transactionTemplate;

	private final MeterRegistry meterRegistry;

	public CountryInfoService(CountryInfoClient countryInfoClient, CountryInfoRepository repository,
			CountryInfoMapper mapper, TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
		this.countryInfoClient = countryInfoClient;
		this.repository = repository;
		this.mapper = mapper;
		this.transactionTemplate = transactionTemplate;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Steps 3-5 of the brief: normalise the name, resolve it to an ISO code, fetch the full
	 * country info and store it. Re-importing an existing country refreshes it (idempotent).
	 * If the provider is down but we already hold the country, the stored copy is returned.
	 */
	public ImportResult importCountry(String rawName) {
		Set<String> candidates = CountryNameFormatter.lookupCandidates(rawName);
		try {
			String isoCode = resolveIsoCode(candidates)
				.orElseThrow(() -> new CountryNotFoundException(CountryNameFormatter.toSentenceCase(rawName)));
			CountryDetails details = this.countryInfoClient.findFullCountryInfo(isoCode)
				.orElseThrow(() -> new CountryNotFoundException(isoCode));
			ImportResult result = upsert(details);
			record(result.outcome().name());
			log.atInfo()
				.addKeyValue("isoCode", isoCode)
				.addKeyValue("countryId", result.country().id())
				.addKeyValue("outcome", result.outcome())
				.log("Country imported");
			return result;
		}
		catch (CountryNotFoundException ex) {
			record("NOT_FOUND");
			throw ex;
		}
		catch (UpstreamServiceException ex) {
			Optional<ImportResult> stale = findStoredByName(candidates);
			if (stale.isPresent()) {
				record(Outcome.SERVED_STALE.name());
				log.atWarn()
					.addKeyValue("isoCode", stale.get().country().isoCode())
					.addKeyValue("reason", ex.getReason())
					.log("Upstream unavailable; serving stored copy");
				return stale.get();
			}
			record("UPSTREAM_" + ex.getReason().name());
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public PageResponse<CountryInfoResponse> findAll(Pageable pageable) {
		return PageResponse.from(this.repository.findAll(pageable).map(this.mapper::toResponse));
	}

	@Transactional(readOnly = true)
	public CountryInfoResponse findById(Long id) {
		return this.mapper.toResponse(getOrThrow(id));
	}

	@Transactional
	public CountryInfoResponse update(Long id, CountryUpdateRequest request) {
		CountryInfo entity = getOrThrow(id);
		this.mapper.apply(request, entity);
		CountryInfo saved = this.repository.saveAndFlush(entity);
		log.atInfo().addKeyValue("countryId", id).addKeyValue("isoCode", saved.getIsoCode()).log("Country updated");
		return this.mapper.toResponse(saved);
	}

	@Transactional
	public void delete(Long id) {
		CountryInfo entity = getOrThrow(id);
		this.repository.delete(entity);
		log.atInfo().addKeyValue("countryId", id).addKeyValue("isoCode", entity.getIsoCode()).log("Country deleted");
	}

	private Optional<String> resolveIsoCode(Set<String> candidates) {
		for (String candidate : candidates) {
			Optional<String> isoCode = this.countryInfoClient.findIsoCode(candidate);
			if (isoCode.isPresent()) {
				return isoCode;
			}
		}
		return Optional.empty();
	}

	private ImportResult upsert(CountryDetails details) {
		try {
			return this.transactionTemplate.execute(status -> doUpsert(details));
		}
		catch (DataIntegrityViolationException ex) {
			// Two replicas imported the same new country concurrently and the other one won the
			// unique-key race. Retry once: the row now exists, so this becomes an update.
			log.atInfo().addKeyValue("isoCode", details.isoCode()).log("Concurrent insert detected; retrying as update");
			return this.transactionTemplate.execute(status -> doUpsert(details));
		}
	}

	private ImportResult doUpsert(CountryDetails details) {
		Optional<CountryInfo> existing = this.repository.findByIsoCode(details.isoCode());
		CountryInfo entity = existing.orElseGet(() -> new CountryInfo(details.isoCode()));
		this.mapper.apply(details, entity);
		CountryInfo saved = this.repository.saveAndFlush(entity);
		return new ImportResult(this.mapper.toResponse(saved), existing.isPresent() ? Outcome.UPDATED : Outcome.CREATED);
	}

	private Optional<ImportResult> findStoredByName(Set<String> candidates) {
		return this.transactionTemplate.execute(status -> candidates.stream()
			.map(this.repository::findFirstByNameIgnoreCase)
			.flatMap(Optional::stream)
			.findFirst()
			.map(entity -> new ImportResult(this.mapper.toResponse(entity), Outcome.SERVED_STALE)));
	}

	private CountryInfo getOrThrow(Long id) {
		return this.repository.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
	}

	private void record(String outcome) {
		Counter.builder("countryinfo.imports")
			.description("Country import requests by outcome")
			.tag("outcome", outcome)
			.register(this.meterRegistry)
			.increment();
	}

}
