package com.eauction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication
public class EauctionApplication {

	public static void main(String[] args) {
		SpringApplication.run(EauctionApplication.class, args);
	}

}
