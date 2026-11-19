# Material Matching va AI hisobvaraq tavsiyasi

**Holat:** amalga oshirish uchun texnik topshiriq va taklif etilayotgan API shartnomasi. Ushbu hujjat funksiyaning ishlab turganini yoki joriy etilganini bildirmaydi.

**Maqsad:** mavjud `/app/mxik-reconciliation` sahifasini materiallar kesimidagi **Material Matching** ish joyiga aylantirish. Unda MXIK va qo‘lda kirim uchun ombor hisobvarag‘i alohida ko‘rinadi. Shu hisobvaraq bo‘yicha bir xil deterministik qoida va bitta AI tavsiya xizmati Material Matching hamda qo‘lda kirim formasida ishlatiladi.

## 1. Asosiy biznes qoidalari

1. MXIK ixtiyoriy. MXIK yo‘qligi materialni yaroqsiz deb ko‘rsatmaydi va qo‘lda kirimni o‘z-o‘zidan to‘xtatmaydi.
2. Ombor hisobvarag‘i faqat qo‘lda material kirimiga yaroqli faol TMB materiallari uchun majburiy. Asosiy vosita va xizmatlar «hisobvaraq yetishmayapti» soniga qo‘shilmaydi.
3. **Saqlangan hisobvaraq** (`Material.manualReceiptStockAccountId`) materialning aniq sozlamasi. **Amaldagi hisobvaraq** muayyan kirimga qo‘llanadigan natija. Bu ikki qiymat alohida qaytariladi va ko‘rsatiladi.
4. Tegishli oxirgi yakunlangan 1C qoldiq suratida materialga bog‘langan **bitta** yaroqli hisobvaraq bo‘lsa, u avtomatik amaldagi hisobvaraq bo‘ladi. Material sozlamasiga yashirin yozuv qilinmaydi.
5. Shu suratda **bir nechta** yaroqli hisobvaraq bo‘lsa, foydalanuvchi aynan shu kirim qatori uchun ulardan birini tanlaydi. Saqlangan material hisobvarag‘i bu tanlovni chetlab o‘tmaydi.
6. Yaroqli import hisobvarag‘i **bo‘lmasa**, mavjud saqlangan material hisobvarag‘i qo‘llanadi. U ham bo‘lmasa, foydalanuvchidan yaroqli hisobvaraq so‘raladi. Material uchun kelgusi kirimlarga mo‘ljallangan fallback sozlamani saqlash alohida aniq amal hisoblanadi.
7. Hisobvaraq joriy tenant va kompaniyaga tegishli, faol va provodka uchun yaroqli bo‘lishi kerak. `002` bilan boshlanuvchi saqlov hisobvaraqlari kompaniyaga tegishli material kirimida yaroqsiz.
8. AI faqat so‘rov bo‘yicha tavsiya beradi. Tavsiya hisobvaraqni o‘zi tayinlamaydi, material sozlamasini o‘zgartirmaydi va kirimni post qilmaydi. Oldingi harakatlar yoki ulardagi hisobvaraq suratlari qayta yozilmaydi.

| Oxirgi tegishli 1C qoldiq suratidagi yaroqli nomzodlar | Amaldagi holat | Foydalanuvchi amali |
| --- | --- | --- |
| Bitta | `AUTO_1C` | Hisobvaraq ko‘rsatiladi, qo‘lda kirim davom etishi mumkin |
| Bir nechta | `CHOICE_REQUIRED` | Shu nomzodlardan birini kirim qatori uchun tanlash kerak |
| Nol, materialda saqlangan hisobvaraq bor | `CONFIGURED_FALLBACK` | Saqlangan hisobvaraq qo‘llanadi |
| Nol, saqlangan hisobvaraq ham yo‘q | `ACCOUNT_REQUIRED` | Yaroqli hisobvaraq tanlash va alohida tasdiq bilan materialga saqlash kerak |
| Material qo‘lda TMB kirimiga yaroqsiz | `NOT_APPLICABLE` | Hisobvaraq yetishmasligi deb sanalmaydi |

Bu holat nomlari **taklif etilgan yangi API qiymatlari**. Amaldagi koddagi enumlar sifatida qabul qilinmasin.

## 2. Amalga oshirish rejasi

### 2.1. Backend, birinchi bosqich — o‘qish va deterministik yechim

1. `warehouse_account_imports` ichida joriy tenant/kompaniyaning tegishli oxirgi `COMPLETED` suratini aniqlash. Bitta sanada bir nechta import bo‘lsa, tanlash tartibini aniq belgilash. Qaror har bir material uchun eski tasodifiy harakatlardan chiqarilmasin.
2. `warehouse_account_entries` ichidan shu importning `BALANCE` satrlarini o‘qish. `warehouse_source_master_links` va yozuvdagi material ID orqali haqiqiy materialga bog‘lash. Sun’iy yoki bog‘lanmagan ID deterministik nomzod bo‘lmaydi. Alohida material va hisobvaraq kombinatsiyalarini saqlash, bir necha omborni dalil sifatida ko‘rsatish.
3. Importdagi kodni kompaniya hisobvaraqlar rejasidagi haqiqiy ID bilan moslash va mavjud `StockAccountResolver` yaroqlilik tekshiruvini qayta ishlatish. Importda uchragan, ammo faol/postable bo‘lmagan hisobvaraqni nomzodga kiritmaslik.
4. Material turi, saqlangan hisobvaraq, 1C nomzodlari, amaldagi hisobvaraq, manba, surat sanasi va sababni qaytaruvchi umumiy read model yaratish. Ro‘yxatda sahifalash va jami sonlar SQL darajasida ishlasin, har satr uchun alohida 1C so‘rovi yuborilmasin.
5. Material Matching ro‘yxat/detail DTOlariga MXIK va hisobvaraq holatini alohida kiritish. **All**, **Missing MXIK**, **Missing stock account**, **Both set** filtrlari, qidiruv, barqaror tartiblash va sahifalashni qo‘llash. Mavjud MXIK URL hamda amallar ishlashda davom etsin.
6. Dalillar uchun cheklangan va sahifalanadigan so‘rov qo‘shish. Har bir satrda manba turi, import ID, yozuv ID, material, hisobvaraq, ombor, sana va to‘liqlik holati bo‘lsin.

Material Matching va kirim formasiga bir xil natija beradigan **taklif etilayotgan** o‘qish shartnomasi:

```text
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

Misolda saqlangan hisobvaraq ko‘rsatilgan bo‘lsa ham, `CHOICE_REQUIRED` holati uchun amaldagi hisobvaraq avtomatik tanlanmaydi. Ro‘yxat DTO shu holatni filtr va sanash uchun ixcham qaytaradi; dalillarning to‘liq sahifasi detail so‘rovida olinadi.

### 2.2. Backend, ikkinchi bosqich — tanlash va post qilish

1. Qo‘lda kirimning `WarehouseStockFactRequest.Line` shartnomasiga **ixtiyoriy** `stockAccountId` qo‘shish. Eski mijozlarning maydonsiz so‘rovlari bitta avtomatik 1C hisobvarag‘i yoki saqlangan fallback bilan ishlashi kerak. Bir nechta nomzod bor holatda yangi aniq tanlov talab qilinadi.
2. Posting vaqtida material va hisobvaraqni shu tranzaksiya ichida qayta tekshirish. Ko‘p 1C nomzodi bo‘lsa, tanlangan ID aynan shu nomzodlardan biri bo‘lsin. Bitta nomzod bo‘lsa, server uni qo‘llasin. Import nomzodi bo‘lmasa, saqlangan fallback qo‘llansin yoki alohida saqlash amali talab qilinsin.
3. Yangi harakatga tanlangan hisobvaraq **kodining suratini** yozish. Amaldagi `accountCodeSnapshot` xulqini saqlash. Idempotent qayta yuborishda bir xil so‘rov bir xil natijani bersin; hisobvaraq tanlovi so‘rov fingerprintiga kirsin.
4. Material fallbackni saqlash uchun mavjud `PUT /api/stock-account-resolution/manual-receipt/materials/{materialId}` mijozlariga moslikni saqlash. Versiya va kontekst tekshiruvli yangi amal kerak bo‘lsa, uni alohida shartnoma sifatida qo‘shish. Avtomatik 1C natijasi bu maydonga yozilmasin.
5. Qo‘lda tanlash va AI yordamida tanlashni auditda ajratish. Material sozlamasi va kirimga xos tanlov auditda alohida harakat sifatida qayd etilsin.

### 2.3. Backend, uchinchi bosqich — AI tavsiya

1. Mavjud `AiAnalysisJob` hayot siklini kengaytirish: yangi tahlil turi, mavjud navbat/worker/polling, tilni uzatish, availability va natijani saqlash. AI yo‘qligida qo‘lda tanlash ishlashi kerak.
2. Backend har bir so‘rov uchun ruxsatlar bilan cheklangan, hajmi chegaralangan dalil paketini yig‘adi. AI xizmatiga brauzer to‘g‘ridan-to‘g‘ri ma’lumot yubormaydi.
3. AI chiqishi qat’iy strukturada qabul qilinadi. Server hisobvaraq ID/kodini ruxsat etilgan nomzodlar ichida tekshiradi. Ish ID, material versiyasi va dalil fingerprinti saqlanadi.
4. Qo‘llash amali joriy tenant/kompaniya, foydalanuvchi huquqi, material va dalil versiyasi, ish statusi, hisobvaraq yaroqliligi hamda tanlov doirasini qayta tekshiradi. O‘zgargan kontekst `409` bilan qaytariladi; forma loyihasi saqlanib qoladi.

### 2.4. Frontend

1. `/app/mxik-reconciliation` route va deep linklarni saqlab, ko‘rinadigan nomni **Material Matching**ga o‘zgartirish. Ingliz, rus va o‘zbek matnlarini yangilash.
2. Jadvalda bitta material qatori, ikkita alohida status, hisobvaraq kodi/nomi/manbasi, filtrlar, qidiruv va sahifalash. 1C orqali avtomatik topilgan hisobvaraq saqlangan sozlamadan vizual farqlansin.
3. Detail panelda MXIKning mavjud ko‘rish/tayinlash/batch ishlari saqlansin. Hisobvaraq nomzodlari, 1C dalili, aniq qo‘lda tanlash va AI tavsiya alohida ko‘rsatilsin.
4. Bitta qayta ishlatiladigan tavsiya komponenti Material Matching va `ManualReceiptAccount` integratsiyasida qo‘llansin. Ikkalasi bir xil backend job va apply shartnomasidan foydalansin.
5. Tavsiyani boshlash, kutish, ishonchli javob yo‘qligi, xato va eskirish holatlari ko‘rsatilsin. AI xatosi qo‘lda tanlashni bloklamasin. Kirimdagi miqdor, narx, sana, manba, boshqa qatorlar va yuklangan fayllar saqlansin.
6. Kompaniya, foydalanuvchi, material yoki sahifa almashganda eski javoblar yangi kontekstga qo‘llanmasin. Sozlama saqlangach tegishli material, kirim hisobi, matching va tavsiya keshlari yangilansin. Klaviatura/fokus, mobil kenglik, light/dark va uch til tekshirilsin.

### 2.5. Yakuniy tekshiruv va chiqarish

Tenant/kompaniya izolyatsiyasi, dalil manbalari ruxsati, 1Cning bitta/ko‘p/nol nomzodi, eski va chala importlar, bog‘lanmagan materiallar, `002`, nofaol hisobvaraq, eski material/dalil versiyasi, AI tomonidan uydirilgan ID, audit, idempotent qayta yuborish, filtr jami sonlari va kirim draftini saqlash tekshirilsin. Backend shartnomasi frontenddan oldin chiqarilsin; joriy etish alohida ruxsat va release jarayoniga bog‘liq.

## 3. AI ishlab chiquvchisi uchun biznes mantiq

### 3.1. AI qachon chaqiriladi

Foydalanuvchi **Suggest account** tugmasini bosganda. Bir nechta import nomzodidan tanlashga yordam berishi yoki import nomzodi bo‘lmaganda material uchun fallback hisobvaraq taklif qilishi mumkin. Bitta 1C nomzodi allaqachon deterministik yechilgan bo‘lsa, AI tavsiya uni almashtira olmaydi. UI istasa tushuntirish uchun dalilni ko‘rsatadi.

### 3.2. AI kirish dalillari

Backend dalillarni ruxsat bo‘yicha filtrlaydi va har biriga `evidenceId`, `sourceType`, haqiqiy hujjat/material/hisobvaraq IDlari, sana yoki `snapshotDate`, `complete` holati va qisqa faktlarni beradi. Paketda quyidagilar bo‘lishi mumkin:

- Material kodi/nomi, turi va ostturi, guruhi, birlik, xususiyatlari, tavsifi, ixtiyoriy MXIK, mavjud saqlangan hisobvaraq va mos hisobvaraqlar.
- Oxirgi tegishli yakunlangan 1C qoldiq suratidagi materialga bog‘langan hisobvaraqlar. Ombor, qoldiq, import ID va sana ko‘rsatiladi. Eski 1C harakatlari qo‘shimcha tarixiy dalil sifatida berilishi mumkin; ular nomzodlar to‘plamini kengaytirmaydi.
- Joriy operatsion qoldiq va tegishli MM harakatlari. Operatsion miqdor 1C buxgalteriya qoldig‘i bilan bitta qiymatga qo‘shilmaydi.
- Materialga bog‘langan tasdiqlangan/yakunlangan shartnoma, spetsifikatsiya, buyurtma va qabul qilingan kirimlar. Manbada hisobvaraq yozilgan bo‘lsa, faqat o‘sha fakt beriladi. Bekor qilingan yoki eski hujjatning holati yashirilmaydi.
- Foydalanuvchi ko‘rishi mumkin bo‘lgan materialga xos yetkazib beruvchi va yetkazish konteksti. Yetkazib beruvchi nomining o‘zi hisobvaraqni isbotlamaydi.
- Ruxsat etilgan oldingi hisob qarorlari yoki klassifikatsiya moslamalari. O‘xshash materiallar aniq «boshqa material» dalili deb belgilanadi.

Dalillar dolzarbligi bo‘yicha saralanadi va soni chegaralanadi. Backend foydalanuvchi ko‘ra olmaydigan procurement, supplier yoki moliyaviy ma’lumotni AI kirishidan **ham**, ko‘rsatiladigan izohdan **ham** olib tashlaydi. Parollar, tokenlar va aloqasiz kompaniya ma’lumotlari yuborilmaydi. Manba yo‘q bo‘lsa, bo‘shliq ochiq ko‘rsatiladi; fakt to‘qilmaydi.

### 3.3. Qaror chegarasi

AIga `allowedAccountIds` ro‘yxati backendda oldindan hisoblanib beriladi:

- `CHOICE_REQUIRED` holatida faqat oxirgi tegishli 1C suratidagi **yaroqli import nomzodlari**.
- `ACCOUNT_REQUIRED` holatida faqat kompaniyaning **yaroqli hisobvaraqlari**; natija faqat foydalanuvchi tasdiqlaydigan material fallback sozlamasiga mos.
- `AUTO_1C`, `CONFIGURED_FALLBACK` va `NOT_APPLICABLE` holatlarida AI yangi hisobvaraq tayinlashni talab qilmaydi. Agar UI tavsiyani chaqirsa, u mavjud hisobni tushuntirishi yoki `NO_RELIABLE_SUGGESTION` qaytarishi mumkin; boshqa hisobga apply qilinmaydi.

AI dalillarni tushuntiradi, qarama-qarshilik va eskilikni ko‘rsatadi. Confidence raqami mavjud bo‘lsa ham, dalilsiz hisobvaraq tanlashga asos bo‘lmaydi. Aniq algoritm/model tanlovi AI jamoasiga tegishli, ammo chiqishdagi ID va dalil havolalari tekshiriladigan bo‘lishi shart.

### 3.4. Taklif etilayotgan backend ↔ AI shartnomasi

Quyidagi JSON **loyihaviy shartnoma**. Bu bugun mavjud endpoint deb qaralmasin. Backenddagi job worker AI xizmatini ichki integratsiya orqali chaqiradi; tashqi marshrut va autentifikatsiya usuli AI jamoasi bilan kelishiladi. `schemaVersion` kelajakdagi moslik uchun majburiy.

```json
{
  "schemaVersion": 1,
  "task": "MATERIAL_STOCK_ACCOUNT_RECOMMENDATION",
  "jobId": "uuid",
  "language": "uz",
  "tenantId": "tenant",
  "companyId": "uuid",
  "materialId": "uuid",
  "materialVersion": 12,
  "evidenceFingerprint": "sha256",
  "resolutionStatus": "CHOICE_REQUIRED",
  "allowedAccounts": [
    { "id": "uuid", "code": "1010", "name": "Materiallar" }
  ],
  "material": {
    "code": "MAT-001",
    "name": "Material nomi",
    "type": "TMB",
    "subtype": "MATERIAL",
    "configuredAccountId": null,
    "mxikCode": null
  },
  "evidence": [
    {
      "evidenceId": "one-c:import-id:entry-id",
      "sourceType": "ONE_C_BALANCE",
      "sourceId": "entry-id",
      "date": "2026-11-12",
      "complete": true,
      "accountId": "uuid",
      "facts": { "warehouseId": "uuid", "quantity": "10.0000000" }
    }
  ]
}
```

`tenantId` va `companyId` ichki chaqiruvda faqat scope tekshiruvi uchun. `facts` tarkibi manba turiga qarab cheklangan, versiyalangan DTOlar bilan aniqlanadi. Misoldagi sana va qiymatlar ma’lumot namunasi xolos. AI `allowedAccounts`dan tashqari ID qaytarmasligi kerak.

```json
{
  "schemaVersion": 1,
  "jobId": "uuid",
  "materialId": "uuid",
  "materialVersion": 12,
  "evidenceFingerprint": "sha256",
  "status": "RECOMMENDED",
  "recommendedAccount": {
    "id": "uuid",
    "code": "1010"
  },
  "confidence": "MEDIUM",
  "reason": "Oxirgi 1C qoldig‘i va tasdiqlangan kirim shu hisobvaraqni ko‘rsatadi.",
  "evidenceRefs": ["one-c:import-id:entry-id"],
  "conflicts": []
}
```

Dalil yetarli bo‘lmasa `status: "NO_RELIABLE_SUGGESTION"`, `recommendedAccount: null`, qisqa `reason` va mos `evidenceRefs` qaytadi. `confidence` uchun taklif etilgan qiymatlar `HIGH`, `MEDIUM`, `LOW`; ularni tanlash mezoni AI jamoasi bilan kelishiladi. Bo‘sh yoki tekshirib bo‘lmaydigan dalil IDlari tavsiya uchun qabul qilinmaydi.

### 3.5. Backend tekshiruvi va UIga qaytarish

Backend AI javobidagi `schemaVersion`, job/material ID, material versiyasi, dalil fingerprinti, status va nomzod ID/kodini tekshiradi. `recommendedAccount.id` haqiqiy, faol, postable, joriy tenant/kompaniya hisobvarag‘i va ayni job uchun `allowedAccounts` a’zosi bo‘lishi kerak. `evidenceRefs` faqat yuborilgan, foydalanuvchiga ochiq dalillarga ishora qiladi. Noto‘g‘ri yoki uydirma javob **tavsiya sifatida chiqarilmaydi**; job xato holatiga o‘tadi. Ruxsat berilmagan dalillarni izohda qaytarishga ham yo‘l qo‘yilmaydi.

UI mavjud `GET /api/ai-analysis-jobs/{jobId}` orqali jobni polling qiladi. Yangi tavsiyani boshlash va availability uchun **taklif etilayotgan** marshrutlar:

```text
GET  /api/materials/{materialId}/ai/stock-account/availability
POST /api/materials/{materialId}/ai/stock-account/jobs
GET  /api/ai-analysis-jobs/{jobId}                 (mavjud job o‘qish marshruti)
```

Start so‘rovida backend joriy foydalanuvchi, tenant, kompaniya va materialni server kontekstidan oladi. Brauzer evidence yoki nomzod ro‘yxatini o‘zi tuzmaydi. Job javobida mavjud `AiAnalysisJobResponse` maydonlari (`id`, `status`, `result`, `errorCode` va boshqalar) ishlatiladi. `result` yuqoridagi tavsiya shartnomasiga mos bo‘ladi.

```json
{
  "id": "job-uuid",
  "analysisType": "MATERIAL_STOCK_ACCOUNT_RECOMMENDATION",
  "entityType": "MATERIAL",
  "entityId": "material-uuid",
  "status": "COMPLETED",
  "result": {
    "schemaVersion": 1,
    "status": "RECOMMENDED",
    "recommendedAccount": { "id": "account-uuid", "code": "1010" },
    "confidence": "MEDIUM",
    "reason": "Dalillarga asoslangan qisqa izoh",
    "evidenceRefs": ["one-c:import-id:entry-id"],
    "materialVersion": 12,
    "evidenceFingerprint": "sha256"
  },
  "errorCode": null,
  "errorMessage": null
}
```

Bu misol frontend uchun muhim maydonlarni ko‘rsatadi; mavjud `AiAnalysisJobResponse`ning qolgan metadata maydonlari ham APIda qoladi. `PENDING`/`RUNNING` paytida `result` bo‘sh, `FAILED` paytida `errorCode` va `errorMessage` to‘ldiriladi. `NO_RELIABLE_SUGGESTION` muvaffaqiyatli yakunlangan jobning natijasi bo‘lishi mumkin.

### 3.6. Tavsiyani qo‘llash

Tavsiya natijasida ikki turdagi foydalanuvchi amali mumkin. Ular bir-biriga aralashtirilmaydi:

| Amal | Qachon | Natija |
| --- | --- | --- |
| Kirim qatori uchun tanlash | Bir nechta 1C nomzodi | Tanlangan hisobvaraq faqat shu kirim qatorida saqlanadi, post paytida qayta tekshiriladi |
| Material fallbackni saqlash | Import nomzodi yo‘q | `manualReceiptStockAccountId` foydalanuvchining alohida tasdig‘i bilan yangilanadi; kelgusi kirimlarga ta’siri ko‘rsatiladi |

**Taklif etilayotgan** material fallback amali:

```json
PUT /api/materials/{materialId}/manual-receipt-stock-account
{
  "accountId": "uuid",
  "expectedMaterialVersion": 12,
  "expectedEvidenceFingerprint": "sha256",
  "recommendationJobId": "uuid"
}
```

Qo‘lda saqlashda `recommendationJobId` `null`; audit uni `MANUAL` deb yozadi. AI yordamida saqlashda job aynan shu material, account va o‘zgarmagan kontekstga tegishli bo‘lishi shart; audit `AI_ASSISTED`, job ID va dalil fingerprintini yozadi. Eski `PUT /api/stock-account-resolution/manual-receipt/materials/{materialId}` marshruti uchun moslik saqlanadi. Kirim qatoriga tanlashda `stockAccountId` va kerak bo‘lsa `recommendationJobId` yuboriladi; server post vaqtida ularni qayta tekshiradi. Bu amallarning hech biri tavsiya tugmasi bosilgan zahoti kirimni post qilmaydi.

Kirimning mavjud `lines[]` elementiga taklif etilayotgan qo‘shimcha maydonlar:

```json
{
  "materialId": "uuid",
  "quantity": 2,
  "unitCost": 1000,
  "stockAccountId": "uuid",
  "recommendationJobId": "uuid"
}
```

`recommendationJobId` bo‘sh bo‘lsa tanlov qo‘lda bajarilgan deb qayd etiladi. `stockAccountId` bir nechta import nomzodi bo‘lgan holatda majburiy. Tanlov eskirsa yoki nomzodlar o‘zgarsa server `409` qaytaradi; UI qator yonida xatoni ko‘rsatib, formadagi ma’lumotni saqlaydi. Yaroqsiz hisobvaraq yoki ruxsat yetishmasligi mos `4xx` xato bilan qaytadi.

## 4. Kelishilishi kerak bo‘lgan aniq nuqtalar

1. Bir sanada bir nechta `COMPLETED` import bo‘lsa, qaysi import «oxirgi tegishli surat» sanaladi. `completed_at` va ID bilan deterministik tartib taklif qilinadi; tashkilot/source partitioni ham tasdiqlansin.
2. Nol yoki manfiy qoldiqli `BALANCE` satri nomzod hisobvaraqni tasdiqlaydimi. Bu masala bir/ko‘p nomzod qoidasini o‘zgartiradi.
3. `stockAccountId` bilan birga receipt-specific AI job IDni qaysi posting maydonida olib yurish va auditda qanday bog‘lash.
4. Material Matching o‘qish, AI so‘rash, procurement/supplier dalilini ko‘rish, kirim qatorida tanlash va material fallbackni saqlash huquqlarining aniq rol jadvali.
5. AI xizmatining ichki marshruti, autentifikatsiyasi, timeout/retry siyosati, payload chegarasi va `confidence` mezoni. Yuqoridagi JSON mazmuniy shartnoma sifatida taklif etilgan.

## 5. Hozirgi kod bilan bog‘lanish

- `mm-backend/src/main/java/com/enterprise/mm/service/StockAccountResolver.java` qo‘lda kirim hisobvarag‘ini hozir faqat `manualReceiptStockAccountId`dan oladi; yaroqlilik va audit mantig‘i shu yerda bor.
- `mm-backend/src/main/java/com/enterprise/mm/service/WarehouseStockFactService.java` hozir postingda `resolveManualReceiptAccount()` natijasining kodini `accountCodeSnapshot`ga yozadi.
- `mm-backend/src/main/java/com/enterprise/mm/payload/WarehouseStockFactRequest.java`ning `Line` turida hozir kirimga xos hisobvaraq maydoni yo‘q.
- `mm-backend/src/main/java/com/enterprise/mm/warehouse/reports/WarehouseAccountReportQuery.java` va import jadvallarida 1C yozuvlari hamda material linklari bor. Hisobotning barcha tarixiy ko‘rinishi deterministik qo‘lda kirim qoidasi deb qabul qilinmasin.
- `mm-backend/src/main/java/com/enterprise/mm/service/AiAnalysisJobService.java`, `AiAnalysisJobWorker.java` va `AiAnalysisJobController.java` mavjud MXIK AI job uslubini ko‘rsatadi. Yangi hisobvaraq tavsiyasi alohida tahlil turi bo‘ladi.
- `mm-front/src/features/mxik-reconciliation/MxikReconciliationPage.tsx` mavjud MXIK sahifasi. Frontend checkout hozir `codex/production-completion` branchida mahalliy commitlar bilan remote’dan oldinda; kodlashdan oldin branch va ishchi daraxt holati qayta tekshirilsin.

**Manba:** `C:\Users\User\Downloads\Telegram Desktop\material-matching-mxik-stock-account-plan-2026-11-12.md` va 2026-11-12 kuni ko‘rilgan loyiha kodi. Ushbu hujjatdagi yangi marshrutlar, statuslar va JSON shakllari dastlabki taklifdir.
