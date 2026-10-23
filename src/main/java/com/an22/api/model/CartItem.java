package com.an22.api.model;

/**
 * {@code CartItem} (sale.model.ts): 1 บรรทัดของตะกร้า POS — factor 1 = หน่วยฐาน ไม่งั้นตัวคูณของหน่วยแพ็ก; serial บังคับเมื่อ SKU คุม serial
 * (qty = 1). ตัวเลขเป็น Double ให้กฎ "จำนวนเต็ม" ทำงาน; manualDiscount null = ไม่มี.
 */
public record CartItem(Long productId, Double factor, Double qty, String serial, ManualDiscount manualDiscount) {}
