package com.ncbaloop.countryinfo.exception;

/**
 * A stored resource with the requested identifier does not exist.
 */
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String resource, Object id) {
		super(resource + " with id " + id + " was not found");
	}

}
