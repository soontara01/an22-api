package com.an22.api.company;

import com.an22.api.audit.Audit;
import com.an22.api.auth.SessionDao;
import com.an22.api.auth.SessionDao.Via;
import com.an22.api.auth.ShopSession;
import com.an22.api.billing.BillingService;
import com.an22.api.model.CompanyDeletion;
import com.an22.api.model.CompanyExport;
import com.an22.api.rules.CompanyDataRules;
import com.an22.api.security.PiiCipher;
import com.an22.api.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ข้อมูลทั้งบริษัท (owner — controller ตรวจ, E38 / E39): export เป็น JSON ไฟล์เดียว และขอลบ / ยกเลิกคำขอลบ (ระงับทันที, platform ลบหลัง
 * {@code DELETION_WAIT_DAYS} วัน — ขั้น 12).
 */
@Service
public class CompanyDataService {

  /** เพดานเวลา query ของ export (ค่าเริ่มของ role สั้นกว่า). */
  static final String EXPORT_TIMEOUT = "120s";

  private final CompanyExportDao exports;
  private final CompanyAccountDao accounts;
  private final SessionDao sessions;
  private final BillingService billing;
  private final PasswordEncoder passwords;
  private final PiiCipher cipher;
  private final JsonMapper json;
  private final Audit audit;
  private final Clock clock;

  CompanyDataService(CompanyExportDao exports, CompanyAccountDao accounts, SessionDao sessions, BillingService billing,
      PasswordEncoder passwords, PiiCipher cipher, JsonMapper json, Audit audit, Clock clock) {
    this.exports = exports;
    this.accounts = accounts;
    this.sessions = sessions;
    this.billing = billing;
    this.passwords = passwords;
    this.cipher = cipher;
    this.json = json;
    this.audit = audit;
    this.clock = clock;
  }

  public record DeletionRequest(String companyCode, String password, String reason) {}

  /**
   * {@code GET /company/export}: ทุกตารางของบริษัท (ชื่อ collection = ชื่อตารางไม่มี {@code dt_} แบบ camelCase, field แบบ camelCase) ตามที่เก็บ —
   * ไม่มีรหัสผ่าน / hash ({@code *_hash}), ช่องที่เข้ารหัส ({@code *_enc}) ถอดแล้ว. audit {@code company_export}.
   */
  @Transactional
  public CompanyExport export(ShopSession s) {
    exports.allowLongQueries(EXPORT_TIMEOUT);
    Map<String, Object> data = new LinkedHashMap<>();
    for (String table : exports.tables()) {
      List<JsonNode> rows = new ArrayList<>();
      for (String row : exports.rows(table, exports.hasId(table))) {
        rows.add(exported(table, (ObjectNode) json.readTree(row), s.tenantId()));
      }
      data.put(camel(table.substring("dt_".length())), rows);
    }
    audit.record(s, "company_export", null, "company", s.tenantId(), s.companyCode(), "ดาวน์โหลดข้อมูลทั้งบริษัท (JSON)");
    return new CompanyExport(CompanyDataRules.EXPORT_SCHEMA_VERSION, Instant.now(clock),
        new CompanyExport.Company(s.tenantId(), s.companyCode(), s.companyName(), s.taxId()), data);
  }

  private JsonNode exported(String table, ObjectNode row, long tenantId) {
    ObjectNode out = json.createObjectNode();
    for (Map.Entry<String, JsonNode> field : row.properties()) {
      String column = field.getKey();
      JsonNode value = field.getValue();
      if (column.endsWith("_hash")) {
        continue;
      }
      if (column.endsWith("_enc")) {
        column = column.substring(0, column.length() - "_enc".length());
        if (!value.isNull()) {
          // bytea ใน jsonb = "\x<hex>"
          byte[] stored = HexFormat.of().parseHex(value.asString().substring(2));
          value = json.readTree(cipher.decryptString(stored, PiiCipher.aad(table, column, tenantId)));
        }
      }
      out.set(camel(column), value);
    }
    return out;
  }

  static String camel(String snake) {
    StringBuilder b = new StringBuilder(snake.length());
    boolean up = false;
    for (char ch : snake.toCharArray()) {
      if (ch == '_') {
        up = true;
      } else {
        b.append(up ? Character.toUpperCase(ch) : ch);
        up = false;
      }
    }
    return b.toString();
  }

  /** {@code GET /company/deletion}: คำขอที่รออยู่ หรือ null. */
  @Transactional(readOnly = true)
  public CompanyDeletion deletion(ShopSession s) {
    return accounts.deletion(s.tenantId()).orElse(null);
  }

  /**
   * {@code POST /company/deletion}: พิมพ์รหัสบริษัท + รหัสผ่านของ owner → ระงับทันทีทุกคนยกเว้น owner (แทนการระงับเดิม), ลบได้ตั้งแต่ +30 วัน.
   */
  @Transactional("platformTx")
  public CompanyDeletion requestDeletion(ShopSession s, DeletionRequest in) {
    String problem = CompanyDataRules.deletionRequestError(in.companyCode(), in.password(), s.companyCode());
    if (problem != null) {
      throw ApiException.badRequest(problem);
    }
    String hash = sessions.userById(Via.PLATFORM, s.tenantId(), s.user().id()).orElseThrow().passwordHash();
    if (!passwords.matches(in.password(), hash)) {
      throw ApiException.badRequest("รหัสผ่านไม่ถูกต้อง");
    }
    if (accounts.lockDeletion(s.tenantId()).isPresent()) {
      throw ApiException.badRequest("ขอลบข้อมูลไว้แล้ว");
    }
    String reason = in.reason() == null ? "" : in.reason().strip();
    CompanyDeletion d = new CompanyDeletion(Instant.now(clock).truncatedTo(ChronoUnit.MICROS), s.user().name(), reason,
        LocalDate.now(clock).plusDays(CompanyDataRules.DELETION_WAIT_DAYS));
    accounts.requestDeletion(s.tenantId(), d);
    audit.recordOnPlatform(s, "deletion_request", null, "company", s.tenantId(), s.companyCode(),
        "ลบได้ตั้งแต่ " + d.purgeAfter() + (reason.isEmpty() ? "" : " — " + reason));
    return d;
  }

  /** {@code DELETE /company/deletion}: กลับมาใช้งาน แล้วให้ billing ตัดสินอีกครั้ง (ค้างชำระเกินผ่อนผัน = ระงับทันที). */
  @Transactional("platformTx")
  public void cancelDeletion(ShopSession s) {
    if (accounts.lockDeletion(s.tenantId()).isEmpty()) {
      throw ApiException.badRequest("ไม่มีคำขอลบข้อมูล");
    }
    accounts.cancelDeletion(s.tenantId());
    audit.recordOnPlatform(s, "deletion_cancel", null, "company", s.tenantId(), s.companyCode(), "");
    billing.catchUpIn(s.tenantId());
  }
}
