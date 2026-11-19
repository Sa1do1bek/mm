package com.enterprise.mm.payload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssueRequestApproveRequest {
    private String comment;
    @NotNull
    private Long expectedVersion;

    @Valid
    private List<Line> lines;

    @Getter
    @Setter
    public static class Line {
        private UUID issueRequestLineId;
        private BigDecimal approvedQuantity;
    }
}
