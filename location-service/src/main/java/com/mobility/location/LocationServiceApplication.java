package com.mobility.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class LocationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(LocationServiceApplication.class, args);
	}
}
