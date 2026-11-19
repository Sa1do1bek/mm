package com.enterprise.mm.enums;

/**
 * Procurement request kind for position-based documents (demand plans, purchase requests, etc.).
 * Mirrors the MXIK classifier kinds ({@code GOODS} / {@code SERVICE} / {@code WORKS}) so a document's request type
 * can filter its item materials to matching product codes and, later, route it to a request-type
 * specific approval flow. WORKS remains a distinct document type while using the SERVICE catalog mapping.
 */
public enum RequestType {
    GOODS,
    SERVICE;

    public boolean isServiceStyle() {
        return this == SERVICE;
    }
}

