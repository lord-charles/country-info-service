package com.ncbaloop.countryinfo.web.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable pagination envelope. Spring's {@code Page} is not serialised directly because its
 * JSON shape is an implementation detail that may change between versions.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
