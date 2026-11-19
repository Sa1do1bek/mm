# Material MXIK sverka: AI ulanish shartnomasi

## Vazifa va chegaralar

UI `Master data and references → Materials → MXIK reconciliation` sahifasida amaldagi MM materiallarini ko‘rsatadi. 1C zaxira nusxasidagi IKPU maydoni tarixiy va o‘zgartirilmaydi. Qo‘lda MXIK biriktirish hozirgi `/api/mxik-reconciliation/assignments` orqali ishlaydi. AI tavsiyasi avtomatik biriktirish emas: foydalanuvchi ko‘rib tasdiqlaydi.

## Bitta material uchun AI ish

Frontend mavjud `ai_analysis_jobs` uslubini ishlatadi:

- `POST /api/materials/{materialId}/ai/mxik-match/jobs` — body talab qilinmaydi; `202` va odatdagi `AiAnalysisJobResponse` qaytadi.
- `GET /api/ai-analysis-jobs/{jobId}` va `GET /api/ai-analysis-jobs/latest?analysisType=MATERIAL_MXIK_MATCH&entityType=MATERIAL&entityId={materialId}` — odatdagi polling.
- `AiAnalysisType` ga `MATERIAL_MXIK_MATCH` qo‘shiladi, `entityType=MATERIAL`, `entityId=materialId`.
- **Modelga faqat `materialId` yuboriladi.** AI xizmati shu ID orqali ma’lumotlar bazasidan kerakli material, guruh, birlik, tavsif va tegishli MXIKli materiallarni o‘zi o‘qiydi. Tenant va company chegarasi serverda tekshiriladi.

`result` (job `resultPayload`) kamida quyidagi shaklda bo‘lsin:

```json
{
  "materialId": "uuid",
  "materialVersion": 4,
  "mxikCode": "12345678901234567",
  "confidence": 0.92,
  "reason": "Material nomi va xususiyatlari shu MXIKga mos"
}
```

`confidence` 0 dan 1 gacha. `mxikCode` mavjud MXIK katalogida bo‘lishi va material turiga mos kelishi kerak (`SERVICES → SERVICE`, qolganlari `GOODS`). Mos kod bo‘lmasa, job natijasida tavsiya bo‘lmasin; taxminiy kod yaratilmasin. `materialVersion` tavsiya hisoblangan paytdagi material versiyasi. Tasdiqlash vaqtida u joriy versiya bilan solishtiriladi.

## Tanlangan materiallar

Bir HTTP so‘rov bilan ko‘p materialni modelga berish o‘rniga, backend **har bir material uchun alohida AI job** yaratadi. Bu natijalarni alohida kuzatish, xatoni qayta ishlash va uzoq so‘rovdan qochish imkonini beradi.

Frontend adapteri: `POST /api/mxik-reconciliation/ai/jobs`.

- Tanlanganlar: `{ "scope": "SELECTED", "materialIds": ["uuid", "..."] }` (bir martada ko‘pi bilan 25).
- `FILTERED` scope qo‘llab-quvvatlanmaydi. Oddiy UI butun filtr yoki katalog uchun AI ishini boshlamaydi.
- Backend har bir tanlangan material uchun alohida child job yaratadi va MM AI chaqiruvlarini bittadan bajaradi. MXIK ishlari bitta workerli alohida executor navbatidan o‘tadi va boshqa AI funksiyalarining executorini band qilmaydi.
- Parent batch job umumiy `queued/completed/failed` hisoblarini beradi; har bir materialning yakuniy natijasi uning individual AI jobida saqlanadi.
- Bir material uchun faol job bo‘lsa, dublikat yaratmay, mavjud job qaytariladi. Qayta urinish faqat muvaffaqiyatsiz yoki eskirgan job uchun.

UI `VITE_MXIK_AI_ENABLED=true` bo‘lmaguncha AI tugmalarini o‘chirib turadi. Endpointlar ishlab, contract tasdiqlangach flag yoqiladi. Hech qanday soxta tavsiya ko‘rsatilmaydi.

## Ko‘rsatish va tasdiqlash

`GET /api/mxik-reconciliation/materials` javobidagi har bir qatorga eng so‘nggi haqiqiy natijadan `suggestedMxikCode`, `suggestionConfidence`, `suggestionReason`, `suggestionJobId`, `suggestionStatus` qo‘shiladi. Buni sahifa uchun bitta batch query bilan olish kerak; har bir qatorga alohida so‘rov yuborilmasin.

Tasdiqlash `POST /api/mxik-reconciliation/assignments` orqali bo‘ladi:

```json
{
  "assignments": [
    { "materialId": "uuid", "expectedVersion": 4, "mxikCode": "12345678901234567", "recommendationJobId": "job-uuid" }
  ]
}
```

`recommendationJobId` berilsa, backend job `COMPLETED`, shu materialga tegishli, joriy tenant/companyda, natijadagi kod va `materialVersion` mos ekanini tekshiradi va `MXIK_APPROVE_AI` audit yozadi. Kod qo‘lda tanlansa `recommendationJobId` yuborilmaydi va `MXIK_ASSIGN_MANUAL` yoziladi. Batch assignment 100 tagacha va tranzaksion: bitta qator eskirgan yoki noto‘g‘ri bo‘lsa, hech biri o‘zgarmaydi. UI har bir material uchun alohida tavsiya kodini yuboradi; bitta kodni hammasiga qo‘llash esa alohida qo‘lda biriktirish amali.

Filtrdagi barcha tavsiyalarni tasdiqlash uchun keyingi bosqichda serverda alohida fon amali kerak: foydalanuvchiga avval mos va eskirgan tavsiyalar soni ko‘rsatiladi, tasdiqdan so‘ng server filtr IDlarini snapshot qiladi va kichik tranzaksion qismlarda tasdiqlaydi. Brauzer barcha sahifalarni yuklab, minglab assignmentlarni bitta HTTP so‘roviga joylamaydi. Bu amal har bir material bo‘yicha muvaffaqiyat yoki xato natijasini qaytarishi va audit yozishi kerak. AI endpoint mavjud bo‘lmaguncha UI bunday tavsiyalarni tasdiqlashni taklif qilmaydi.
