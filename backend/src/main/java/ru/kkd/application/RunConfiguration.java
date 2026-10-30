package ru.kkd.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.kkd.domain.metric.QualityMetricsCalculator;
import ru.kkd.domain.rule.DataQualityChecker;

@Configuration(proxyBeanMethods = false)
public class RunConfiguration {

    @Bean
    DataQualityChecker dataQualityChecker() {
        return new DataQualityChecker();
    }

    @Bean
    Clock runClock() {
        return Clock.systemUTC();
    }

    @Bean
    QualityMetricsCalculator qualityMetricsCalculator() {
        return new QualityMetricsCalculator();
    }
}
