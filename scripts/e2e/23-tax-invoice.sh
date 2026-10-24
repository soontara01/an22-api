#!/usr/bin/env bash
# ข้อ 23 — ใบกำกับภาษีเต็มรูป (docs/e2e-test-plan.md)
E2E_STEP=23
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

today=$(TZ=Asia/Bangkok date +%F)
ymd=$(TZ=Asia/Bangkok date +%Y%m%d)
std_company E23
login "$CODE" boss pass1234; T_BOSS=$TOKEN
login "$CODE" cash1 pass1234; T_CASH1=$TOKEN
num() { jq -r "$1 | if type == \"number\" then (. + 0 | tostring) else tostring end" <<<"$BODY"; }
at() { as "$1"; branch "$2"; terminal "${3:--}"; }
inv() { echo "INV-HQ-$ymd-$(printf %04d "$1")"; }
buyer() { # name [jq แก้]
  jq -nc --arg n "$1" '{name:$n, idType:"tax_id", taxId:"0105550123451", branchType:"head", branchNo:"", address:"9 ถนนพระราม 9 กรุงเทพฯ"}' | jq -c "${2:-.}"
}
sale() { # [buyer]
  call POST /sales "$(jq -nc --argjson p "$P_MS" --argjson m "$PM_CASH" --argjson b "${1:-null}" '{items:[{productId:$p, factor:1, qty:1, serial:null}], freeSerials:[],
    payments:[{methodId:$m, amount:350, reference:""}], customer:"", expectedTotal:350, buyer:$b}')"
  SALE_ID=$(jq -r .id <<<"$BODY")
}
company_vat() { as "$T_BOSS"; call PUT /settings/company "$(jq -nc --arg n "บริษัท ทดสอบ $CODE" --argjson v "$1" '{name:$n, taxId:"0105550123451", vatRegistered:$v,
  taxInvoicePaper:"A4", exchangeDays:7, manualDiscountMaxPercent:5, requireShift:true, shiftVarianceLimit:100, shiftMaxHours:12, staffSeeCost:false}')"; }
counts() { sql "SELECT (SELECT count(*) FROM dt_sales WHERE tenant_id = $TENANT) || ':' || (SELECT count(*) FROM dt_tax_invoices WHERE tenant_id = $TENANT)"; }
NAME1="บริษัท ลูกค้าลับยี่สิบสาม จำกัด"
NAME2="บริษัท ลูกค้าใหม่ลับยี่สิบสาม จำกัด"
E2E_EXTRA_SECRETS="ลูกค้าลับยี่สิบสาม ลูกค้าใหม่ลับยี่สิบสาม"

at "$T_CASH1" "$BR_HQ" 1
call POST /shifts/open '{"openingFloat":0,"openingDenominations":[]}'

case_id 23.1 "ขายพร้อมผู้ซื้อ"
sale "$(buyer "  $NAME1 " '.taxId = "0-1055-50123-45-1"')"
expect_status 200
S1=$SALE_ID
expect_json .taxInvoiceNo "$(inv 1)"
expect_sql "SELECT invoice_no || ':' || at_sale || ':' || (issued_at IS NOT NULL) || ':' || issued_by FROM dt_tax_invoices WHERE tenant_id = $TENANT AND sale_id = $S1" \
  "$(inv 1):true:true:แคชเชียร์หนึ่ง"
expect_sql "SELECT position(convert_to('ลูกค้าลับยี่สิบสาม', 'UTF8') in buyer_enc) || ':' || position(convert_to('0105550123451', 'UTF8') in buyer_enc) || ':' || (length(buyer_enc) > 40)
  FROM dt_tax_invoices WHERE tenant_id = $TENANT AND sale_id = $S1" "0:0:true"
expect_sql "SELECT encode(buyer_tax_id_hash, 'hex') FROM dt_tax_invoices WHERE tenant_id = $TENANT AND sale_id = $S1" "$(hmac "$TENANT:0105550123451")"
call GET "/sales/$S1/tax-invoice"
expect_json '[.buyer.name, .buyer.taxId, .buyer.branchType] | join(" ")' "$NAME1 0105550123451 head"   # ตัดช่องว่าง / ขีด

case_id 23.2 "ผู้ซื้อผิด"
before=$(counts)
while IFS='|' read -r b msg; do
  sale "$b"
  expect_status 400
  expect_msg "$msg"
done <<EOF
$(buyer " ")|กรุณากรอกชื่อผู้ซื้อ
$(buyer "$NAME1" '.taxId = "0105550123452"')|เลขประจำตัวผู้เสียภาษีผู้ซื้อต้องเป็น 13 หลักที่ถูกต้อง
$(buyer "$NAME1" '.taxId = ""')|เลขประจำตัวผู้เสียภาษีผู้ซื้อต้องเป็น 13 หลักที่ถูกต้อง
$(buyer "$NAME1" '.idType = "passport" | .taxId = "AB1"')|เลขที่หนังสือเดินทางต้องเป็นตัวอักษรอังกฤษ/ตัวเลข 6–20 ตัว
$(buyer "$NAME1" '.branchType = "branch" | .branchNo = "12"')|เลขที่สาขาผู้ซื้อต้องเป็นตัวเลข 5 หลัก
$(buyer "$NAME1" '.address = " "')|กรุณากรอกที่อยู่ผู้ซื้อ
EOF
check "ไม่มีบิล / ใบกำกับใหม่" "$before" "$(counts)"
sale "$(buyer "$NAME1" '.idType = "passport" | .taxId = "ab123456" | .branchType = "branch" | .branchNo = "00001"')"
expect_status 200   # passport ตัวใหญ่ ไม่มีสาขา
call GET "/sales/$SALE_ID/tax-invoice"
expect_json '[.buyer.idType, .buyer.taxId, .buyer.branchType, "[" + .buyer.branchNo + "]"] | join(" ")' "passport AB123456 none []"

case_id 23.3 "ออกทีหลัง / ซ้ำ / บิลยกเลิกแล้ว"
sale
S2=$SALE_ID
expect_json .taxInvoiceNo null
call POST "/sales/$S2/tax-invoice" "{\"buyer\":$(buyer "$NAME1")}"
expect_status 200
expect_json '[.invoiceNo, .atSale] | map(tostring) | join(" ")' "$(inv 3) false"
expect_sql "SELECT tax_invoice_no FROM dt_sales WHERE id = $S2" "$(inv 3)"
call @api2 POST "/sales/$S2/tax-invoice" "{\"buyer\":$(buyer "$NAME1")}"
expect_status 400
expect_msg "บิลนี้ออกใบกำกับภาษีเต็มรูปแล้ว ($(inv 3))"
sale
VOIDED=$SALE_ID
call POST "/sales/$VOIDED/void" '{"reason":"ทดสอบ","approval":{"username":"boss","password":"pass1234"}}'
expect_status 200
call POST "/sales/$VOIDED/tax-invoice" "{\"buyer\":$(buyer "$NAME1")}"
expect_status 400
expect_msg "ออกใบกำกับภาษีได้เฉพาะบิลขายหน้าร้านที่ชำระแล้ว"
call POST "/sales/$S2/tax-invoice" "{\"buyer\":$(buyer " ")}"
expect_status 400
check "ออกทีหลังผู้ซื้อผิด (ใบมีแล้วตอบก่อน)" "บิลนี้ออกใบกำกับภาษีเต็มรูปแล้ว ($(inv 3))" "$(jq -r .message <<<"$BODY")"

case_id 23.4 "reissue"
call POST "/sales/$S2/tax-invoice/reissue" "{\"buyer\":$(buyer "$NAME2"),\"reason\":\"แก้ชื่อผู้ซื้อ\"}"
expect_status 403
expect_msg "ยกเลิกและออกใบกำกับภาษีใหม่ได้เฉพาะผู้ดูแลระบบ"
at "$T_BOSS" "$BR_HQ"
call POST "/sales/$S2/tax-invoice/reissue" "{\"buyer\":$(buyer "$NAME2"),\"reason\":\" \"}"
expect_status 400
expect_msg "กรุณาระบุเหตุผลที่ยกเลิกใบเดิม"
call POST "/sales/$S2/tax-invoice/reissue" "{\"buyer\":$(buyer " "),\"reason\":\"แก้ชื่อ\"}"
expect_status 400
expect_msg "กรุณากรอกชื่อผู้ซื้อ"
call @api2 POST "/sales/$S2/tax-invoice/reissue" "{\"buyer\":$(buyer "$NAME2"),\"reason\":\" แก้ชื่อผู้ซื้อ \"}"
expect_status 200
expect_json '[.invoiceNo, .replacesInvoiceNo, .buyer.name] | join(" ")' "$(inv 4) $(inv 3) $NAME2"
expect_sql "SELECT string_agg(invoice_no || ':' || (cancelled_at IS NOT NULL) || ':' || coalesce(nullif(cancel_reason, ''), '-') || ':' || coalesce(replaced_by_no, '-') || ':' || coalesce(replaces_invoice_no, '-'), ',' ORDER BY id)
  FROM dt_tax_invoices WHERE tenant_id = $TENANT AND sale_id = $S2" "$(inv 3):true:แก้ชื่อผู้ซื้อ:$(inv 4):-,$(inv 4):false:-:-:$(inv 3)"
expect_sql "SELECT tax_invoice_no FROM dt_sales WHERE id = $S2" "$(inv 4)"
expect_sql "SELECT action || ':' || user_id || ':' || ref_label || ':' || detail FROM dt_audit_log WHERE tenant_id = $TENANT AND action = 'tax_invoice_reissue'" \
  "tax_invoice_reissue:$U_BOSS:$(inv 4):แทน $(inv 3) — แก้ชื่อผู้ซื้อ"
call POST "/sales/$VOIDED/tax-invoice/reissue" "{\"buyer\":$(buyer "$NAME2"),\"reason\":\"x\"}"
expect_status 400
expect_msg "ออกใบใหม่ได้เฉพาะบิลที่ชำระแล้ว"

case_id 23.5 "ร้านไม่จด VAT"
company_vat false
expect_status 200
at "$T_CASH1" "$BR_HQ" 1
sale "$(buyer "$NAME1")"
expect_status 400
expect_msg "ร้านไม่ได้จดทะเบียน VAT ออกใบกำกับภาษีไม่ได้"
sale
expect_status 200
NOVAT=$SALE_ID
check "บิลไม่จด VAT" "0 false" "$(num .vat) $(num .vatRegistered)"
expect_sql "SELECT vat_registered || ':' || vat FROM dt_sales WHERE id = $NOVAT" "false:0.00"
company_vat true
at "$T_CASH1" "$BR_HQ" 1
call POST "/sales/$NOVAT/tax-invoice" "{\"buyer\":$(buyer "$NAME1")}"
expect_status 400
expect_msg "บิลนี้ขายตอนร้านยังไม่ได้จด VAT ออกใบกำกับภาษีไม่ได้"

case_id 23.6 "รายงาน / ค้นผู้ซื้อ / ใบของบิล"
call GET "/tax-invoices?from=$today&to=$today"
expect_status 403
expect_msg "ดูรายงานภาษีขายได้เฉพาะผู้ดูแลระบบ"
at "$T_BOSS" "$BR_HQ"
call GET "/tax-invoices?from=$today&to=$today"
expect_status 200
check "รายงาน: ทุกใบของวันนี้ เก่าก่อน (รวมใบที่ยกเลิก)" "$(inv 1),$(inv 2),$(inv 3),$(inv 4)" "$(jq -r '[.[].invoiceNo] | join(",")' <<<"$BODY")"
check "ชื่อผู้ซื้อถอดรหัสแล้ว" "$NAME1" "$(jq -r '.[0].buyer.name' <<<"$BODY")"
call GET /tax-invoices
expect_status 400
for q in 0105550123451 0-1055-50123-45-1; do
  call POST /tax-invoices/buyer-lookup "{\"taxId\":\"$q\"}"
  expect_status 200
  check "ผู้ซื้อล่าสุดของเลข $q" "$NAME2 0105550123451" "$(jq -r '[.name, .taxId] | join(" ")' <<<"$BODY")"
done
call POST /tax-invoices/buyer-lookup '{"taxId":"0994000165510"}'
check "เลขที่ไม่เคยใช้ → ว่าง" "200 null" "$STATUS ${BODY:-null}"
call GET "/sales/$S2/tax-invoice"
expect_json .invoiceNo "$(inv 4)"
call GET "/sales/$S2/tax-invoices"
expect_json '[.[].invoiceNo] | join(",")' "$(inv 3),$(inv 4)"
call GET "/sales/$VOIDED/tax-invoice"
check "บิลไม่มีใบ → ว่าง" "200 null" "$STATUS ${BODY:-null}"
as "$T_CASH1"; branch "$BR_HQ"
call GET "/sales/$S1/tax-invoice"
expect_json .buyer.name "$NAME1"   # พนักงานพิมพ์ใบกำกับของบิลได้
as -; branch -; terminal -

finish
