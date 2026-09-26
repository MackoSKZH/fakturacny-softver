package com.fakturacnysoftver.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
class ClockConfig {

    /** Datumy na faktury vzdy podla slovenskeho casu, nie podla casovej zony servera. */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Europe/Bratislava"));
    }
}
