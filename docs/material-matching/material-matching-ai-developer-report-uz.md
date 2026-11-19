# AI ishlab chiquvchisi uchun hisobot: material ombor hisobvarag‘i tavsiyasi

**Holat:** AI va backend jamoalari kelishishi uchun alohida texnik shartnoma loyihasi. Bu endpoint va JSON shakllari hozirgi tizimda tayyor deb hisoblanmaydi.

**Funksiya:** Material Matching (`/app/mxik-reconciliation`) va qo‘lda material kirimi bitta AI tavsiya xizmatidan foydalanadi. AI materialga mos ombor hisobvarag‘ini **taklif qiladi**. Yakuniy qo‘llash, kirimni post qilish va audit backend hamda foydalanuvchi amallari orqali bajariladi.

## 1. Biznes qarori va AI vazifasi

Backend AI chaqirilishidan oldin 1C qoldig‘i bo‘yicha deterministik holatni aniqlaydi:

| Backend holati | Hisobvaraq qoidasi | AI vazifasi |
| --- | --- | --- |
| `AUTO_1C` | Oxirgi tegishli yakunlangan, materialga bog‘langan 1C `BALANCE` suratida bitta yaroqli hisobvaraq bor. Backend uni amaldagi hisobvaraq sifatida oladi. | Boshqa hisobvaraq tavsiya qilinmaydi. |
| `CHOICE_REQUIRED` | Xuddi shu suratda bir nechta yaroqli hisobvaraq bor. Foydalanuvchi aynan shu kirim qatori uchun bittasini tanlaydi. | Faqat backend yuborgan shu import nomzodlaridan birini tavsiya qilish yoki ishonchli tavsiya yo‘qligini bildirish. |
| `CONFIGURED_FALLBACK` | Yaroqli import nomzodi yo‘q, materialda hisobvaraq aniq saqlangan. | Mavjud sozlama qo‘llanadi. Boshqa hisobvaraqni avtomatik almashtirish yo‘q. |
| `ACCOUNT_REQUIRED` | Yaroqli import nomzodi ham, materialda saqlangan hisobvaraq ham yo‘q. | Kompaniyaning yaroqli hisobvaraqlaridan material uchun fallback sozlama sifatida bittasini taklif qilish yoki ishonchli tavsiya yo‘qligini bildirish. |
| `NOT_APPLICABLE` | Material turi qo‘lda TMB kirimiga yaroqsiz. | Hisobvaraq tavsiyasi berilmaydi. |

Bu holatlar AI jamoasi yuborgan `STOCK_ACCOUNT_RECOMMENDATION.md` shartnomasida tasdiqlangan. Backend kompaniya bo‘yicha eng katta `snapshot_date`li `COMPLETED` importlarni oladi va nol/manfiy qoldiq satrlarini ham nomzod sanaydi. AI bu qarorni o‘zi chiqarmaydi.

MXIK ixtiyoriy va ombor hisobvarag‘i tavsiyasini majburiy ravishda belgilamaydi. Yetkazib beruvchi nomining o‘zi ham hisobvaraqni isbotlamaydi. AI hisobvaraq, material, hujjat, narx yoki dalilni to‘qib chiqarmasligi kerak.

## 2. Backend → AI chaqiruvi

Foydalanuvchi **Suggest account**ni bosadi. Backend foydalanuvchi huquqlarini, materialni va kompaniyani tekshiradi, dalillarni yig‘adi va mavjud AI job mexanizmida ish boshlaydi. AI xizmatiga faqat backend murojaat qiladi. Brauzer nomzodlar yoki dalillarni AIga bevosita yubormaydi.

Backend `POST /v1/stock-account-recommendation/recommend` marshrutiga so‘rov yuboradi. Autentifikatsiya `X-API-Key`, tenant/kompaniya scope esa header va so‘rov konteksti orqali beriladi. `CHOICE_REQUIRED` holatida avval saqlangan kirim qoralamasi asosida `CURRENT_OPERATION` dalili ham yuboriladi. Mazmuniy so‘rov quyidagicha:

```json
{
  "schemaVersion": 1,
  "task": "MATERIAL_STOCK_ACCOUNT_RECOMMENDATION",
  "jobId": "22222222-2222-4222-8222-222222222222",
  "language": "uz",
  "scope": {
    "tenantId": "tenant-a",
    "companyId": "33333333-3333-4333-8333-333333333333"
  },
  "material": {
    "id": "44444444-4444-4444-8444-444444444444",
    "version": 12,
    "code": "MAT-001",
    "name": "Material nomi",
    "type": "TMB",
    "subtype": "MATERIAL",
    "group": "Xomashyo",
    "unit": "kg",
    "description": "Qisqa tavsif",
    "mxikCode": null,
    "configuredAccountId": null
  },
  "resolution": {
    "status": "CHOICE_REQUIRED",
    "snapshotImportIds": ["55555555-5555-4555-8555-555555555555"],
    "snapshotDate": "2026-11-12",
    "evidenceFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "allowedAccounts": [
      { "id": "66666666-6666-4666-8666-666666666666", "code": "1010", "name": "Materiallar" },
      { "id": "77777777-7777-4777-8777-777777777777", "code": "1030", "name": "Materiallar" }
    ]
  },
  "evidence": [
    {
      "id": "1c:55555555-5555-4555-8555-555555555555:entry-1",
      "sourceType": "ONE_C_BALANCE",
      "sourceId": "entry-1",
      "sourceDate": "2026-11-12",
      "complete": true,
      "materialId": "44444444-4444-4444-8444-444444444444",
      "accountId": "66666666-6666-4666-8666-666666666666",
      "facts": {
        "warehouseId": "88888888-8888-4888-8888-888888888888",
        "quantity": "10.0000000"
      }
    },
    {
      "id": "current-operation:99999999-9999-4999-8999-999999999999",
      "sourceType": "CURRENT_OPERATION",
      "sourceId": "99999999-9999-4999-8999-999999999999",
      "sourceDate": "2026-11-12",
      "complete": true,
      "materialId": "44444444-4444-4444-8444-444444444444",
      "accountId": null,
      "status": "VALID",
      "operationContext": {
        "operationType": "RECEIPT",
        "purpose": "Uskunani ta’mirlash",
        "warehouseId": "88888888-8888-4888-8888-888888888888"
      },
      "facts": { "quantity": "2", "unitCost": "50000" }
    }
  ]
}
```

Misoldagi IDlar, sana, nom va miqdor namunaviy. `allowedAccounts` backend hisoblagan **yagona ruxsat etilgan tanlov doirasi**. AI ularni kengaytirmaydi. Hisobvaraq kodi va ID bir-biriga mos kelishi kerak. `evidenceFingerprint` material versiyasidan alohida bo‘lib, import, link, ruxsat etilgan dalil yoki nomzodlar o‘zgarganda tavsiyani eskirtirish uchun xizmat qiladi.

### Dalillar tarkibi

Backend quyidagi haqiqiy, foydalanuvchiga ochiq yozuvlardan tegishlisini saralab va hajmini chegaralab beradi:

- Oxirgi 1C `BALANCE` suratidagi bog‘langan material–hisobvaraq yozuvlari, import/surat sanasi va ombor. Oldingi 1C `MOVEMENT` yozuvlari tarixiy dalil bo‘lishi mumkin, ammo `allowedAccounts`ni kengaytirmaydi.
- Joriy operatsion qoldiq va tegishli MM harakatlari. Ularning miqdori 1C buxgalteriya qoldig‘idan alohida ko‘rsatiladi.
- Yangi qo‘lda kirimlarda kiritilgan ishlatilish maqsadi harakatga bog‘lanib saqlanadi; keyingi tavsiyalarda oldingi kirimning `operationContext.purpose` qiymati sifatida beriladi. Eski kirimlarda bunday maydon bo‘lmasligi mumkin.
- Materialga bog‘langan tasdiqlangan yoki yakunlangan shartnomalar, spetsifikatsiyalar, buyurtmalar va qabul qilingan kirimlar. Hisobvaraq faqat manbaning o‘zida mavjud bo‘lsa fakt sifatida beriladi.
- Materialga tegishli yetkazib beruvchi va yetkazish konteksti, foydalanuvchi uni ko‘ra olsa. Yetkazib beruvchi nomi yakka dalil hisoblanmaydi.
- Material xususiyatlari, mavjud saqlangan hisobvaraq, ruxsat etilgan oldingi qarorlar va aniq belgilangan o‘xshash material misollari.
- `CHOICE_REQUIRED` uchun saqlangan, foydalanuvchiga tegishli kirim qoralamasi: material, ombor, sana, ishlatilish maqsadi, miqdor va narx. `CURRENT_OPERATION.accountId` bo‘sh; tanlovni AI javobi bildiradi.

Har dalilda `id`, `sourceType`, haqiqiy manba IDsi, sana, `complete` va faqat kerakli faktlar bo‘lsin. Bekor qilingan, eski, chala yoki bir-biriga zid ma’lumotlar tegishli belgi bilan beriladi. Ruxsat bo‘lmagan procurement, supplier va moliyaviy ma’lumotlar so‘rovdan ham, AI javobidan ko‘rsatiladigan izohdan ham chiqariladi. Tokenlar, parollar va boshqa kompaniya ma’lumotlari yuborilmaydi. Dalil soni va matn uzunligi backend konfiguratsiyasida chegaralanadi.

## 3. AI → backend javobi

AI ikkita mazmuniy natijadan birini qaytaradi. Tavsiya mavjud bo‘lsa:

```json
{
  "schemaVersion": 1,
  "jobId": "22222222-2222-4222-8222-222222222222",
  "materialId": "44444444-4444-4444-8444-444444444444",
  "materialVersion": 12,
  "evidenceFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "status": "RECOMMENDED",
  "recommendedAccount": {
    "id": "66666666-6666-4666-8666-666666666666",
    "code": "1010"
  },
  "confidence": "MEDIUM",
  "reason": "Oxirgi 1C qoldig‘i va tasdiqlangan kirim shu hisobvaraqni qo‘llab-quvvatlaydi.",
  "evidenceRefs": ["1c:55555555-5555-4555-8555-555555555555:entry-1"],
  "conflicts": []
}
```

Dalil yetarli bo‘lmasa yoki ziddiyat hal qilinmasa:

```json
{
  "schemaVersion": 1,
  "jobId": "22222222-2222-4222-8222-222222222222",
  "materialId": "44444444-4444-4444-8444-444444444444",
  "materialVersion": 12,
  "evidenceFingerprint": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "status": "NO_RELIABLE_SUGGESTION",
  "recommendedAccount": null,
  "confidence": null,
  "reason": "Dalillar ikki hisobvaraq o‘rtasida ishonchli tanlovga yetmaydi.",
  "evidenceRefs": ["1c:55555555-5555-4555-8555-555555555555:entry-1"],
  "conflicts": ["1C va tasdiqlangan kirim hisobvaraqlari farq qiladi"]
}
```

`confidence` uchun `HIGH`, `MEDIUM`, `LOW` qiymatlari taklif etiladi; ularning baholash mezoni AI jamoasi bilan kelishiladi. `reason` qisqa va foydalanuvchi tilida yoziladi. `evidenceRefs` faqat so‘rovda berilgan `evidence[].id` qiymatlariga ishora qiladi. AI manba matnini tekshiruvsiz qayta nashr qilmaydi. `NO_RELIABLE_SUGGESTION` AI xatosi emas, muvaffaqiyatli va halol natija.

## 4. Backend javobni qanday tekshiradi

Backend jobni yakunlashdan oldin quyidagilarni tekshiradi:

1. `schemaVersion`, `jobId`, `materialId`, `materialVersion` va `evidenceFingerprint` ayni job so‘roviga mos.
2. `RECOMMENDED` bo‘lsa, `recommendedAccount.id` `allowedAccounts` ichida. Kod haqiqiy kompaniya hisobvarag‘i kodiga mos, hisobvaraq faol, postable va `002` emas.
3. Barcha `evidenceRefs` ayni foydalanuvchiga ruxsat etilgan so‘rov dalillari ichida. Izohda ruxsat etilmagan ma’lumot yo‘q.
4. `NO_RELIABLE_SUGGESTION` bo‘lsa, `recommendedAccount` bo‘sh. Noto‘g‘ri, uydirma yoki shartnomaga mos kelmagan javob jobni xato holatiga o‘tkazadi.

Backend tavsiyani saqlaganda job ID, ishlatilgan material versiyasi va dalil fingerprintini birga saqlaydi. Qo‘llash paytida ularni **qayta** tekshiradi. Material, nomzodlar, ruxsat yoki dalillar o‘zgarsa, eski tavsiya qo‘llanmaydi va frontendga konflikt javobi qaytadi.

## 5. Brauzer ↔ backend bog‘lanishi

Quyidagi marshrutlar backendda mavjud:

```text
GET  /api/materials/{materialId}/manual-receipt-stock-account/resolution
GET  /api/materials/{materialId}/ai/stock-account/availability
POST /api/workspaces/warehouse-execution/manual/stock-receipt/drafts
GET  /api/workspaces/warehouse-execution/manual/stock-receipt/drafts/{draftId}
POST /api/materials/{materialId}/ai/stock-account/jobs
GET  /api/ai-analysis-jobs/{jobId}
```

Brauzer qoralama yaratishda `warehouseId`, `materialId`, `movementDate`, `purpose`, `quantity`, `unitCost` yuboradi. Start so‘rovida `materialId` URLda, `draftId` JSON bodyda, til `Accept-Language` orqali beriladi. Dalil, tenant, kompaniya va `allowedAccounts` serverdan olinadi. Javob mavjud `AiAnalysisJobResponse` qobig‘ida `id`, `analysisType`, `entityType`, `entityId`, `status`, `result`, `errorCode` va `errorMessage` bilan keladi. `COMPLETED` jobning `result` maydonida 3-bo‘limdagi AI javobi bo‘ladi. `NO_RELIABLE_SUGGESTION` ham `COMPLETED` natijadir. `FAILED` holatida foydalanuvchi qo‘lda hisobvaraq tanlashda davom etadi.

Foydalanuvchi tavsiyani qo‘llaganda ikki xil amal bor:

| Kontekst | Backendga yuboriladigan tanlov | Natija |
| --- | --- | --- |
| `CHOICE_REQUIRED` | Qo‘lda kirimning tegishli `lines[]` qatorida `stockAccountId` va AI yordamida tanlangan bo‘lsa `recommendationJobId`, `accountDraftId`, `purpose` | Faqat shu kirim qatori hisobvarag‘i. Post paytida server qoralama, nomzod va jobni qayta tekshiradi, qoralamani bir marta ishlatilgan deb belgilaydi. |
| `ACCOUNT_REQUIRED` | Material fallbackni saqlash uchun alohida tasdiqlangan so‘rovda `accountId`, `expectedMaterialVersion`, `expectedEvidenceFingerprint`, `recommendationJobId` | Kelgusi kirimlar uchun materialning saqlangan hisobvarag‘i yangilanadi. Kirim avtomatik post qilinmaydi. |

Qo‘lda tanlashda `recommendationJobId` bo‘sh. Auditda qo‘lda va AI yordamidagi amal ajratiladi. AI jobning o‘zi hisobvaraqni yozmaydi. Qoralamadagi ombor, material, sana, maqsad, miqdor yoki narx o‘zgarsa avvalgi AI tanlovi rad etiladi. Xato yoki eskirgan tavsiya kirimdagi boshqa qatorlar va yuklangan fayllarni yo‘qotmasligi kerak.

## 6. Qabul qilish mezonlari va ochiq qarorlar

- Bir nechta 1C nomzodi bo‘lsa AI faqat ular ichidan ID qaytaradi. Bitta nomzod bo‘lsa backend avtomatik yechimi ustuvor. Import nomzodi yo‘q bo‘lsa AI faqat ruxsat etilgan kompaniya hisobvaraqlaridan fallback taklif qiladi.
- Foydalanuvchi ko‘ra olmaydigan hujjat AI kirishi va natija izohida qatnashmaydi. Uydirma ID, kod yoki dalil backendda rad etiladi.
- Bir xil so‘rov kontekstida job natijasi takroriy pollingda barqaror. Material yoki dalil o‘zgarsa tavsiya eskiradi. AI mavjud bo‘lmasa qo‘lda tanlash ishlaydi.
- 1C suratining aniq partition/tartibi, nol yoki manfiy qoldiqning nomzodga ta’siri, ichki AI endpoint va autentifikatsiya, payload chegarasi, timeout/retry hamda confidence mezoni backend va AI jamoalari o‘rtasida yakunlanadi.

**Hozirgi kod tayanchlari:** `MmAiClient`, `AiAnalysisJobService`, `AiAnalysisJobWorker`, `AiAnalysisJobController`, `StockAccountResolver`, `WarehouseStockFactRequest` va `WarehouseStockFactService` (`mm-backend/src/main/java/com/enterprise/mm/` ostida). Asosiy funksiyani backend/frontend bo‘yicha amalga oshirish rejasi alohida `material-matching-ai-implementation-uz.md` faylida.
