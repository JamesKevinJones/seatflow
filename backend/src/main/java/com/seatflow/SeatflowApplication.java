package com.seatflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is enabled for the reservation expiry sweeper. Note that the
 * sweeper is a convenience, not a correctness mechanism - see
 * {@code ReservationExpirySweeper} and {@code docs/CONCURRENCY.md}.
 */
@SpringBootApplication
@EnableScheduling
public class SeatflowApplication {

	public static void main(String[] args) {
		SpringApplication.run(SeatflowApplication.class, args);
	}

}
