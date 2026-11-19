# Material qoldig‘i va kirim manbalari detail API

## Frontend ulanishi

Stock, material tanlash va transfer draft oynalaridagi `Info` tugmasi:

```http
GET /api/materials/{materialId}/command-view/stock-detail?warehouseId={warehouseId}&page=0&size=25
```

`/receipt-origins` shu endpointning aliasi. Ikkalasi ham bir xil `ApiResponse<Detail>` qaytaradi:
summary va sahifalangan `origins` bitta repeatable-read tranzaksiyada olinadi.

| Parametr | Qoida |
| --- | --- |
| `warehouseId` | Tanlangan ombor. Omborga biriktirilgan foydalanuvchi uchun server scope qo‘llaydi; boshqa ombor rad etiladi. |
| `stockBatchId` | Ixtiyoriy partiya filtri; summary va origins uchun bir xil ishlaydi. |
| `asOf` | `YYYY-MM-DD`; berilmasa bugungi joriy holat. Kelajakdagi sana: 400. |
| `positiveOnly` | Default `true`; faqat qoldig‘i musbat kirimlar. Summary bu parametrga bog‘liq emas. |
| `page`, `size` | Default `0`, `25`; page >= 0, size 1..100. Tartib: receivedAt DESC, id DESC. |

Movement qatoridagi `Info` tugmasi:

```http
GET /api/materials/{materialId}/command-view/movements/{movementId}/allocations?warehouseId={warehouseId}&page=0&size=25
```

Bu endpoint hujjat miqdorini (`documentQuantity`) va amalda posting qilingan signed
allocation delta'larini alohida beradi. `SOURCE` uchun manba, `TARGET` uchun qabul
tomonining ombor/partiya/joylashuv snapshotlari qaytadi. Ichki `costLayers` massivida
qaysi tannarx qatlamlari yaratilgani, iste'mol qilingani yoki tiklangani ko‘rsatiladi.
Ombor filtri boshqa tomonning allocationlarini chiqarmaydi. Scope ichida allocation
topilmasa 404; ombor filtri bo‘lmagan legacy/draft movement uchun dalil yo‘qligi
`allocationStatus=UNAVAILABLE` bilan bildiriladi. Imported history yozuvlari bu operatsion
endpointdan qaytarilmaydi; mavjud imported movement detail endpointi saqlanadi.

## Response semantikasi

- `material`: ID, kod, nom, o‘lchov birligi ID.
- `warehouseId`, `stockBatchId`: server qo‘llagan scope.
- `asOf`, `calculatedAt`, `historical`: tanlangan sana va hisoblash vaqti.
- `summary.onHand/available/reserved/quarantine`: fizik qoldiq ko‘rsatkichlari.
- `summary.totalBookValue`: qoldiqning kompaniya hisob valyutasidagi qiymati; hisob valyutasi sozlanmagan bo‘lsa NO_DATA.
- `summary.costLayerQuantity`: tannarx qatlamlari bo‘yicha miqdor.
- `summary.quantityWithoutCostLayers`: max(onHand − costLayerQuantity, 0).
- `summary.costLayerQuantityDifference`: onHand − costLayerQuantity; manfiy qiymat ham qaytadi.
- `summary.costLayerBookValue`, `bookValueDifference`: faqat qatlamlar bir xil
  kompaniya hisob valyutasida bo‘lsa mavjud. Turli valyutalar bitta pul summasi qilib berilmaydi.
- `origins`: `content/page/size/totalElements/totalPages`; pagination summary'ni o‘zgartirmaydi.

Har bir origin:

| Maydon | Mazmuni |
| --- | --- |
| `costLayerId`, `stockBatchId`, `batchNumber` | Bitta partiya ichida bir nechta kirim bo‘lishi mumkin. Qator identifikatori costLayerId. |
| `receivedQuantity` | Shu ombordagi qatlamning dastlabki miqdori. |
| `remainingQuantity` | Joriy yoki tarixiy sana uchun hisoblangan qatlam qoldig‘i. |
| `warehouseReceivedDate` | Shu omborga kelish harakatining biznes sanasi; bo‘lmasa postedAt sanasi. |
| `originalReceivedDate` | To‘liq parent zanjiri orqali topilgan asl RECEIPT sanasi. Opening balans sanasi asl kelish sanasi deb berilmaydi. |
| `sourceWarehouse` | Bevosita jo‘natuvchi ombor. |
| `supplier` | Asl kirim yetkazib beruvchisi; ruxsatga bog‘liq. |
| `receiptDocument`, `originalMovementId` | Asl kirim dalili; hujjatlarni o‘qish ruxsatiga bog‘liq. |
| `purchaseUnitPrice`, `purchaseExchangeRate` | Posting vaqtida saqlangan original valyutadagi xarid narxi va kurs. Eski snapshot yo‘q bo‘lsa narx NO_DATA. |
| `receiptUnitCost` | Asl kirim harakatining o‘sha paytdagi hisob valyutasidagi birlik tannarxi. |
| `currentUnitCost` | Qolgan qatlam qiymati / qolgan miqdor; 6 kasr xonaga yaxlitlanadi. Miqdor 0 bo‘lsa NO_DATA. |
| `remainingBookValue` | Qatlamning saqlangan yoki qayta tiklangan qolgan qiymati; unitCost × quantity orqali qayta hisoblanmaydi. |

Miqdorlar va pul qiymatlari JSON string ko‘rinishida beriladi. Pul va umumiy miqdor
metrikalari `value/currencyCode/status` yoki `value/status` formatida.
`DENIED` ruxsat yo‘qligini, `NO_DATA` dalil yo‘qligini bildiradi. Ikkalasini ham UI'da 0 deb ko‘rsatmaslik kerak.

## Kelib chiqish ishonchliligi

- `VERIFIED`: to‘liq parent zanjiri, posting qilingan asl RECEIPT, aniq GoodsReceipt
  yoki GoodsReceiptBatch orqali bog‘langan kirim va mavjud supplier bor.
- `PARTIAL`: asl kirim harakati bor, ammo to‘liq receipt/supplier dalili yo‘q.
  Eski PurchaseOrder referensi orqali topilgan supplier shu holatga kiradi.
- `UNKNOWN`: opening balans, singan/cyclic/64 qadamdan uzun parent zanjiri yoki asl
  RECEIPT dalili yo‘q. Noma'lum supplier va sana taxmin qilinmaydi.

Ancestry tenant/company/material bo‘yicha chegaralanadi. Barcha qatorlarning parentlari
har darajada bitta batch query bilan olinadi; har bir qator uchun alohida so‘rov yo‘q.

**Quantity basis doim `COST_LAYER_ALLOCATION`.** Qatlam miqdorlari mavjud FIFO/LIFO/
o‘rtacha tannarx siyosatiga asoslanadi. Ayniqsa o‘rtacha tannarxda ularni aniq fizik
buyumning supplier identifikatori deb ko‘rsatish mumkin emas. Alohida fizik serial/lot
provenance talab qilinsa, shu tracking bo‘yicha alohida allocation modeli kerak bo‘ladi.
Rezerv va karantin umumiy summary'da qaytadi; ular dalilsiz supplierlar orasida bo‘linmaydi.
Owner/category/location bo‘yicha fizik qoldiq tafovutlari umumiy reconciliation'da ko‘rinadi;
cost layer'lardan bu kesimlar taxmin qilinmaydi.

## Tarixiy sana

Bugundan oldingi sanada miqdor va qiymatlar immutable movement allocation hamda
cost-lineage delta'lari yig‘indisidan tiklanadi. Biznes sanasi movementDate, bo‘lmasa
postedAt sanasi; shuning uchun keyinroq posting qilingan backdated hujjat eski sana
natijasini o‘zgartirishi mumkin. Bu o‘sha paytda foydalanuvchi nimani ko‘rganining audit snapshoti emas.

Original va reversal ikkalasi ham signed delta sifatida saqlanadi. Originalning
keyingi statusi sababli tarixdan chiqarib tashlanmaydi.

Tarixiy hisobdan oldin har bir balans va qatlamning barcha delta'lari joriy saqlangan
miqdor va qiymatlarga mosligi tekshiriladi. Allocation'siz eski posted movement,
alohida account-aware STOCK posting, o‘chirilgan qator, qo‘shimcha qiymat/rezerv
o‘zgarishi bilan reconciliation yetishmasa:

```json
{
  "dataStatus": "UNAVAILABLE",
  "summary": {"onHand": {"value": null, "status": "NO_DATA"}},
  "origins": {"content": [], "page": 0, "size": 25, "totalElements": 0, "totalPages": 0},
  "warnings": ["HISTORICAL_EVIDENCE_INCOMPLETE"]
}
```

Bu parchada faqat muhim maydonlar ko‘rsatilgan. Joriy holatda bunday eski qoldiq
ko‘rinadi, cost layer bilan qoplanmagan qismi alohida hisoblanadi.

## Ruxsatlar va cache

Mavjud MaterialCommandViewScopeGuard va section policy qayta ishlatilgan. INVENTORY
ruxsati majburiy; VALUATION, SUPPLIERS, DOCUMENTS alohida tekshiriladi.
Narxga ruxsat yo‘q bo‘lsa barcha narxlar/qiymatlar DENIED va kurs null;
supplier/hujjat ruxsati yo‘q bo‘lsa tegishli obyektlar null. Javoblar `Cache-Control: no-store`.

## Migratsiya va chiqarish

`V52__material_receipt_detail_snapshots.sql`:

1. StockMovement'ga nullable `source_unit_price_snapshot` qo‘shadi.
2. GoodsReceiptBatch'ga nullable `stock_movement_id` va lookup indeksi qo‘shadi.
3. Mavjud yozuvlarning miqdori/narxini o‘zgartirmaydi va bugungi PO narxidan tarixni backfill qilmaydi.

PurchaseOrderService → ProcurementStockPostingPort → posting adapter yangi kirimning
original birlik narxini uzatadi. Receipt posting xizmati har bir batch movementId'sini
saqlaydi. Oldingi port konstruktorlari bilan source compatibility saqlangan.

Frontendda tavsiya etilgan ustunlar: manba, shu omborga kelgan sana, asl kelgan sana,
qolgan miqdor, xarid narxi va valyutasi, joriy tannarx, qoldiq qiymati, hujjat.
Transfer draft uchun sending warehouse scope ishlatiladi; posting qilingan hujjat
uchun movement allocations ko‘rsatiladi. Draftning joriy stock preview'i haqiqiy
posting allocationi sifatida belgilanishi kerak emas.

## Tekshirish

```sh
mvn -q -Dtest=MaterialStockDetailTest,MaterialStockDetailPostgresTest,MaterialStockDetailHttpTest,WarehouseReceiptPostingServiceTest,PurchaseOrderReceiptEvidenceTest,ProcurementStockPostingBoundaryTest,MaterialCommandViewArchitectureTest,MaterialCommandViewApiContractTest test
```

PostgreSQL testlar Docker mavjud bo‘lganda ishga tushadi. Bu API frontend kodini
o‘zgartirmaydi; Info tugmalarini yuqoridagi endpointlarga ulash kerak.
