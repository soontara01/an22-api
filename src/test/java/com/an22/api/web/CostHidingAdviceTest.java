package com.an22.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ตาม {@code cost-visibility.model.spec.ts} ({@code hideCost()}). */
class CostHidingAdviceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void nullsEveryCostFieldAtAnyDepthAndKeepsTheRest() {
    JsonNode body = JSON.readTree("""
        {"sku":"A1","cost":100,"avgCost":95.5,"suppliers":[{"supplierId":1,"cost":90,"moq":10}],
         "lines":[{"qty":2,"cogs":191,"amount":300,"manualDiscount":{"type":"baht","value":5}}],
         "card":{"opening":{"qty":3,"avgCost":90,"value":270},"totals":{"inQty":1,"inCost":100,"outQty":0,"outCost":0},
                 "movements":[{"unitCost":1,"totalCost":1,"balanceAvgCost":1,"balanceValue":1,"qty":1}]},
         "exchange":{"costIn":1,"costOut":2},"transfer":{"unitCost":9},"when":null}
        """);
    CostHidingAdvice.strip(body);
    assertThat(body).isEqualTo(JSON.readTree("""
        {"sku":"A1","cost":null,"avgCost":null,"suppliers":[{"supplierId":1,"cost":null,"moq":10}],
         "lines":[{"qty":2,"cogs":null,"amount":300,"manualDiscount":{"type":"baht","value":5}}],
         "card":{"opening":{"qty":3,"avgCost":null,"value":null},"totals":{"inQty":1,"inCost":null,"outQty":0,"outCost":null},
                 "movements":[{"unitCost":null,"totalCost":null,"balanceAvgCost":null,"balanceValue":null,"qty":1}]},
         "exchange":{"costIn":null,"costOut":null},"transfer":{"unitCost":null},"when":null}
        """));
  }
}
