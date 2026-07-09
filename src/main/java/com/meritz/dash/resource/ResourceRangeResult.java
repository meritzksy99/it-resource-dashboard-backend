package com.meritz.dash.resource;

import java.util.List;

public record ResourceRangeResult(
        List<ResourceView> items,
        String from,
        String to,
        String unitType,
        String unitId
) {}
