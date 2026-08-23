package com.seatflow;

import org.springframework.boot.SpringApplication;

public class TestSeatflowApplication {

	public static void main(String[] args) {
		SpringApplication.from(SeatflowApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
