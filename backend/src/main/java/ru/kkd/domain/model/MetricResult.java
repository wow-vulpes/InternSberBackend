package ru.kkd.domain.model;

import java.math.BigDecimal;

public record MetricResult(
        MetricCode code,
        BigDecimal current,
        BigDecimal previous,
        BigDecimal delta,
        MetricTrend trend,
        Severity severity) {}
