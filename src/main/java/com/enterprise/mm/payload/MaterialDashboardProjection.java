package com.enterprise.mm.payload;

import java.math.BigDecimal;

public interface MaterialDashboardProjection {

    Long getTotalMaterials();

    Long getLowStock();

    Long getOutOfStock();

    BigDecimal getValue();
}
