package com.ncbaloop.countryinfo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CountryInfoServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CountryInfoServiceApplication.class, args);
	}

}
