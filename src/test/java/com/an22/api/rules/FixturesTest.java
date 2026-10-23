package com.an22.api.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.an22.api.model.CartItem;
import com.an22.api.model.FreeSerial;
import com.an22.api.model.ManualDiscount;
import com.an22.api.model.PaymentInput;
import com.an22.api.model.PaymentMethod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden fixtures ของหน้าบ้าน ({@code ../FrontEnd-An22/docs/backend/fixtures}, ทับได้ด้วย {@code -Dfixtures.dir=…}) — ผลของ
 * {@link PosPricing} / {@link PosPayment} ต้องตรงทั้ง object: ตัวเลขเทียบค่า (450 = 450.0), ข้อความตรงทุกตัวอักษร.
 */
class FixturesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path DIR = Path.of(System.getProperty("fixtures.dir", "../FrontEnd-An22/docs/backend/fixtures"));

  record ManualRule(String reason, boolean isAdmin, double maxPercent) {}

  record PriceCartInput(
      List<CartItem> items, PosPricing.PricingContext context, List<FreeSerial> freeSerials, ManualDiscount billManualDiscount,
      ManualRule manualDiscountRule) {}

  record PriceCartCase(String name, PriceCartInput input, JsonNode expected) {}

  record PaymentCaseInput(double total, List<PaymentInput> payments, List<PaymentMethod> methods) {}

  record PaymentCase(String name, PaymentCaseInput input, JsonNode expected) {}

  @TestFactory
  Stream<DynamicTest> priceCartMatchesFrontEnd() throws Exception {
    List<PriceCartCase> cases = read("price-cart.json", new TypeReference<>() {});
    return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
      PriceCartInput in = c.input();
      PosPricing.PricedCart cart = PosPricing.priceCart(in.items(), in.context(), in.freeSerials(), in.billManualDiscount());
      assertSame(c.expected().get("cart"), JSON.valueToTree(cart), "cart");
      assertThat(PosPricing.cartError(cart)).isEqualTo(text(c.expected().get("cartError")));
      ManualRule rule = in.manualDiscountRule();
      assertThat(PosPricing.manualDiscountError(cart, rule.reason(), rule.isAdmin(), rule.maxPercent()))
          .isEqualTo(text(c.expected().get("manualDiscountError")));
    }));
  }

  @TestFactory
  Stream<DynamicTest> paymentMatchesFrontEnd() throws Exception {
    List<PaymentCase> cases = read("payment.json", new TypeReference<>() {});
    return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
      PaymentCaseInput in = c.input();
      assertSame(c.expected().get("summary"), JSON.valueToTree(PosPayment.paymentSummary(in.total(), in.payments(), in.methods())),
          "summary");
      assertThat(PosPayment.paymentError(in.total(), in.payments(), in.methods())).isEqualTo(text(c.expected().get("error")));
    }));
  }

  private static <T> T read(String file, TypeReference<T> type) throws Exception {
    Path path = DIR.resolve(file);
    assertThat(Files.isRegularFile(path)).as("ไม่พบ fixtures %s (ตั้ง -Dfixtures.dir)", path.toAbsolutePath()).isTrue();
    T cases = JSON.readValue(path.toFile(), type);
    assertThat((List<?>) cases).isNotEmpty();
    return cases;
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() ? null : node.asString();
  }

  /** เทียบทั้งต้นไม้: key ครบเท่ากัน, ตัวเลขเทียบค่า, อื่นเทียบตรง — บอก path ที่ต่าง. */
  private static void assertSame(JsonNode expected, JsonNode actual, String path) {
    if (expected.isNumber() && actual.isNumber()) {
      if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
        fail("%s: expected %s but was %s", path, expected, actual);
      }
      return;
    }
    if (expected.isObject() && actual.isObject()) {
      Set<String> keys = new HashSet<>(expected.propertyNames());
      assertThat(new HashSet<>(actual.propertyNames())).as("%s: keys", path).isEqualTo(keys);
      for (String key : keys) {
        assertSame(expected.get(key), actual.get(key), path + "." + key);
      }
      return;
    }
    if (expected.isArray() && actual.isArray()) {
      assertThat(actual.size()).as("%s: size (actual %s)", path, actual).isEqualTo(expected.size());
      for (int i = 0; i < expected.size(); i++) {
        assertSame(expected.get(i), actual.get(i), path + "[" + i + "]");
      }
      return;
    }
    assertThat(actual).as(path).isEqualTo(expected);
  }
}
