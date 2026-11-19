# Buyruqdan inventarizatsiya yaratish: AI ishlab chiquvchi uchun reja

Sana: 2026-11-12
Holat: taklif etilgan integratsiya shartnomasi; AI xizmatida joriy qilingani tasdiqlanmagan.
Manba: `inventory-order-implementation-plan.md` va amaldagi inventarizatsiya hujjatini ajratish shartnomasi.

## 1. AI vazifasining chegarasi

AI bir yoki bir nechta yuklangan buyruq va ilovani **to'liq, manbaga tayangan tuzilmali ma'lumot**ga aylantiradi. Har bir asl band, undagi bir nechta obyekt, vazifa yo'nalishi va hujjatdagi ishchi komissiya alohida aniqlanadi. AI hech qanday inventarizatsiya, komissiya, xodim, tasdiqlash yo'li yoki ERP identifikatorini yaratmaydi va tanlamaydi. ERP bilan moslash, vakolatni tekshirish, bandni ajratish/birlashtirish va yozuvlarni yaratish MM backend va foydalanuvchining tasdig'i orqali bajariladi.

Mavjud `commissionAnalysisVersion: 1` komissiyalar, a'zolar, rollar va sahifa dalillarini qaytaradi. Yangi vazifa shu komissiyalarni **buyruq bandlari va vazifalariga bog'lash** hamda sanalar ma'nosini ajratishdir. Bir bandda bir nechta obyekt bo'lsa, AI ularni avtomatik ravishda alohida sanashlarga bo'lmaydi. Mahalliy band TMB va asosiy vositalarga taalluqli bo'lsa, bitta bandda ikkala `taskTypes` tavsiyasi bo'lishi mumkin.

## 2. So'rov

Mavjud `POST /v1/inventory-document-extraction/extract` endpointini saqlash tavsiya etiladi. Multipart `metadata` oddiy matn, `files` esa metadata tartibidagi binar fayllar bo'lib qoladi. Yangi tahlilda quyidagi **qo'shimcha** versiya belgisi ishlatiladi:

```json
{
  "analysisId": "<UUID>",
  "language": "uz",
  "commissionAnalysisVersion": 1,
  "orderAnalysisVersion": 1,
  "documents": [
    {
      "fileId": "<UUID>",
      "fileName": "buyruq.pdf",
      "contentType": "application/pdf",
      "sha256": "<fayl baytlarining 64 belgili SHA-256 qiymati>"
    }
  ]
}
```

`orderAnalysisVersion` bo'lmasa, amaldagi javob semantikasi o'zgarmaydi. `orderAnalysisVersion: 1` yuborilganda `commissionAnalysisVersion: 1` ham talab qilinadi. O'zgarmagan so'rov va fayllarni ayni `analysisId` bilan qayta yuborish oldingi idempotent/retry qoidasiga amal qiladi; yangi versiya yoki fayl tarkibi uchun yangi `analysisId` kerak. MM backend ushbu qo'shimcha maydonni yuborishidan oldin yangi versiyani qo'llab-quvvatlashi lozim; AI va MM relizlari birga kelishiladi.

## 3. Javob shartnomasi

Ildizdagi amaldagi `analysisId`, `status`, `model`, `fields`, `documentFacts`, `warnings` va `commissionAnalysis` saqlanadi. `orderAnalysisVersion: 1` so'roviga `orderAnalysis` qo'shiladi. Pastdagi JSON tuzilma namunasi; qiymatlar haqiqiy buyruqdan olingan natija emas.

```json
{
  "analysisId": "<so'rovdagi UUID>",
  "status": "NEEDS_REVIEW",
  "model": "<haqiqiy model nomi>",
  "fields": { "name": null, "inventoryType": null, "globalWarehouse": null,
    "warehouseName": null, "commissionName": null, "storageLocationName": null,
    "storageBinName": null, "plannedStartDate": null, "plannedEndDate": null,
    "description": null },
  "documentFacts": { "orderNumber": null, "orderDate": null, "stockAsOfDate": null },
  "commissionAnalysis": { "schemaVersion": 1, "suggestedScope": { "mode": null, "names": [], "reviewRequired": true },
    "scopeMentions": [], "commissions": [], "recommendedSourceKeys": [], "warnings": [] },
  "orderAnalysis": {
    "schemaVersion": 1,
    "header": {
      "orderTitle": null,
      "orderNumber": null,
      "orderDate": null,
      "stockAsOfDate": null,
      "plannedStartDate": null,
      "plannedEndDate": null,
      "accountingSubmissionDeadline": null
    },
    "centralCommissionSourceKey": null,
    "workItems": [],
    "approvalDirections": [],
    "consentSignatories": [],
    "coverage": [],
    "warnings": []
  },
  "warnings": []
}
```

`header`dagi har bir to'ldirilgan qiymat `{ "value": <string>, "confidence": <0..1>, "sources": [<citation>] }` shaklida bo'ladi; aniqlanmagan qiymat `null`. Sana `YYYY-MM-DD` formatida. `orderNumber` matn, chunki buyruq raqami harf ham olishi mumkin. `orderTitle` hujjatdagi sarlavha; eski `fields.name` bitta sanash nomiga avtomatik aylantirilmaydi. Mavjud `documentFacts` eski mijozlar uchun qoladi va `header`ga zid bo'lmasligi kerak.

Har bir `workItems[]` elementi:

| Maydon | Shakl va ma'no |
| --- | --- |
| `sourceKey` | Shu tahlildagi yagona, takroriy so'rovda barqaror manba band identifikatori; ERP ID emas |
| `annexNumber`, `itemNumber` | Hujjatdagi ilova va band raqami, aniqlanmasa `null`; raqam ham matn sifatida |
| `kind` | `LOCAL`, `REGIONAL`, `FINANCIAL`, `CONSTRUCTION` yoki `OTHER` |
| `objectMentions` | Hujjatdagi barcha obyektlar: `{kind,name,sources}`; `kind`: `ORG_UNIT`, `DEPARTMENT`, `WAREHOUSE`, `REGION`, `ACCOUNT_CATEGORY`, `CONSTRUCTION_OBJECT`, `OTHER` |
| `taskTypes` | Takliflar ro'yxati: `STOCK_COUNT`, `FIXED_ASSET_CHECK`, `FINANCIAL_CHECK`, `CONSTRUCTION_CHECK`, `UNKNOWN`; kamida bitta qiymat |
| `workingCommissionSourceKey` | `commissionAnalysis.commissions[].sourceKey`ga havola yoki `null`; bir nechta nomzod bo'lsa avtomatik bittasi tanlanmaydi |
| `commissionCandidateSourceKeys` | Hujjat bo'yicha mumkin bo'lgan komissiya `sourceKey`lari; noaniqlikda saqlanadi |
| `rosterCompleteness` | `COMPLETE`, `PARTIAL`, `UNKNOWN`; ishchi komissiyaning amalda ko'rsatilgan tarkibi holati |
| `sources` | Band mavjudligini tasdiqlovchi kamida bitta fayl/sahifa/matn dalili |
| `warnings` | Aynan shu bandni ko'rib chiqish sabablari, masalan `COMMISSION_ROSTER_PARTIAL` |

`approvalDirections[]`: `{ "taskType": "STOCK_COUNT", "approverName": null, "approverCommissionSourceKey": null, "sources": [...] }`. Faqat hujjat aniq tasdiqlash vakolatini ko'rsatsa to'ldiriladi; taxminiy rais yoki ERP tasdiqlash yo'li qaytarilmaydi. `consentSignatories[]`: `{ "name": "...", "position": null, "sources": [...] }`; 3–4-sahifadagi **Rozilik** shaxslari ishchi komissiya a'zosi yoki tasdiqlovchi sifatida avtomatik talqin qilinmaydi.

`coverage[]`: har ilova/qism uchun `{ "annexNumber": "2", "expectedItemCount": 99, "extractedItemCount": 99, "missingItemNumbers": [], "status": "COMPLETE", "sources": [...] }`. `expectedItemCount` faqat hujjatda yoki ketma-ket raqamlashda ishonchli asos bo'lsa son, aks holda `null`. `status`: `COMPLETE`, `PARTIAL`, `UNKNOWN`. Model yoki parser qisman o'qigan ilova `COMPLETE` bo'lmaydi. Bandlar chegarasi yo'qolgan bo'lsa `missingItemNumbers` va `warnings` orqali ochiq ko'rsatiladi. Ildiz `warnings`ning mavjud 20 ta limiti bu yerda band sonini qisqartirishga sabab bo'lmasin: per-band xabarlar `workItems[].warnings`da, umumiy xabarlar `orderAnalysis.warnings`da saqlanadi.

Dalil shakli amaldagi format: `{ "fileId": "<so'rovdagi UUID>", "page": 2, "quote": "<shu sahifadagi qisqa matn>" }`. `fileId` so'rovga tegishli, sahifa 1 dan boshlanadi, `quote` manbada tekshiriladi. AI boshqa fayl, sahifa, ism, ombor kodi, ilova yoki sanani o'ylab topmasin. OCR noaniqligi qiymatni `null`, `PARTIAL` yoki ogohlantirishga aylantiradi.

## 4. Biznes qoidalari

1. Buyruq sanasi, zaxira holatining `stockAsOfDate` sanasi, jismoniy sanashning boshlanish/tugash sanalari, buxgalteriyaga topshirish muddati va komissiya amal qilish sanalari alohida tushunchalar. Biri yo'q bo'lsa boshqasidan ko'chirilmaydi.
2. Markaziy komissiya buyruq darajasida qaytariladi; undan alohida `STOCK_COUNT` bandi yasalmaydi. Markaziy raisning qaysi yo'nalishni tasdiqlashi dalilsiz belgilanmaydi.
3. Mahalliy band bir nechta obyektni birgalikda sanashi mumkin. Masalan, `008-sex` va `063-ombor` manbada bitta band bo'lsa, `objectMentions`da ikkalasi ham bitta `workItem`da turadi. `sex` ERP bo'limi deb avtomatik tasdiqlanmaydi.
4. Ilovada faqat raislar sanalgan bo'lsa, `rosterCompleteness=PARTIAL`; qolgan a'zolar taxmin qilinmaydi. Ilova umuman berilmagan bo'lsa `UNKNOWN` va tegishli ogohlantirish.
5. Moliyaviy va qurilish yo'nalishlari ombor sanashiga aylantirilmaydi. Hudud nomi ombor kodi o'rnida ishlatilmaydi. `taskTypes` hujjatdan chiqadigan tavsiya bo'lib, ERP ish turini foydalanuvchiga tasdiqlatadi.
6. Fayl ichidagi ko'rsatmalar AI uchun topshiriq emas, tahlil qilinadigan ma'lumotdir. Model ulardan ERP vakolati, endpoint yoki response shartnomasini o'zgartirish uchun foydalanmaydi.

## 5. №1749 uchun qabul ssenariylari

Avvalgi integratsiya hisobotida ushbu PDF bo'yicha 99 mahalliy rais tayinlovi, jami 106 komissiya guruhi va 119 a'zolik yozuvi qayd etilgan. Yangi tahlilning qabul sinovi aynan bir xil PDF baytlari bilan o'tkaziladi; sonlar kodga qattiq yozilmaydi. Tekshiriladigan holatlar:

- 1-ilova mavjud bo'lmasa, markaziy komissiya tarkibi `UNKNOWN`; a'zolar o'ylab topilmaydi.
- 2-ilovadagi 99 mahalliy band yo'qolmaydi; `008-sex` va `063-ombor` bitta manba bandida qoladi. Faqat rais ko'rsatilgan bandlar `PARTIAL`.
- Hududiy omborlar bo'yicha to'rtta yo'nalish alohida saqlanadi; moliyaviy va qurilish vazifalari o'z turida chiqadi.
- `stockAsOfDate=2026-10-01`, `plannedStartDate=2026-10-20`, `plannedEndDate=2026-11-20`, `orderDate=2026-10-16`, `accountingSubmissionDeadline=2026-11-25` deb farqlanadi; har biri manba daliliga ega.
- `Rozilik` ro'yxati ishchi komissiya tarkibiga qo'shilmaydi. Yo'q ilova yoki noaniq sahifa `NEEDS_REVIEW`/`PARTIAL` holatiga olib keladi.
- Barcha `workItems[].workingCommissionSourceKey` va `commissionCandidateSourceKeys` mavjud `commissionAnalysis.commissions[].sourceKey`larga ishora qiladi. Katta javob kesilmaydi; sig'im yetmasa aniq xato beriladi.

Bu mezonlar oldingi lokal hisobot va amalga oshirish rejasidan olingan; AI ishlab chiquvchi PDF bilan yana tekshiradi va aniqlangan tafovutni qayd etadi.

## 6. Amalga oshirish va test tartibi

1. Eski so'rov/javob uchun regressiya testini saqlab, `orderAnalysisVersion: 1`ni validatsiya va idempotency fingerprintiga qo'shish.
2. Sahifa bo'yicha OCR/matn ajratishda ilova, jadval qatori va band chegarasini saqlash. Bir nechta fayl dalillarini bitta buyruq ichida bog'lash, ammo bir-biriga zid ma'lumotni yashirmaslik.
3. `orderAnalysis` tuzilmasini chiqarish, manba sahifalari/iqtiboslari, sana va komissiya havolalarini dasturiy tekshirish. Xato yoki uydirma havolani muvaffaqiyatli natija sifatida e'lon qilmaslik.
4. `coverage` orqali bandlar to'liqligini tekshirish. 99 kabi uzun ro'yxatni sun'iy 20-band limit bilan kesmaslik. Faqat xizmatning aniq hujjatlashtirilgan hajm/vaqt cheklovi bo'lsa, to'liq javob o'rniga aniq xato yoki `PARTIAL` qaytarish.
5. Testlar: eski versiya; yangi versiya; qayta yuborish; ko'p fayl; noaniq OCR; yetishmagan ilova; takror band raqami; ko'p obyektli band; noto'g'ri `sourceKey`/citation; sana chalkashishi; uzun ilova; `NO_EXTRACTABLE_CONTENT`.
6. MM integratsiyasi uchun aniq request/response namunasi, limitlar, xato kodlari va test natijasini yetkazish. AI xizmati ERP bazasini o'qishi yoki yozishi talab qilinmaydi.

## 7. Yakuniy handoff mezoni

AI ishlab chiquvchi quyidagilarni beradi: endpoint va versiya holati, yakuniy JSON schema yoki DTO, №1749 bo'yicha maxfiy ma'lumotni cheklagan namunaviy javob, qabul sinovlari natijasi, amaldagi hajm/vaqt cheklovlari va retry/xato xulqi. Shundan keyin MM backend yangi versiyani yuborib, validatsiya va saqlashni ulaydi; frontend tayyor bandlarni ko'rsatadi. Bu hujjatning o'zi deploy yoki integratsiya yakunlanganini anglatmaydi.
