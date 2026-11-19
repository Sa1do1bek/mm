package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Company shared lock plus sorted warehouse locks; an empty scope locks the entire company exclusively. */
@Service @RequiredArgsConstructor
public class InventoryScopeLockService {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ExactOpeningImportGuard openingImportGuard;
    @Transactional(propagation=Propagation.MANDATORY)
    public void lock(UUID companyId,Collection<UUID> warehouseIds) {
        Objects.requireNonNull(companyId,"Company scope is required");
        var warehouses=warehouseIds==null?List.<UUID>of():warehouseIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{
            if(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                advisory(connection,warehouses.isEmpty()?"pg_advisory_xact_lock":"pg_advisory_xact_lock_shared",key(companyId,"company"));
                for(var warehouse:warehouses) advisory(connection,"pg_advisory_xact_lock",key(companyId,warehouse.toString()));
            } else {
                // H2 compatibility for existing in-process tests; PostgreSQL is the deployed database.
                try(var statement=connection.prepareStatement("select id from companies where id=? for update")){statement.setObject(1,companyId);statement.execute();}
            }
            return null;
        });
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }
    static long companyLockKey(UUID companyId) { return key(companyId, "company"); }
    private static void advisory(java.sql.Connection c,String function,long key)throws java.sql.SQLException {
        try(var statement=c.prepareStatement("select "+function+"(?)")){statement.setLong(1,key);statement.execute();}
    }
    private static long key(UUID company,String scope) {
        try{return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(("inventory-scope:"+TenantContext.currentTenant()+":"+company+":"+scope).getBytes(StandardCharsets.UTF_8))).getLong();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
