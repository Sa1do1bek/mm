package com.enterprise.mm.payload;

import java.util.List;

public record MXIKSearchResponse(
        Long id,
        String kod,
        String name,
        List<MXIKPathResponse> path
) {}
