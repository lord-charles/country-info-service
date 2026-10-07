package com.ncbaloop.countryinfo.exception;

import java.util.Set;

/**
 * The client asked to sort by a property that is not sortable.
 */
public class InvalidSortException extends RuntimeException {

	public InvalidSortException(String property, Set<String> allowed) {
		super("Cannot sort by '" + property + "'. Allowed: " + String.join(", ", allowed.stream().sorted().toList()));
	}

}
