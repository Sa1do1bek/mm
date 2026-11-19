package com.enterprise.mm.erp;

import com.enterprise.mm.error.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Complete, exact-decimal evidence from the native accounting register, never inferred from its journal. */
final class ErpPeriodEvidence {
    record Amount(BigDecimal quantity, BigDecimal value) {
        static Amount zero() { return new Amount(BigDecimal.ZERO,BigDecimal.ZERO); }
        Amount plus(Amount x) { return new Amount(quantity.add(x.quantity),value.add(x.value)); }
        Amount minus(Amount x) { return new Amount(quantity.subtract(x.quantity),value.subtract(x.value)); }
        boolean same(Amount x) { return quantity.compareTo(x.quantity)==0 && value.compareTo(x.value)==0; }
    }
    record Flow(Amount in, Amount out) {
        static Flow zero() { return new Flow(Amount.zero(),Amount.zero()); }
        Flow plus(Flow x) { return new Flow(in.plus(x.in),out.plus(x.out)); }
        Amount net() { return in.minus(out); }
    }
    record Key(UUID material, UUID warehouse) {}
    record NativeDocument(UUID id, LocalDate date, String kind, String label) {}
    record Snapshot(List<UUID> materials,List<UUID> warehouses,Map<Key,Amount> opening,Map<Key,Amount> closing,
                    NavigableMap<LocalDate,Map<Key,Flow>> days,List<NativeDocument> documents) {}

    static Snapshot parse(JsonNode data,String tenant,UUID company,LocalDate from,LocalDate to) {
        if(data==null || !data.path("protocolVersion").isIntegralNumber() || data.path("protocolVersion").asInt()!=1
            || !tenant.equals(data.path("tenantId").asText()) || !company.toString().equals(data.path("companyId").asText())
            || !from.toString().equals(data.path("from").asText()) || !to.toString().equals(data.path("to").asText())
            || !"DAILY_ACCOUNTING_TURNOVER".equals(data.path("basis").asText()) || !data.path("complete").isBoolean() || !data.path("complete").asBoolean())
            throw invalid("1C period identity or completeness is invalid");
        var open=balance(data,tenant,company,from.minusDays(1),"openingBalances");
        var close=balance(data,tenant,company,to,"closingBalances");
        var materials=open.materials().stream().sorted(Comparator.comparing(UUID::toString)).toList();
        var warehouses=open.warehouses().stream().sorted(Comparator.comparing(UUID::toString)).toList();
        long size=(long)materials.size()*warehouses.size(); long dayCount=from.datesUntil(to.plusDays(1)).count();
        if(dayCount<1 || dayCount>31 || size*dayCount>100000 || !data.path("days").isArray() || data.path("days").size()!=dayCount)
            throw invalid("1C period does not cover all days or exceeds the supported scope");
        var days=new TreeMap<LocalDate,Map<Key,Flow>>();
        for(JsonNode day:data.path("days")) {
            LocalDate date=date(day.path("date"));
            if(date.isBefore(from)||date.isAfter(to)||days.containsKey(date)) throw invalid("1C period contains duplicate or out-of-period days");
            if(!day.path("turnovers").isArray() || day.path("turnovers").size()!=size) throw invalid("1C daily turnover scope is incomplete");
            Map<Key,Flow> values=new HashMap<>();
            for(JsonNode row:day.path("turnovers")) {
                Key key=new Key(id(row.path("materialId")),id(row.path("warehouseId")));
                if(!open.balances().containsKey(new ErpReconciliationService.Key(key.material,key.warehouse)) || values.containsKey(key))
                    throw invalid("1C turnover contains duplicate or out-of-scope positions");
                values.put(key,new Flow(new Amount(number(row.path("inQuantity"),7),number(row.path("inAmount"),4)),
                    new Amount(number(row.path("outQuantity"),7),number(row.path("outAmount"),4))));
            }
            days.put(date,Map.copyOf(values));
        }
        if(!data.path("documents").isArray() || data.path("documents").size()>10000) throw invalid("1C document evidence is invalid");
        List<NativeDocument> documents=new ArrayList<>(); Set<UUID> seen=new HashSet<>();
        for(JsonNode row:data.path("documents")) {
            UUID id=id(row.path("documentId")); LocalDate date=date(row.path("date")); String kind=row.path("kind").asText();
            if(!seen.add(id)||date.isBefore(from)||date.isAfter(to)||!Set.of("RECEIPT","ISSUE","TRANSFER","OTHER").contains(kind)
                || !row.path("label").isTextual() || row.path("label").asText().length()>1000) throw invalid("1C document identity or date is invalid");
            documents.add(new NativeDocument(id,date,kind,row.path("label").asText()));
        }
        documents.sort(Comparator.comparing(x->x.id.toString()));
        Map<Key,Amount> opening=amounts(open),closing=amounts(close);
        for(Key key:opening.keySet()) {
            Amount expected=opening.get(key);
            for(var day:days.values()) expected=expected.plus(day.get(key).net());
            if(!expected.same(closing.get(key))) throw invalid("1C opening plus turnover does not equal closing; repeat the calculation");
        }
        return new Snapshot(materials,warehouses,opening,closing,days,List.copyOf(documents));
    }
    private static ErpReconciliationService.Snapshot balance(JsonNode data,String tenant,UUID company,LocalDate date,String field) {
        ObjectNode node=((ObjectNode)data).deepCopy(); node.put("date",date.toString());node.put("basis","END_OF_DAY");node.set("balances",data.path(field));
        return ErpReconciliationService.parse(node,tenant,company,date);
    }
    private static Map<Key,Amount> amounts(ErpReconciliationService.Snapshot snapshot) {
        Map<Key,Amount> result=new HashMap<>(); snapshot.balances().forEach((k,v)->result.put(new Key(k.material(),k.warehouse()),new Amount(v.quantity(),v.amount())));return Map.copyOf(result);
    }
    static String decimal(BigDecimal value) { return value.signum()==0?"0":value.stripTrailingZeros().toPlainString(); }
    static BigDecimal number(JsonNode node,int scale) {
        if(!node.isTextual()||!node.asText().matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))throw invalid("1C value must be a decimal string");
        BigDecimal value=new BigDecimal(node.asText());
        if(value.scale()>scale||Math.max(0,value.precision()-value.scale())>15)throw invalid("1C value exceeds supported precision");return value;
    }
    static UUID id(JsonNode node) {
        try { UUID id=UUID.fromString(node.asText());if(!node.isTextual()||!id.toString().equals(node.asText())||id.equals(new UUID(0,0)))throw invalid("Invalid canonical UUID");return id; }
        catch(IllegalArgumentException ex){throw invalid("Invalid UUID in 1C period evidence");}
    }
    static LocalDate date(JsonNode node) { try{return LocalDate.parse(node.asText());}catch(RuntimeException ex){throw invalid("Invalid date in 1C period evidence");} }
    static BadRequestException invalid(String message){return new BadRequestException(message,"ERP_PERIOD_EVIDENCE_INVALID");}
}
