# Inventarizatsiya buyrug'i: foydalanuvchi doirasiga mos AI tahlili

Sana: 2026-11-13
Holat: AI ishlab chiquvchi bilan kelishish uchun taklif etilgan 2-versiya shartnomasi.

## Asosiy biznes qoidasi

Bo'lim/sex rahbari №1749 kabi umumiy buyruqni yuklasa, javobda **faqat o'zi rahbarlik qiladigan bo'lim(lar)**ga oid rais tayinlovi, ish bandlari va bevosita tegishli komissiya tarkibi bo'ladi. Buyruqning umumiy raqami, sanasi va muddatlari kontekst uchun qaytishi mumkin. Boshqa bo'limlarning raislari, a'zolari va ishlari javobning hech bir qismida, jumladan ogohlantirish va iqtiboslarda ham ko'rinmaydi. `ADMIN`ning `ALL` so'rovi butun buyruqni qaytaradi.

№1749 ning 2-ilovasidagi 99 ta satr — inventarizatsiya obyektlari va ularga tayinlangan **mahalliy komissiya raislari**. Ular to'liq tarkibli 99 ta komissiya degani emas. 3.1–3.2-bandlarga ko'ra qolgan a'zolar joylardagi farmoyishlar bilan belgilanadi. AI bu satrlarni `chairAssignments[]` sifatida ajratadi; hujjatda a'zolari aniq berilgan markaziy/yo'nalishli komissiya va ishchi guruhlarni `commissionRosters[]`da alohida beradi. To'liq bo'lmagan tarkibni `createEligible: true` deb belgilamaydi.

## So'rov: amaldagi endpointda 2-versiya

`POST /v1/inventory-document-extraction/extract` multipart: `metadata` JSON matn, `files` fayl qismlari. MM backend doirani autentifikatsiya, tenant/kompaniya va rahbarlik yozuvlaridan o'zi hisoblaydi. Brauzer yuborgan bo'lim IDlariga ishonilmaydi.

```json
{
  "analysisId": "<UUID>",
  "language": "uz",
  "commissionAnalysisVersion": 2,
  "orderAnalysisVersion": 2,
  "scope": {
    "mode": "DEPARTMENTS",
    "companyId": "<UUID>",
    "departmentIds": ["<UUID>"],
    "targets": [
      {
        "departmentId": "<UUID>",
        "name": "<ERPdagi nom>",
        "aliases": ["<tasdiqlangan sex kodi yoki muqobil nom>"],
        "warehouseIds": ["<UUID>"]
      }
    ]
  },
  "documents": [
    {"fileId": "<UUID>", "fileName": "1749.pdf", "contentType": "application/pdf", "sha256": "<64 hex>"}
  ]
}
```

Admin uchun `scope.mode = "ALL"`, `departmentIds = []`, `targets = []`. Tenant va kompaniya HTTP sarlavhalarida ham yuboriladi. AI `scope`ni foydalanuvchi vakolati deb mustaqil tasdiqlamaydi; bu MM backendning vazifasi. AI berilgan doira bo'yicha javobni cheklaydi. Bir xil `analysisId` bilan fayllar, hash, versiya yoki doira o'zgarsa `409` qaytariladi; yangi tahlil uchun yangi ID ishlatiladi.

## Javobning majburiy semantikasi

Quyidagi tuzilma minimal namuna. Har bir aniqlangan fakt manba `fileId`, sahifa va qisqa aniq iqtibos bilan keladi; topilmagan qiymat `null` yoki bo'sh ro'yxat bo'ladi.

```json
{
  "analysisId": "<so'rovdagi UUID>",
  "status": "NEEDS_REVIEW",
  "scope": {
    "mode": "DEPARTMENTS",
    "departmentIds": ["<UUID>"],
    "scopeResolution": "MATCHED"
  },
  "orderAnalysis": {
    "schemaVersion": 2,
    "header": {"orderNumber": null, "orderDate": null, "stockAsOfDate": null,
      "plannedStartDate": null, "plannedEndDate": null},
    "chairAssignments": [
      {"sourceKey": "<barqaror kalit>", "annexNumber": "2", "itemNumber": "<satr raqami>",
       "objectMention": {"kind": "ORG_UNIT", "name": "<hujjatdagi nom>"},
       "matchedDepartmentId": "<so'rovdagi UUID>", "chairName": "<F.I.Sh.>",
       "chairPosition": "<lavozim>", "otherMembersRequireLocalOrder": true,
       "sources": [{"fileId": "<UUID>", "page": 5, "quote": "<qisqa dalil>"}]}
    ],
    "workItems": [],
    "commissionRosters": [],
    "coverage": [],
    "warnings": []
  },
  "warnings": []
}
```

`commissionRosters[]` har bir hujjatda nomi va a'zolari ko'rsatilgan tarkibni beradi: `sourceKey`, `kind`, `name`, `members[]` (`fullName`, `position`, `documentRole`, `erpRoleSuggestion`, `sources`), `rosterCompleteness` (`COMPLETE | PARTIAL | UNKNOWN`), `createEligible`, `sources`. AI ERP `commissionId`, `userId`, yangi komissiya IDsi yoki haqiqiy yaratish requestini o'ylab topmaydi. `createEligible` faqat hujjatdagi a'zolik yetarliligini bildiradi; MM backend oxirgi qarorni tekshiradi. `workItems[]` rais tayinlovi va tegishli rosterga `sourceKey` orqali ulanadi. Bir nechta mos nom bo'lsa bittasini taxminan tanlamaydi.

`kind` qiymatlari: `CENTRAL`, `LOCAL`, `SPECIALIZED`, `REGIONAL`, `WORKING_GROUP`, `UNKNOWN`. `WORKING_GROUP` va `UNKNOWN` uchun `createEligible=false`; qolgan turlarda ham faqat to'liq, kamida ikki ismli a'zosi dalillangan komissiya yaratishga tavsiya qilinadi.

`workItems[]` amaldagi buyruq vazifalari bilan mos bo'lishi shart: har bir bandda `sourceKey`, `annexNumber`, `itemNumber`, `kind`, `objectMentions[]` (`kind`, `name`, `sources`), `taskTypes[]`, `workingCommissionSourceKey`, `commissionCandidateSourceKeys[]`, `rosterCompleteness`, `sources`, `warnings[]` bo'ladi. `DEPARTMENTS` javobida har bir band, rais tayinlovi va unga tegishli roster uchun `matchedDepartmentId` faqat so'rovdagi ruxsat etilgan IDlardan biri bo'ladi. `chairAssignmentSourceKey` mavjud bo'lsa, u aynan javobdagi `chairAssignments[]` elementiga bog'lanadi; `workingCommissionSourceKey` esa `commissionRosters[]`ga bog'lanadi. Komissiya a'zolarining har biri `sources` bilan keladi. 2-versiyada eski `commissionAnalysis` yoki boshqa bo'limlarni ochib qo'yishi mumkin bo'lgan umumiy `fields` yuborilmaydi.

MM rais tayinlovidagi `objectMention.name` ni yuborilgan `targets[].name` va `targets[].aliases` bilan taqqoslaydi. Nom/kod ishonchli mos kelmasa, boshqa bo'limning raisini tanlab yubormang: tegishli satrni chiqarib tashlab, `scopeResolution=AMBIGUOUS` va umumiy review kodi qaytaring. `DEPARTMENTS` uchun ildiz, buyruq va ish bandi ogohlantirishlari faqat `UPPER_SNAKE_CASE` kodlari bo'ladi; ularda hamda iqtiboslarda boshqa bo'limlarning ismlari, raislari yoki a'zolarini ko'rsatmang.

MM qabul chegaralari: `chairAssignments` 200, `workItems` 200, `commissionRosters` 100, bitta rosterda a'zolar 100, ildiz `warnings` 20, dalil `quote` 400 belgidan oshmaydi. Ushbu chegaradan katta buyruq uchun alohida transport kelishuvi kerak; UI ning 20 tadan sahifalanishi AI javobiga 20 ta cheklov qo'ymaydi.

`scopeResolution` qiymatlari: `MATCHED`, `AMBIGUOUS`, `NOT_FOUND`. `AMBIGUOUS`da qaysi bo'limga tegishli ekani tasdiqlanmagan nom boshqa bo'lim ma'lumotini oshkor qilmasdan review ogohlantirishiga aylanadi. `NOT_FOUND`da kerakli bo'lim bo'yicha satrlar bo'sh, lekin tahlil texnik jihatdan muvaffaqiyatli. `NEEDS_REVIEW` xato emas. `ALL` natijada buyruqning barcha tegishli bandlari va ilovalar bo'yicha to'liqlik ko'rsatiladi; `DEPARTMENTS` natijada to'liqlik faqat tanlangan doiraga nisbatan hisoblanadi.

## Hajm, sahifalash va limitlar

№1749 ning to'liq tahlili taxminan 480 KB bo'lishi mumkin. AI bitta asinxron tahlilni yakunlab, izchil tuzilmali natijani qaytaradi. UI uchun 20 tadan sahifalashni MM backend bajaradi; **20 ta UI sahifasi AI natijasidagi komissiya, rais yoki bandlar limiti emas**. AI'ning transport limiti va barcha massivlar chegarasi MM validator bilan yozma kelishiladi. Agar kelajakda javob transport chegarasidan oshsa, AI bitta `analysisId` ostida `chunkIndex`, `chunkCount`, checksum bilan qismlarni berishi mumkin; MM hammasini yig'ib tekshirgachgina job `COMPLETED` bo'ladi. Qisman natija yakuniy deb ko'rsatilmaydi.

Amaldagi MM validatorida ildizdagi `warnings` ko'pi bilan 20 ta, ayrim `fields.*.sources[].quote` ko'pi bilan 400 belgi. 2026-11-13 javobida sana dalillari 417 belgi bo'lgani uchun rad etilgan. AI qisqa, aniq iqtibos yuborishi yoki kelishilgan yangi versiyada MM bu limitni oshirishi kerak; manba dalilini jim kesib tashlamaslik lozim. 2026-11-12 dagi `61393915-...` IDli tekshiruv boshqa eski javobga taalluqli: u yerda 24 warnings va `orderAnalysis` yo'q; 2026-11-13 dagi `f9a68335-...` javobiga aralashtirilmasin.

## AI jamoasi topshiradigan qabul namunalari

1. №1749, `ALL`: 2-ilova satrlari `chairAssignments`da; to'liq rosterlar alohida; ilova qamrovi va har bir manba ko'rsatilgan.
2. №1749, bitta bo'lim: faqat shu sex/bo'lim satri va unga tegishli bandlar; boshqa 98 raisning ismi qaytmaydi.
3. Bitta rahbarning ikkita bo'limi: faqat shu ikkita bo'lim natijalari.
4. O'xshash nomli ikkita sex: noaniqlik `AMBIGUOUS`, tasodifiy bog'lash yo'q.
5. Hujjatda bo'lim topilmasa: `NOT_FOUND`, bo'sh scoped ro'yxatlar, texnik xato emas.
6. Idempotent retry, o'zgargan scope/file hash bilan konflikt, ruxsat etilgan massiv va iqtibos uzunligi chegaralarida javob.

AI jamoasi ushbu fixture'lar uchun xom request/response va test natijasini beradi. MM backend shu shartnoma asosida vakolat va natija tekshiruvini amalga oshiradi.
