package com.enterprise.mm.erp.warehouse.outbound;

import com.enterprise.mm.warehouse.posting.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;

/** STOCK carries physical quantity; ACCOUNT, VALUE_ONLY, COST are alternative valuation channels. */
public final class OutboundFacts {
    private OutboundFacts(){}
    private record Key(UUID material,UUID warehouse,String account,UUID owner,String category,String disposition,UUID location,UUID bin,UUID batch){}
    public static ArrayNode normalize(ObjectMapper mapper,List<PostingEffect> effects){
        Map<Key,List<PostingEffect>> positions=new LinkedHashMap<>();for(var e:effects){var key=new Key(e.materialId(),e.warehouseId(),e.accountCode(),e.ownerId(),e.stockCategory(),e.disposition(),e.storageLocationId(),e.storageBinId(),e.stockBatchId());positions.computeIfAbsent(key,k->new ArrayList<>()).add(e);}
        ArrayNode result=mapper.createArrayNode();for(var entry:positions.entrySet()){
            var all=entry.getValue();var stock=all.stream().filter(e->e.type()==PostingEffectType.STOCK).toList();List<PostingEffect> value=List.of();
            for(var type:List.of(PostingEffectType.ACCOUNT,PostingEffectType.VALUE_ONLY,PostingEffectType.COST)){var channel=all.stream().filter(e->e.type()==type).toList();if(!channel.isEmpty()){value=channel;break;}}
            Map<String,List<PostingEffect>> currencies=new LinkedHashMap<>();for(var e:value)currencies.computeIfAbsent(e.currencyCode()==null?"UZS":e.currencyCode(),k->new ArrayList<>()).add(e);
            BigDecimal physical=stock.stream().map(PostingEffect::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(currencies.isEmpty()){add(result,entry.getKey(),physical,BigDecimal.ZERO,"UZS","STOCK");continue;}
            if(currencies.size()==1){var c=currencies.entrySet().iterator().next();BigDecimal q=stock.isEmpty()?c.getValue().stream().filter(e->e.type()==PostingEffectType.ACCOUNT).map(PostingEffect::quantity).reduce(BigDecimal.ZERO,BigDecimal::add):physical;add(result,entry.getKey(),q,sumAmount(c.getValue()),c.getKey(),c.getValue().getFirst().type().name());}
            else{BigDecimal accountQuantity=value.stream().filter(e->e.type()==PostingEffectType.ACCOUNT).map(PostingEffect::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);boolean split=!stock.isEmpty()&&accountQuantity.compareTo(physical)==0;if(!stock.isEmpty()&&!split)add(result,entry.getKey(),physical,BigDecimal.ZERO,"UZS","STOCK");for(var c:currencies.entrySet()){BigDecimal q=stock.isEmpty()||split?c.getValue().stream().filter(e->e.type()==PostingEffectType.ACCOUNT).map(PostingEffect::quantity).reduce(BigDecimal.ZERO,BigDecimal::add):BigDecimal.ZERO;add(result,entry.getKey(),q,sumAmount(c.getValue()),c.getKey(),c.getValue().getFirst().type().name());}}
        }return result;
    }
    private static BigDecimal sumAmount(List<PostingEffect> e){return e.stream().map(PostingEffect::amount).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private static void add(ArrayNode result,Key k,BigDecimal q,BigDecimal amount,String currency,String channel){if(q.signum()==0&&amount.signum()==0)return;ObjectNode n=result.addObject();n.put("domain","STOCK");put(n,"materialId",k.material);put(n,"warehouseId",k.warehouse);n.put("quantity",q.toPlainString());n.put("amount",amount.toPlainString());n.put("currency",currency);n.put("valuationChannel",channel);put(n,"accountCode",k.account);put(n,"ownerId",k.owner);put(n,"stockCategory",k.category);put(n,"disposition",k.disposition);put(n,"storageLocationId",k.location);put(n,"storageBinId",k.bin);put(n,"stockBatchId",k.batch);}
    static void put(ObjectNode n,String key,Object value){if(value==null)n.putNull(key);else n.put(key,String.valueOf(value));}
}
