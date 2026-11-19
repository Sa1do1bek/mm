# Backend ishlab chiquvchisi uchun hisobot: Material Matching va ombor hisobvarag‘i

**Holat:** amalga oshirish topshirig‘i. Quyidagi yangi endpointlar va DTOlar kelishish uchun taklif qilinadi. Amaldagi APIlar bilan moslik saqlansin.

## Maqsad va mavjud tayanch

`/app/mxik-reconciliation` uchun materialga yo‘naltirilgan o‘qish shartnomasi, qo‘lda kirimda 1C asosidagi hisobvaraq yechimi va AI tavsiyani qo‘llash shartnomasi kerak. MXIK ixtiyoriy. Qo‘lda TMB kirimiga yaroqli materialda ombor hisobvarag‘i talab etiladi.

Hozir `StockAccountResolver.findManualReceiptAccount()` faqat `Material.manualReceiptStockAccountId`ni o‘qiydi. `WarehouseStockFactService` kirimni post qilishda uning kodini `accountCodeSnapshot`ga yozadi. `WarehouseStockFactRequest.Line`da kirim qatoriga xos hisobvaraq yo‘q. `MxikReconciliationService.MaterialRow`da faqat MXIK holati bor. 1C import va material linklari boshqa hisobotlarda ishlatiladi, ammo ularni yangi deterministik qoida bilan bog‘lash kerak.

## 1. Hisobvaraq yechish qoidasi

Backend joriy tenant va kompaniyada tegishli **oxirgi yakunlangan 1C `BALANCE` suratini** tanlaydi. Faqat haqiqiy materialga bog‘langan satrlar va kompaniyaning faol, postable, `002` bo‘lmagan hisobvaraqlari nomzod bo‘ladi. Bir material va hisobvaraq bir necha omborda uchrasa, hisobvaraq nomzodi bir marta sanaladi, omborlar esa dalilda qoladi.

| Nomzodlar | `status` | `effectiveAccount` | Kirim qoidasi |
| --- | --- | --- | --- |
| Bitta | `AUTO_1C` | Shu 1C hisobvarag‘i | Server avtomatik qo‘llaydi, materialga yozmaydi |
| Bir nechta | `CHOICE_REQUIRED` | `null` | Shu nomzodlardan birining receipt-specific IDsi kerak |
| Nol, material hisobvarag‘i saqlangan | `CONFIGURED_FALLBACK` | Saqlangan hisobvaraq | U qo‘llanadi |
| Nol, saqlangan hisobvaraq yo‘q | `ACCOUNT_REQUIRED` | `null` | Hisobvaraq materialga alohida tasdiq bilan saqlanishi kerak |
| Qo‘lda TMB kirimiga yaroqsiz | `NOT_APPLICABLE` | `null` | Missing account hisobiga qo‘shilmaydi |

`configuredAccount` va `effectiveAccount` alohida qaytarilsin. 1C bitta hisobvaraqni berganda eski saqlangan hisobvaraq boshqacha bo‘lishi mumkin; UI ikkalasini ko‘rsatadi. Bir nechta import nomzodi bo‘lsa saqlangan hisobvaraq tanlovni avtomatik hal qilmaydi. MXIK holati bu hisobvaraq holatidan mustaqil.

Surat tanlashda `tenant_id`, `company_id`, tashkilot/source partitioni, `status='COMPLETED'`, `snapshot_date` va bir sanada bir nechta import bo‘lsa deterministik tie-break qoidasidan foydalanilsin. `warehouse_source_master_links` yoki yozuvdagi haqiqiy `material_id` orqali link qilinsin. Synthetic/unmapped ID, `MOVEMENT`, eski surat, chala import yoki boshqa kompaniya qoldig‘i nomzod bo‘lmasin. Nol/manfiy `BALANCE` satrining nomzodga ta’siri biznes qarori sifatida yakunlansin.

## 2. Read API va Material Matching ro‘yxati

Mavjud `/api/mxik-reconciliation/materials` va `/materials/{id}` URLlari saqlansin. Ro‘yxatning `missingOnly` parametri eski mijozlar uchun ishlashda qolsin; yangi mijoz uchun mustaqil `mxikStatus` va `accountStatus` filtrlari yoki ekvivalent aniq filtr shartnomasi qo‘shilsin. `All`, `Missing MXIK`, `Missing stock account`, `Both set` semantikasi SQL queryda bajarilsin. Jami son, sahifalash va barqaror `(name,id)` tartib to‘g‘ri bo‘lsin. Har material uchun alohida 1C querydan qochilsin.

Ro‘yxat/detail javobida kamida `id`, `code`, `name`, `materialType`, `mxikCode`, `mxikName`, `materialVersion`, `accountApplicable`, `accountStatus`, `configuredAccount`, `effectiveAccount`, `effectiveSource`, `candidateCount` bo‘lsin. Detailda `candidateAccounts`, `snapshotImportId`, `snapshotDate`, `evidenceFingerprint` va izoh berilsin. Bir xil account resolution service Material Matching va receipt uchun ishlasin.

Backendda qo‘shilgan o‘qish endpointlari:

```text
GET /api/mxik-reconciliation/matching-materials?filter=ALL&mxikStatus=SET&accountStatus=AUTO_1C&page=0&size=25
GET /api/materials/{materialId}/manual-receipt-stock-account/resolution
GET /api/materials/{materialId}/manual-receipt-stock-account/evidence?page=0&size=25
```

```json
{
  "materialId": "uuid",
  "materialVersion": 12,
  "applicable": true,
  "status": "CHOICE_REQUIRED",
  "configuredAccount": { "id": "uuid", "code": "1010", "name": "Materiallar" },
  "effectiveAccount": null,
  "effectiveSource": null,
  "candidateAccounts": [
    { "id": "uuid", "code": "1010", "name": "Materiallar", "source": "ONE_C_BALANCE" },
    { "id": "uuid", "code": "1030", "name": "Materiallar", "source": "ONE_C_BALANCE" }
  ],
  "snapshot": { "importId": "uuid", "snapshotDate": "2026-11-12", "completed": true },
  "evidenceFingerprint": "sha256",
  "reason": "Oxirgi yakunlangan 1C qoldig‘ida ikkita yaroqli hisobvaraq bor"
}
```

`candidateAccounts` faqat tegishli oxirgi suratning yaroqli hisobvaraqlari. Dalil sahifasi manba/import/entry ID, material, hisobvaraq, ombor, sana va to‘liqlik belgisini beradi. O‘qish huquqi har manbaning ruxsatiga mos tekshirilsin. Boshqa ruxsat talab qiladigan procurement/supplier dalili umumiy resolution javobiga chiqmasin.

## 3. Qo‘lda kirim va material sozlamasi

Mavjud `POST /api/workspaces/warehouse-execution/manual/stock-receipt` multipart shartnomasida `request.lines[]`ga ixtiyoriy `stockAccountId` va AI qo‘llangan bo‘lsa `recommendationJobId` qo‘shish taklif etiladi. Mavjud mijoz maydonlarni yubormasa, `AUTO_1C` yoki `CONFIGURED_FALLBACK` holatlarida post ishlasin. `CHOICE_REQUIRED`da tanlov majburiy. `ACCOUNT_REQUIRED`da foydalanuvchi avval material fallbackni aniq saqlashi kerak.

```json
{
  "materialId": "uuid",
  "quantity": 2,
  "unitCost": 1000,
  "serialNumbers": [],
  "stockAccountId": "uuid",
  "recommendationJobId": "uuid"
}
```

Posting tranzaksiyasi ichida material, 1C nomzodlar va hisobvaraq yaroqliligi qayta tekshirilsin. Tanlangan hisobvaraq faqat ruxsat etilgan holatda qabul qilinsin. Hisobvaraq kodi yangi `StockMovement.accountCodeSnapshot`ga yozilsin. Oldingi harakatlarga tegilmasin. Idempotency fingerprint yangi tanlov maydonlarini qamrasin. Qayta yuborish, parallel sozlama o‘zgarishi va import yangilanishi izchil natija bersin.

Material fallbackni saqlash uchun versiyali alohida amal taklif etiladi:

```text
PUT /api/materials/{materialId}/manual-receipt-stock-account
```

```json
{
  "accountId": "uuid",
  "expectedMaterialVersion": 12,
  "expectedEvidenceFingerprint": "sha256",
  "recommendationJobId": "uuid"
}
```

`recommendationJobId` bo‘sh bo‘lsa qo‘lda tanlov. Mavjud `PUT /api/stock-account-resolution/manual-receipt/materials/{materialId}` mijozlari ishlashda qolsin yoki bir vaqtda ko‘chirish shartnomasi taqdim etilsin. Eski marshrutni yangi AI tasdiqlash deb talqin qilmaslik kerak. Materialni bloklab, expected versionni va `ACCOUNT_REQUIRED` holatini tekshirgandan keyin sozlama saqlansin. `CONFIGURED_FALLBACK`ni o‘zgartirish zarur bo‘lsa, bu alohida aniq foydalanuvchi tahriri sifatida ko‘rilsin.

## 4. AI job va qo‘llash

AI jamoasi uchun so‘rov/javobning to‘liq namunasi `material-matching-ai-developer-report-uz.md`da. Backend quyidagilarni bajaradi:

1. Read/suggest/apply huquqlarini serverda ajratadi. Material Matching uchun mavjud MXIK ruxsatlari saqlanadi. Warehouse receipt rolini va har bir dalil manbasini alohida tekshiradi.
2. `AiAnalysisJobService`, worker, `MmAiClient` va mavjud `GET /api/ai-analysis-jobs/{jobId}` pollingini yangi `MATERIAL_STOCK_ACCOUNT_RECOMMENDATION` turiga kengaytiradi. AI unavailable holatini availability endpointi qaytaradi.
3. Material, 1C, operatsion qoldiq, procurement, supplier va ruxsat etilgan tarixdan cheklangan dalil paketini yig‘adi. `allowedAccounts` backend deterministik holatidan olinadi. Dalillar uchun source ID, sana, completeness va fingerprint saqlanadi.
4. AI javobidagi job/material ID, versiya, fingerprint, status, hisobvaraq ID/kod va evidence refsni tekshiradi. Uydirma, nofaol, boshqa kompaniya, `002` yoki nomzoddan tashqari hisobvaraq job xatosiga olib keladi. `NO_RELIABLE_SUGGESTION` esa muvaffaqiyatli natija.
5. Qo‘llash paytida yana joriy material va evidence fingerprint, jobning `COMPLETED` holati, ayni material va foydalanuvchi huquqlarini tekshiradi. Eski tavsiya uchun `409`, yaroqsiz tanlov uchun mos `4xx` qaytaradi. Audit `MANUAL` va `AI_ASSISTED`ni, job va dalil identifikatorlarini ajratadi.

**Taklif etilayotgan job marshrutlari:**

```text
GET  /api/materials/{materialId}/ai/stock-account/availability
POST /api/materials/{materialId}/ai/stock-account/jobs
GET  /api/ai-analysis-jobs/{jobId}  (mavjud)
```

Jobni boshlash tavsiyani qo‘llamaydi. AI xatosi qo‘lda hisobvaraq tanlashni to‘xtatmaydi.

## 5. Tekshiruv va topshirish

- Bir, ko‘p va nol 1C nomzodi; saqlangan hisobvaraq bilan farq; bir sanada ko‘p import; eski/chala/unlinked import; yaroqsiz `002`/nofaol hisobvaraq.
- Tenant/kompaniya ajratilishi, hujjat manbalari ruxsati, sahifalash/jami son, material turi va MXIK filtrining mustaqilligi.
- Postingdagi receipt-specific tanlov, account snapshot, eski mijozlar mosligi, idempotency, parallel o‘zgarish va audit.
- AI natijasidagi uydirma account/dalil, no suggestion, unavailable, eskirgan material yoki dalil, qayta qo‘llash va `409`.
- Fokusli backend testlar va build. Backend shartnomasi frontenddan oldin tayyorlansin.

**Ochiq biznes qarorlari:** bir sanada bir nechta importning ustuvorligi, nol/manfiy qoldiq nomzod bo‘lishi, receipt-specific AI jobni postingda qanday audit qilish va dalil manbalari bo‘yicha aniq rol jadvali. Bu qarorlar kodlashda tasodifiy tanlanmasin.

**Kod tayanchlari:** `mm-backend/src/main/java/com/enterprise/mm/service/StockAccountResolver.java`, `WarehouseStockFactService.java`, `MxikReconciliationService.java`, `AiAnalysisJobService.java`, `AiAnalysisJobWorker.java`; `payload/WarehouseStockFactRequest.java`; `controller/StockAccountResolutionController.java`, `WarehouseStockFactController.java`, `MxikReconciliationController.java`; `warehouse/reports/WarehouseAccountReportQuery.java`.
