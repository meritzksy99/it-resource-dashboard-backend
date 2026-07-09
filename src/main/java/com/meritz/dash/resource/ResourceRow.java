package com.meritz.dash.resource;

public record ResourceRow(String periodYm, String unitType, String unitId,
        int headcount, int availHeadcount, double availMm, double usedMm, double overtimeMm) {}
