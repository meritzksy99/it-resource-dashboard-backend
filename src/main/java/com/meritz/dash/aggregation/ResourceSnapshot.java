package com.meritz.dash.aggregation;

public record ResourceSnapshot(String periodYm, String unitType, String unitId,
        int headcount, int availHeadcount, double availMm, double usedMm, double overtimeMm) {}
