package com.enterprise.mm.payload;

import com.enterprise.mm.common.search.SearchCriteria;

public record UserFeedbackSearchRequest() implements SearchCriteria {
    public static UserFeedbackSearchRequest empty() {
        return new UserFeedbackSearchRequest();
    }
}
