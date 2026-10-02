package com.seatreserve;

import com.seatreserve.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class SeatReserveApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeatReserveApplication.class, args);
    }
}
