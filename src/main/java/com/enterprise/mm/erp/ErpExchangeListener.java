package com.enterprise.mm.erp;

import com.enterprise.mm.warehouse.posting.PostingCommand;

@FunctionalInterface
public interface ErpExchangeListener {
    void acceptFromPosting(PostingCommand command);
}
