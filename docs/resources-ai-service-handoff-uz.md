# Resurslar: AI xizmati uchun integratsiya topshirig‘i

Sana: 2026-11-12

Holat: **taklif etilgan kontrakt; endpointlar hali mavjud deb hisoblanmaydi**

Ijrochi: AI xizmati dasturchisi

## 1. Mahsulot oqimi

ERPda `Resurslar` sahifasi bo‘ladi. Joriy tenant/kompaniyadagi **har bir autentifikatsiyalangan foydalanuvchi** bir yoki bir nechta fayl yuklay oladi. Har fayl alohida resurs va alohida 1-versiya bo‘ladi. Foydalanuvchi yozgan nom ustuvor; bo‘sh qoldirilsa ERP fayl nomidan nom hosil qiladi. Nom bo‘sh qolishi mumkin emas. AI bu nomni qayta yozmaydi.

Fayl xavfsizlik tekshiruvidan o‘tgach ERP uni shu kompaniyaning barcha foydalanuvchilariga darhol ko‘rinadigan qiladi. **AI indekslash tugashini kutib nashrni bloklamaydi.** Indeks tayyor bo‘lguncha kartochka va download ishlaydi, `AI tayyorlanmoqda` holati ko‘rinadi. Indeks xato qilsa resurs o‘qilishi davom etadi; Copilot undan javob olgandek ko‘rsatmaydi. Zararli/xavfsiz emas fayl nashr qilinmaydi.

Mavjud `/v1/copilot/messages` yordamchisi ishlatiladi. Misol: “1749-buyruqda ombor inventarizatsiyasi haqida nima deyilgan?” Yordamchi shu kompaniyaning nashr etilgan hujjatlaridan tegishlisini topib, aynan ishlatilgan versiya va sahifa/bandga tayangan javob beradi. `Resursdan so‘rash` tugmasi aniq `resourceId`ni kontekstga qo‘yadi. Matnda faqat nom kelsa nomni hujjatlar katalogi bilan solishtiradi; bir nechta mos hujjat bo‘lsa aniqlashtirish so‘raydi.

## 2. AI jamoasining aniq vazifasi

1. ERP yuborgan **bitta nashr etilgan versiya** originalini qabul qilish; PDF, skaner PDF, DOCX, TXT va MD matnini/OCRni chiqarish, bo‘lim va sahifa/paragraf lokatorini saqlash.
2. Tenant, company, resource, version, content hash va indeks avlodi bilan belgilangan qidiruv indeksini yaratish. Qayta yuborish va worker restarti duplikat qoldirmasligi kerak.
3. Versiya `CURRENT`, `HISTORICAL` yoki `BLOCKED` bo‘lganda qidiruv eligibilitysini sinxronlashtirish. `BLOCKED` darhol qidiruvdan chiqadi; `HISTORICAL` faqat aniq tarixiy so‘rovda ishlatiladi.
4. Mavjud Copilotga resurs qidiruvi va manbali javobni qo‘shish. Bir hujjat bo‘yicha savol, nom bilan hujjatni aniqlash va shu kompaniya kutubxonasi bo‘yicha umumiy savolni qo‘llash.
5. Har muhim da’vo uchun haqiqiy `citations[]` qaytarish; model yaratgan ID yoki lokatorni serverdagi topilgan parcha bilan tekshirish. Dalil yetmasa ochiq aytish.

AI xizmati ERP foydalanuvchi huquqi, fayl upload, ko‘rinish/nashr, nom ustuvorligi, attachment download yoki chat egasini boshqarmaydi. ERP ma’lumotlar bazasini bevosita o‘qishi shart emas. Hujjatdagi ko‘rsatmalar tizim instructioni emas, ma’lumot sifatida ko‘riladi. Alohida chatbot yoki alohida chat tarixi yaratilmaydi.

## 3. Xizmatlararo kontrakt — kelishish va amalga oshirish uchun

Quyidagi yo‘llar **yangi endpoint taklifi**. Nomlar kelishuvda o‘zgarsa, ERP jamoasiga yakuniy OpenAPI va misollar berilsin. Har chaqiruvda mavjud service `X-API-Key`, `X-Tenant-Id`, `X-Company-Id` ishlatiladi. Headerlar va metadata tenant/companysi mos bo‘lmasa rad etiladi. Brauzer AI endpointini to‘g‘ridan-to‘g‘ri chaqirmaydi.

### 3.1 Versiyani indekslash

`POST /v1/resource-knowledge/versions/index` — `multipart/form-data`: `metadata` JSON va bitta `file` part. ERP faqat xavfsizligi tasdiqlangan, nashr etilgan versiyani yuboradi.

```json
{
  "jobId": "<UUID>",
  "tenantId": "<tenant>",
  "companyId": "<UUID>",
  "resourceId": "<UUID>",
  "versionId": "<UUID>",
  "title": "Inventarizatsiya bo‘yicha 1749-buyruq",
  "versionLabel": "1",
  "fileName": "1749.pdf",
  "contentType": "application/pdf",
  "sha256": "<64 hex>",
  "language": "uz",
  "knowledgeRevision": 12,
  "indexGeneration": 1
}
```

`jobId` va `(tenantId, companyId, versionId, sha256, indexGeneration)` bir xil bo‘lsa retry o‘sha ishni davom ettiradi yoki natijani qaytaradi. Shu `jobId` boshqa bytes/metadata bilan kelsa `409`. AI file hash va MIME/signaturani tekshiradi. Original faylni umumiy logga yozmaydi. Boshlang‘ich hajm/sahifa/vaqt limitlari ERP bilan kelishiladi; AI aniq `TOO_LARGE`, `UNSUPPORTED_FORMAT`, `OCR_FAILED`, `INDEX_FAILED` kabi kodlarni qaytaradi.

Qabul javobi: `202 {"jobId":"<UUID>","status":"QUEUED"}`. Natija uchun `GET /v1/resource-knowledge/jobs/{jobId}`:

```json
{
  "jobId": "<UUID>",
  "tenantId": "<tenant>",
  "companyId": "<UUID>",
  "resourceId": "<UUID>",
  "versionId": "<UUID>",
  "status": "READY",
  "indexGeneration": 1,
  "sectionCount": 18,
  "chunkCount": 64,
  "errorCode": null
}
```

Holatlar: `QUEUED`, `RUNNING`, `READY`, `FAILED`. Polling faqat tegishli tenant/company va service credentialga natija beradi. Worker lease, retry va idempotency bilan ishlaydi. Eski avlodning kechikkan natijasi yangi avlodni almashtirmaydi. `READY` faqat to‘liq indeks atomar tayyor bo‘lganda beriladi.

### 3.2 Versiya qidiruv holatini yangilash

`PUT /v1/resource-knowledge/versions/{versionId}/visibility`:

```json
{
  "resourceId": "<UUID>",
  "knowledgeRevision": 13,
  "state": "HISTORICAL"
}
```

`CURRENT` — oddiy savollarda ishlatiladi; `HISTORICAL` — faqat aniq tarixiy versiya yoki tarixiy sana so‘ralganda; `BLOCKED` — xavfsizlik/karantin sababli hech qachon ishlatilmaydi. ERP yagona holat egasi. Yangiroq revisionni eski xabar ortga qaytarmaydi. `BLOCKED` uchun cache invalidation darhol ishlaydi. Bu endpoint faylni fizik o‘chirmaydi.

### 3.3 Mavjud Copilot so‘rovini kengaytirish

`POST /v1/copilot/messages`ning mavjud `runId`, `conversationId`, `executionMode`, `message`, `locale`, `status`, `answer`, `cards`, `actions`, `warnings` va polling xatti-harakati saqlanadi. ERP tekshirgan ixtiyoriy `knowledgeScope` qo‘shiladi:

```json
{
  "runId": "run_123",
  "conversationId": "<UUID>",
  "executionMode": "poll",
  "message": "1749-buyruqda inventarizatsiya muddati nima?",
  "locale": "uz",
  "knowledgeScope": {
    "mode": "RESOURCE",
    "resourceId": "<UUID>",
    "versionId": null,
    "knowledgeRevision": 13
  }
}
```

`RESOURCE` rejimida `resourceId` majburiy va faqat shu resurs qidiriladi. `versionId` bor bo‘lsa aynan shu nashr etilgan versiya tekshiriladi; tarixiy bo‘lsa javobda belgi chiqadi. IDsiz `RESOURCES` rejimida faqat shu kompaniyaning `CURRENT + READY` resurslari qidiriladi. `knowledgeScope` kelmasa `AUTO`: mavjud Copilot oqimi saqlanadi, lekin savolda hujjat nomi aniq tilga olinsa resurs title indeksidan topib hujjat savoliga yo‘naltiradi. Bir xil yoki noaniq nomda nomzodlar bilan aniqlashtirish qaytariladi, bitta hujjat taxminan tanlanmaydi. Operativ ERP savollarining amaldagi access qoidalari saqlanadi. Bir `runId` bilan boshqa `knowledgeScope` yuborilsa `409`.

Terminal javobda oddiy foydalanuvchiga ham quyidagi maydonlar keladi:

```json
{
  "runId": "run_123",
  "status": "COMPLETED",
  "answer": "...",
  "answerMode": "RESOURCE_DOCUMENT",
  "knowledgeRevision": 13,
  "citations": [
    {
      "resourceId": "<UUID>",
      "versionId": "<UUID>",
      "sectionId": "4.2",
      "locator": {"kind": "PDF_PAGE", "page": 3},
      "excerpt": "...",
      "historical": false
    }
  ],
  "cards": [],
  "actions": [],
  "warnings": []
}
```

Citationda URLni model yasamaydi; ERP tekshirib o‘zining scoped URLini quradi. PDFda haqiqiy PDF sahifasi, boshqa formatlarda mavjud bo‘lim/paragraf lokatori ishlatiladi. Iqtibos original matndan olinadi. `includeEvidence=false` yoki admin bo‘lmagan rol `citations`ni yashirmaydi. `evidence` texnik diagnostika bo‘lib qoladi. Savolga yetarli asos topilmasa javob buni aytadi va `citations` bo‘sh bo‘ladi; uydirma manba qaytarilmaydi. Tanlangan hujjat hali `READY` bo‘lmasa `RESOURCE_NOT_READY`, qidiruv muvaffaqiyatsiz bo‘lsa `RESOURCE_RETRIEVAL_FAILED` kabi aniq kod/status keladi.

## 4. Qidiruv va xavfsizlik invariantlari

- Har bir chunkda `tenantId`, `companyId`, `resourceId`, `versionId`, `sectionId`, locator, `sha256`, `indexGeneration` va visibility saqlanadi. Tenant/company hamda nashr eligibility filtri **top-k qidiruvdan oldin** qo‘llanadi.
- O‘qish/retrieval rol, bo‘lim, ombor yoki uploaderga qarab cheklanmaydi. `X-Tenant-Id`/`X-Company-Id`ni autentifikatsiyalangan ERP service request bilan moslashtiring; boshqa company IDsi orqali izlashga yo‘l qo‘ymang.
- `CURRENT` uchun faqat nashr etilgan, xavfsiz va `READY` avlodlar; `HISTORICAL` uchun faqat aniq tarixiy scope; draft/rejected/blocked hech qachon umumiy qidiruvga kirmaydi.
- Lotin/kirill, apostrof variantlari, RU, ko‘p ustun, jadval va skanlarni real namunalarda sinang. Qidiruv normallashtirilishi iqtibosning original matnini buzmasin.
- Hujjat ichidagi prompt injection tool chaqirishga, ERP operatsiyasini bajarishga yoki boshqa tenant ma’lumotini olishga sabab bo‘lmasin. AI xizmati xom fayl, maxfiy kalit yoki shaxsiy chatni logga chiqarmaydi.
- Cache kaliti kamida tenant, company, knowledgeRevision, resource/version scope, locale va savolni o‘z ichiga oladi; publish, archive, block va qayta indekslashda eskirgan javob ishlatilmaydi.

## 5. AI jamoasidan topshiriladigan natija

1. Yakuniy OpenAPI, indeks/visibility/Copilot request-response misollari va xato kodlari; ERP implementatsiyasiga ta’sir qiladigan kontrakt o‘zgarishi oldindan kelishiladi.
2. PDF, skan PDF, DOCX, TXT, MD bo‘yicha OCR/parser/indeks ishlashi, retry va revision/generation testlari.
3. Bir tenantdagi ikki company va ikki tenant orasidagi izolatsiya testi; bir companydagi turli rollar bir xil nashr korpusiga kira olishini tasdiqlash.
4. Bir xil nomli ikki hujjat, aniq `resourceId`, tarixiy versiya, indeks hali tayyor emas, javobsiz savol, uydirma citation va prompt injection testlari.
5. Real hujjatlar bilan citation lokatori va savolga javob sifatini baholash hisobotlari; qolgan cheklovlar va deployment konfiguratsiyasi.

ERP backend va frontend implementatsiyasi alohida rejalashtirilgan: [resources-erp-frontend-implementation-plan.md](resources-erp-frontend-implementation-plan.md). Ushbu hujjat AI xizmatining vazifalarini belgilaydi; mavjud endpointning amalga oshirilganini tasdiqlamaydi.
