package com.ncbaloop.countryinfo.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Enables caching with the highest advice precedence so a cache hit short-circuits the
 * Resilience4j retry / circuit-breaker / bulkhead aspects. Without this, an open circuit would
 * reject even lookups we could answer from memory.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CacheConfig {

}
