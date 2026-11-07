package com.giveago.reschedulefairy;

import com.giveago.reschedulefairy.ics.IcsCalendarService;
import com.giveago.reschedulefairy.schedule.RescheduleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RescheduleConfig {

    @Bean
    RescheduleService rescheduleService() {
        return new RescheduleService();
    }

    @Bean
    IcsCalendarService icsCalendarService() {
        return new IcsCalendarService();
    }
}
