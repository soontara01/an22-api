package com.an22.api.rules;

import com.an22.api.model.Transfer;
import com.an22.api.model.TransferLine;
import com.an22.api.model.TransferPayload;
import com.an22.api.model.TransferReceivePayload;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** port จาก {@code core/models/transfer.model.ts} — ใบโอนสินค้า (E26–E29). payload ต้อง normalize (list ไม่เป็น null) ก่อนเรียก. */
public final class TransferRules {

  private TransferRules() {}

  public record BranchRef(long id, String code, boolean active) {}

  /** สต็อกของ SKU ที่สาขาต้นทาง. */
  public record ProductRef(long id, String sku, int stock, String unit, boolean serialControl, String itemType) {}

  public record SerialRef(String serial, long productId, long branchId, String status) {}

  /** {@code TransferContext}: {@code serials} = serial ของ SKU ในใบ (ทุกสาขา). */
  public record Context(long fromBranchId, List<BranchRef> branches, List<ProductRef> products, List<SerialRef> serials) {}

  /** {@code shortQty()}: จำนวนที่ไม่มาถึงของบรรทัดที่รับแล้ว. */
  public static int shortQty(TransferLine line) {
    return line.receivedQty() == null ? 0 : line.qty() - line.receivedQty();
  }

  /** {@code transferError()}: กฎเดียวของใบโอนใหม่ — null = ผ่าน. */
  public static String transferError(TransferPayload p, Context ctx) {
    BranchRef to = ctx.branches().stream().filter(b -> Objects.equals(b.id(), p.toBranchId())).findFirst().orElse(null);
    if (to == null) {
      return "กรุณาเลือกสาขาปลายทาง";
    }
    if (to.id() == ctx.fromBranchId()) {
      return "สาขาปลายทางต้องไม่ใช่สาขาต้นทาง";
    }
    if (!to.active()) {
      return "สาขา " + to.code() + " ปิดใช้งานแล้ว";
    }
    if (p.lines().isEmpty()) {
      return "ยังไม่มีรายการสินค้าที่จะโอน";
    }
    Set<Long> seen = new HashSet<>();
    for (TransferPayload.Line line : p.lines()) {
      ProductRef product = ctx.products().stream().filter(x -> Objects.equals(x.id(), line.productId())).findFirst().orElse(null);
      if (product == null) {
        return "ไม่พบ SKU ในรายการโอน";
      }
      if (!seen.add(product.id())) {
        return product.sku() + " ซ้ำในรายการ";
      }
      if (ProductRules.isService(product.itemType())) {
        return product.sku() + " เป็นสินค้าบริการ ไม่มีสต็อก";
      }
      if (product.serialControl()) {
        if (line.serials().isEmpty()) {
          return product.sku() + ": กรุณาเลือก Serial ที่จะโอน";
        }
        if (new HashSet<>(line.serials()).size() != line.serials().size()) {
          return product.sku() + ": Serial ซ้ำในรายการ";
        }
        if (line.qty() == null || line.qty() != line.serials().size()) {
          return product.sku() + ": จำนวนต้องเท่ากับจำนวน Serial";
        }
        for (String serial : line.serials()) {
          SerialRef unit = ctx.serials().stream().filter(s -> s.serial().equals(serial)).findFirst().orElse(null);
          if (unit == null || unit.productId() != product.id() || !"in_stock".equals(unit.status())
              || unit.branchId() != ctx.fromBranchId()) {
            return "ซีเรียล " + serial + " ไม่อยู่ในคลังของ " + product.sku() + " ที่สาขานี้";
          }
        }
        continue;
      }
      if (!line.serials().isEmpty()) {
        return product.sku() + " ไม่ได้ควบคุม Serial";
      }
      if (!isInteger(line.qty()) || line.qty() <= 0) {
        return product.sku() + ": จำนวนต้องเป็นจำนวนเต็มมากกว่า 0";
      }
      if (line.qty() > product.stock()) {
        return product.sku() + " มีสต็อกไม่พอ (คงเหลือ " + product.stock() + " " + product.unit() + ")";
      }
    }
    return null;
  }

  /** {@code receiveTransferError()} (E27): รับครั้งเดียว ทุกบรรทัด ส่วนขาดต้องมีเหตุผล. */
  public static String receiveTransferError(Transfer t, TransferReceivePayload p, Long branchId) {
    if (!"in_transit".equals(t.status())) {
      return "ใบโอนนี้รับหรือยกเลิกไปแล้ว";
    }
    if (branchId == null || branchId != t.toBranchId()) {
      return "รับของได้ที่สาขาปลายทางเท่านั้น";
    }
    boolean isShort = false;
    for (TransferLine line : t.lines()) {
      TransferReceivePayload.Line got = received(p, line.productId());
      if (got == null) {
        return line.sku() + ": ไม่ได้ระบุจำนวนที่รับ";
      }
      if (!isInteger(got.receivedQty()) || got.receivedQty() < 0 || got.receivedQty() > line.qty()) {
        return line.sku() + ": จำนวนที่รับต้องอยู่ระหว่าง 0–" + line.qty();
      }
      if (!line.serials().isEmpty()) {
        String unknown = got.receivedSerials().stream().filter(s -> !line.serials().contains(s)).findFirst().orElse(null);
        if (unknown != null) {
          return "ซีเรียล " + unknown + " ไม่อยู่ในใบโอนนี้";
        }
        if (new HashSet<>(got.receivedSerials()).size() != got.receivedQty()) {
          return line.sku() + ": จำนวนที่รับต้องเท่ากับจำนวน Serial ที่ติ๊ก";
        }
      }
      if (got.receivedQty() < line.qty()) {
        isShort = true;
      }
    }
    if (isShort && p.shortReason().isBlank()) {
      return "กรุณาระบุเหตุผลของสินค้าที่ขาด / เสียหาย";
    }
    return null;
  }

  /** {@code cancelTransferError()} (E29): ยังระหว่างโอน, ที่สาขาต้นทาง, admin, มีเหตุผล. */
  public static String cancelTransferError(Transfer t, Long branchId, boolean isAdmin, String reason) {
    if (!"in_transit".equals(t.status())) {
      return "ยกเลิกได้เฉพาะใบโอนที่ยังไม่ได้รับ";
    }
    if (branchId == null || branchId != t.fromBranchId()) {
      return "ยกเลิกได้ที่สาขาต้นทางเท่านั้น";
    }
    if (!isAdmin) {
      return "ยกเลิกใบโอนได้เฉพาะผู้ดูแลระบบ";
    }
    if (reason == null || reason.isBlank()) {
      return "กรุณาระบุเหตุผลการยกเลิก";
    }
    return null;
  }

  /** บรรทัดแรกของ payload ที่เป็น SKU นี้ ({@code p.lines.find()}). */
  public static TransferReceivePayload.Line received(TransferReceivePayload p, long productId) {
    return p.lines().stream().filter(l -> Objects.equals(l.productId(), productId)).findFirst().orElse(null);
  }

  /** {@code Number.isInteger()}. */
  private static boolean isInteger(Double n) {
    return n != null && Double.isFinite(n) && n == Math.rint(n);
  }
}
