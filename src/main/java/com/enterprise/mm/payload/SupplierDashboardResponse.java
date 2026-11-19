package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.util.List;

public record SupplierDashboardResponse(SupplierSummary summary,
                                        List<LabelCountDto> byCategory,
                                        List<LabelCountDto> byStatus,
                                        List<LabelCountDto> byRelationshipType,
                                        List<LabelCountDto> bySupplierType,
                                        List<LabelCountDto> byCountry,
                                        BigDecimal averageRating,
                                        List<SupplierShortDto> topRated,
                                        List<SupplierShortDto> recentlyAdded,
                                        List<SupplierAlertDto> openFirst) {
}
