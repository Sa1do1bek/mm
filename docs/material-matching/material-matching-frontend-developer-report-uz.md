# Frontend ishlab chiquvchisi uchun hisobot: Material Matching va qo‘lda kirim

**Holat:** frontend amalga oshirish topshirig‘i. Yangi API shakllari `material-matching-backend-developer-report-uz.md` bilan kelishiladigan taklifdir.

## Maqsad va joriy kod

Mavjud `/app/mxik-reconciliation` URL va `materialId` deep linklari saqlanadi. Ko‘rinadigan sahifa nomi **Material Matching** bo‘ladi. Bir material uchun MXIK va qo‘lda kirim ombor hisobvarag‘i alohida status bilan ko‘rsatiladi. MXIK ixtiyoriy.

Hozirgi UI `mm-front/src/features/mxik-reconciliation/MxikReconciliationPage.tsx` va `api.ts`da. Qo‘lda kirim `mm-front/src/features/warehouse-execution/WarehouseStockFactModal.tsx`ning receipt rejimida bajariladi. Bu checkoutda `ManualReceiptAccount` nomli tayyor komponent topilmadi; hisobvaraq boshqaruvi receipt qatoriga qo‘shilishi kerak. Forma `lines` va `files`ni komponent holatida saqlaydi hamda multipart `stock-receipt` so‘rovini yuboradi.

## 1. Material Matching sahifasi

Jadvalda har material uchun bitta qator bo‘lsin. Kamida kod, nom, MXIK kod/nom, MXIK holati, hisobvaraq kod/nom, hisobvaraq holati va manbasi ko‘rinsin. Quyidagi holatlar farqlansin:

| Backend status | UI matni/amal |
| --- | --- |
| `AUTO_1C` | Oxirgi 1C qoldig‘idan avtomatik topilgan hisobvaraq. Saqlangan material sozlamasi sifatida ko‘rsatilmasin. |
| `CHOICE_REQUIRED` | Bir nechta 1C hisobvarag‘i bor. Nomzodlar va dalil ko‘rsatiladi, kirimda bittasi tanlanadi. |
| `CONFIGURED_FALLBACK` | Materialga aniq saqlangan hisobvaraq. |
| `ACCOUNT_REQUIRED` | Yaroqli import nomzodi va material sozlamasi yo‘q. Mos hisobvaraqni tanlash va materialga saqlash amali. |
| `NOT_APPLICABLE` | Ushbu material turi uchun qo‘lda TMB kirim hisobvarag‘i tatbiq etilmaydi. |

MXIK va hisobvaraq statuslari mustaqil. MXIK yo‘q material «noto‘g‘ri» deb belgilanmasin. **All**, **Missing MXIK**, **Missing stock account**, **Both set** filtrlari, qidiruv, sahifalash va jami sonlar backend natijasiga asoslanadi. `Both set`da MXIK va amaldagi hisobvaraq mavjud materiallar, shu jumladan `AUTO_1C`, ko‘rsatiladi. `NOT_APPLICABLE` satrlar missing account deb sanalmaydi.

Mavjud MXIK review, qo‘lda tayinlash, o‘xshash material dalili, batch, klaviatura boshqaruvi va `materialId` deep linki ishlashda qolsin. Detail panelda mavjud MXIK, saqlangan material hisobvarag‘i, amaldagi hisobvaraq, 1C nomzodlari, surat sanasi, ombor/manba dalillari va AI tavsiya alohida bloklarda ko‘rsatiladi. MXIKni saqlash hisobvaraqni, hisobvaraqni saqlash MXIKni o‘zgartirmaydi.

### Sahifa API shartnomasi

```text
GET /api/mxik-reconciliation/matching-materials?filter=ALL&search=...&page=0&size=25&mxikStatus=...&accountStatus=...
GET /api/mxik-reconciliation/materials/{materialId}
GET /api/materials/{materialId}/manual-receipt-stock-account/resolution
GET /api/materials/{materialId}/manual-receipt-stock-account/evidence?page=0&size=25
```

Yangi filtr parametrlari backend bilan yakunlanadi. Eski `missingOnly` deep linki yangi filtrga mos o‘qilsin. Tanlangan `materialId` sahifa yangilanganda ham ochilsin. `candidateAccounts` faqat backend qaytargan ro‘yxatdan olinadi. UI o‘zi 1C yozuvlaridan amaldagi hisobvaraq hisoblamaydi.

## 2. Qo‘lda kirim formasidagi hisobvaraq

`WarehouseStockFactModal.tsx`ning faqat receipt rejimidagi har bir material qatoriga hisobvaraq holati va boshqaruv qo‘shilsin. Material tanlanganda umumiy resolution endpointi chaqirilsin. Material o‘zgarsa avvalgi account tanlovi va AI tavsiyasi shu qatordan olib tashlansin, boshqa qator va forma maydonlari qoladi.

| Holat | Forma xulqi |
| --- | --- |
| `AUTO_1C` | Kod/nom va 1C manbasi ko‘rsatiladi. Qator hisobvarag‘i backend qoidasi bilan to‘ldiriladi. |
| `CHOICE_REQUIRED` | Backend bergan nomzodlardan aniq bitta tanlov kerak. AI **Suggest account** yordamchi amal sifatida mavjud. |
| `CONFIGURED_FALLBACK` | Saqlangan kod/nom ko‘rsatiladi. |
| `ACCOUNT_REQUIRED` | Yaroqli kompaniya hisobvaraqlari, **Suggest account** va alohida **Material uchun saqlash** amali ko‘rsatiladi. Saqlash kelgusi kirimlarga ta’siri tushuntiriladi. |
| Yuklash/xato | Shu qator yonida aniq holat ko‘rsatiladi. Xato boshqa qatorlardagi draftni o‘chirmaydi. |

`stockFactLineErrors`ga hisobvaraq uchun qatorga xos xato turi qo‘shilsin. Submitda qaysi qatorga hisobvaraq yetishmayotgani ko‘rsatilib, fokus shu boshqaruvga o‘tsin. Server `409` qaytarsa, tegishli resolution va nomzodlar yangilansin, foydalanuvchi qayta ko‘rsin. Forma yopilmasin; miqdor, unit cost, sana, source/reference, seriallar, qolgan qatorlar va staged files saqlansin.

Mavjud `stock-receipt` multipart `request` ichidagi qatorga yangi maydonlar qo‘shiladi:

```json
{
  "materialId": "uuid",
  "quantity": "2",
  "unitCost": "1000",
  "serialNumbers": [],
  "stockAccountId": "uuid",
  "recommendationJobId": "uuid"
}
```

`CHOICE_REQUIRED`da `stockAccountId` foydalanuvchi tanlovi. `AUTO_1C` va `CONFIGURED_FALLBACK`da frontend server avtomatik qoidasiga tayanishi mumkin. `recommendationJobId` faqat foydalanuvchi ayni AI tavsiyasini tanlaganda yuboriladi. Oddiy qo‘lda tanlovda bo‘sh. Mavjud `Idempotency-Key` yuborish xulqi saqlansin; hisobvaraq tanlovi o‘zgarsa so‘rov fingerprinti va key ham yangilansin.

## 3. Bitta AI tavsiya boshqaruvi

Material Matching va qo‘lda kirim uchun bitta qayta ishlatiladigan account recommendation control ishlatilsin. Bu komponent backenddagi bir xil availability, job start va polling marshrutlariga murojaat qiladi. Brauzer AIga o‘z dalil paketini tuzmaydi.

```text
GET  /api/materials/{materialId}/ai/stock-account/availability
POST /api/materials/{materialId}/ai/stock-account/jobs
GET  /api/ai-analysis-jobs/{jobId}
```

Komponent `UNAVAILABLE`, `IDLE`, `PENDING/RUNNING`, `RECOMMENDED`, `NO_RELIABLE_SUGGESTION`, `FAILED`, `STALE` ko‘rinishlarini qo‘llasin. `RECOMMENDED` natijasida account kodi/nomi, confidence, qisqa sabab, dalil manbalari va **Apply** ko‘rsatiladi. Dalillar faqat backend ruxsat bergan havolalar bilan ko‘rsatiladi. `NO_RELIABLE_SUGGESTION` matni ochiq beriladi. AI xatosi yoki mavjud emasligi qo‘lda tanlashni bloklamaydi.

Apply bosilganda UI tavsiyaning `materialId`, `materialVersion`, `evidenceFingerprint`, job IDsi va hozirgi tanlov kontekstini tekshiradi, ammo server tekshiruvi hal qiluvchi bo‘ladi. `CHOICE_REQUIRED`da Apply shu receipt qatoridagi tanlovni o‘rnatadi. `ACCOUNT_REQUIRED`da Apply material fallbackni saqlash uchun alohida tasdiq amalini ishga tushiradi. Har ikki holatda Apply kirimni post qilmaydi. Matching sahifasida receipt-specific tanlov materialning saqlangan sozlamasi sifatida ko‘rsatilmasin.

### Kesh va eskirgan javoblar

Tanlangan material, foydalanuvchi, kompaniya, receipt row key va sahifa kontekstini query keyga kiriting. Material/kompaniya almashganda eski job yoki resolution javobini yangi materialga qo‘llamang. Material sozlamasi saqlangach matching ro‘yxati/detail, resolution, receipt row va AI tavsiya keshlarini joriy identifikatsiya doirasida yangilang. Oldingi kechikkan javob yangi tanlovni bekor qilmasin.

## 4. Matn, foydalanish qulayligi va tekshiruv

Ingliz, rus va o‘zbek tillarida sahifa nomi, statuslar, sabablar, 1C manbasi, saqlangan sozlama, AI tavsiya, no suggestion, xato va eskirgan tavsiya matnlari bo‘lsin. Kichik ekran, light/dark, klaviatura/fokus, loading/empty/error holatlari tekshirilsin. Hisobvaraq va MXIK boshqaruvi bir-birining fokusini yoki draftini buzmasin.

Tekshiruv senariylari: MXIK bor/yo‘q va hisobvaraq bor/yo‘q kombinatsiyalari; `AUTO_1C`, `CHOICE_REQUIRED`, `CONFIGURED_FALLBACK`, `ACCOUNT_REQUIRED`, `NOT_APPLICABLE`; bir nechta 1C nomzodi; AI unavailable/no suggestion/failed/stale; qo‘lda va AI yordamida tanlash; kompaniya/material almashishi; material sozlamasi ikkala yuzada yangilanishi; staged files va kirim drafti saqlanishi; eski deep link va MXIK batch ishlari.

**Backendga bog‘liq ochiq nuqtalar:** aniq yangi filtr parametrlari, resolution/detail DTO nomlari, apply marshruti va receipt-specific AI job audit maydoni. Frontend shu shartnomalar backend tomonidan tasdiqlanib berilgach ularga ulanadi. AI request/response mazmuni `material-matching-ai-developer-report-uz.md`da.
