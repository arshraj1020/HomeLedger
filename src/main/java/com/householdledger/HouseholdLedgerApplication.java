package com.householdledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. {@code @EnableScheduling} is required from phase 6 onward for
 * the daily trial-balance check (PRD §FR-6); enabled here in phase 0 so it
 * does not need to be revisited later.
 */
@SpringBootApplication
@EnableScheduling
public class HouseholdLedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(HouseholdLedgerApplication.class, args);
    }
}
