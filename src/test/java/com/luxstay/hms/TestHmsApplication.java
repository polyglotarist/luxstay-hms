package com.luxstay.hms;

import org.springframework.boot.SpringApplication;

public class TestHmsApplication {

	public static void main(String[] args) {
		SpringApplication.from(HmsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
