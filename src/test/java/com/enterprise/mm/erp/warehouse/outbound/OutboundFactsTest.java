package com.enterprise.mm.erp.warehouse.outbound;

import static org.assertj.core.api.Assertions.*;
import com.enterprise.mm.warehouse.posting.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class OutboundFactsTest {
    @Test void physicalQuantityAndOnlyOneMonetaryChannelAreRetainedPerPosition(){UUID m=UUID.randomUUID(),w=UUID.randomUUID();var effects=List.of(new PostingEffect(PostingEffectType.STOCK,m,w,"1010","PCS",new BigDecimal("-2"),BigDecimal.ZERO,null),new PostingEffect(PostingEffectType.ACCOUNT,m,w,"1010","PCS",new BigDecimal("-2"),new BigDecimal("-20"),"UZS"),new PostingEffect(PostingEffectType.COST,m,w,"1010",null,BigDecimal.ZERO,new BigDecimal("-20"),"UZS"));var facts=OutboundFacts.normalize(new ObjectMapper(),effects);assertThat(facts.size()).isEqualTo(1);assertThat(facts.get(0).path("quantity").asText()).isEqualTo("-2");assertThat(facts.get(0).path("amount").asText()).isEqualTo("-20");}
    @Test void zeroQuantityRevaluationAndOwnerPartitionsArePreserved(){UUID m=UUID.randomUUID(),w=UUID.randomUUID();var values=List.of(new PostingEffect(PostingEffectType.VALUE_ONLY,m,w,"1010",null,BigDecimal.ZERO,new BigDecimal("3.125"),"UZS"));var facts=OutboundFacts.normalize(new ObjectMapper(),values);assertThat(facts.get(0).path("quantity").asText()).isEqualTo("0");assertThat(facts.get(0).path("amount").asText()).isEqualTo("3.125");}
}
