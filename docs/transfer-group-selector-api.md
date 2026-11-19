# Transfer group select: ish rejasi va API kontrakti

Sana: 2026-11-03. Manba: frontendning 2026-11-02 talabi va mavjud backend.
Bu hujjat repository implementatsiyasini tavsiflaydi; production deploy qilinganini anglatmaydi.

## Qaror va vazifa chegarasi

Foydalanuvchi tasdiqlagan qoida: **butun guruhni faqat jo‘natuvchi ombor va ADMIN/MM_MANAGER ko‘radi**.
Qabul qiluvchi omborga a’zolik butun guruh detail/progressini ko‘rishga ruxsat bermaydi.
Bu qoida list, detail va progress uchun bir xil. Child transferning alohida o‘qish/ijro siyosati o‘zgarmaydi.

Guruh yaratish, cancel-remainder, child transfer, dispatch/receive va progress miqdorlarini hisoblash
algoritmlari ushbu o‘zgarish doirasiga kirmaydi. Global security config, error envelope va company siyosati
o‘zgartirilmaydi. Yangi migration yoki indeks qo‘shilmaydi.

## Bosqichma-bosqich ish rejasi

1. **Bazaviy tekshiruv:** mavjud user o‘zgarishlarini aniqlash; group controller/service/repository,
   RoleDataScopeService, pagination, localization va error mappingni o‘qish; mavjud grouped-transfer
   hamda transit conservation testlarini o‘tkazish.
2. **Kontrakt:** faqat `id/code/name` option DTO; `search/sourceWarehouseId/page/size`;
   ombor ruxsati va current-company cheklovini belgilash.
3. **Query:** parametrli JPQL projection; tenant/company/soft-delete/source scope va searchni
   paginationdan oldin DBda qo‘llash; data/count uchun bitta predicate; ikki maydonli barqaror sort.
4. **Service va HTTP:** search normalizatsiyasi, uzunlik va pagination validatsiyasi; source filterga
   ruxsat tekshiruvi; localized option label; list endpoint va OpenAPI.
5. **O‘qish mosligi:** detail/progress uchun bitta parent authorization metodi; mavjud bo‘lmagan,
   o‘chirilgan yoki ruxsatsiz guruh progressiga `404`; valid guruhning progress formatini saqlash.
6. **Verification:** service testlari; H2 va haqiqiy PostgreSQL query testlari; haqiqiy security filterlari
   va service bilan MockMvc; OpenAPI; tegishli mavjud transfer regressiyalari.
7. **Handoff:** yakuniy kontrakt, xato kodlari, test natijalari va frontend ulash tartibini hujjatlashtirish.
   Deploydan keyin target muhitdagi mavjud ruxsatli guruh bilan smoke test bajariladi.

## Endpoint

```http
GET /api/v1/warehouse/transfer-groups?search=WTG&page=0&size=20
Authorization: Bearer <token>
X-Tenant-Id: <tenant>
X-Company-Id: <company-uuid>
Accept-Language: uz
```

| Parametr | Default | Qoida |
| --- | --- | --- |
| `search` | Filtr yo‘q | Trimdan so‘ng 200 Unicode belgigacha; registrga bog‘liq bo‘lmagan code OR name substring |
| `sourceWarehouseId` | Filtr yo‘q | UUID; mavjud scope ichida qo‘shimcha jo‘natuvchi ombor filtri |
| `page` | `0` | Noldan boshlanadi, manfiy bo‘lmaydi |
| `size` | `20` | `1..100` |

`search` bo‘sh bo‘lsa filtr qo‘llanmaydi. `%`, `_`, `!` literal belgilar; query parametrli.
Qidiruv code va saqlangan name ichida, shu jumladan mavjud barcha name tarjimalarida ishlaydi.
Description qidiruvga kirmaydi. `name` javobda Accept-Language bo‘yicha mavjud `LocalizedText`
fallback siyosati bilan tarjima qilinadi. Oddiy legacy nomlar ham qo‘llanadi.

Tartib doim `createdAt DESC, id DESC`. Status bo‘yicha yashirin ACTIVE filtri yo‘q.
Bu offset pagination: o‘zgarmagan ma’lumotlarda tartib barqaror; parallel insert/delete paytida
bir nechta HTTP so‘rovlari uchun yagona snapshot kafolatlanmaydi.

## Javob

```json
{
  "data": {
    "content": [
      {
        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "code": "WTG-42",
        "name": "Reagentlar"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  },
  "message": null,
  "errorCode": null,
  "timestamp": "2026-11-03T10:00:00"
}
```

Bu misol; ID target muhitda mavjud test guruhi degani emas. Mavjud ApiResponse timestamp turi
`LocalDateTime`; unga UTC `Z` qo‘shilmaydi. `success` maydoni qo‘shilmaydi.

HTTP optionda `id/code/name` majburiy, null emas. Legacy bo‘sh code uchun faqat ko‘rsatish maqsadida
`WTG-<full-group-uuid>`, bo‘sh name uchun localized “Ko‘chirish guruhi” ishlatiladi.
Bu fallbacklar DBga yozilmaydi; search saqlangan code/name bo‘yicha ishlaydi, sun’iy fallback bo‘yicha emas.
Target muhitdagi legacy yozuvlar auditi production bazasiga kirishsiz tasdiqlangan deb hisoblanmaydi.

`totalElements` va `totalPages` barcha scope/filtrlardan keyingi sonlar. Mos guruh yo‘q bo‘lsa
`content: []`, ikkala umumiy son `0`. Diapazondan keyingi sahifada content bo‘sh, umumiy sonlar saqlanadi.
Progress agregatlari, child documentlar va material satrlari ro‘yxat uchun yuklanmaydi.
To‘liq sahifada data va count uchun ikkita SQL; ayrim qisqa sahifalarda Spring Data countni o‘tkazib yuborishi mumkin.

## Ruxsatlar va xatolar

Rollari: ADMIN, MM_MANAGER, WAREHOUSE_MANAGER, WAREHOUSE_OPERATOR, WAREHOUSE_KEEPER.
WAREHOUSE_HEAD va boshqa rollarga ushbu vazifada yangi ruxsat berilmaydi.

- ADMIN/MM_MANAGER: faqat joriy tenant va company guruhlari.
- Warehouse rollari: faqat o‘ziga biriktirilgan jo‘natuvchi ombor guruhlari.
- Ombor biriktirilmagan warehouse foydalanuvchi: `404`, barcha guruhlarga fallback yo‘q.
- Boshqa source warehouse filtri: `404`; mavjud bo‘lmagan yoki boshqa tenant/company ombori ham `404`.
- Boshqa omborlarning guruhlari listda yashiriladi; counterga ham kirmaydi.
- `companyScope=CURRENT_COMPANY` mavjud TenantFilter tomonidan tekshiriladi.
  Boshqa qiymat `400/InvalidCompanyScope`; parametr admin uchun company cheklovini ochmaydi.

Mavjud error mapping saqlanadi; frontend taklifidagi umumiy nomlarga butun tizim ko‘chirilmaydi:

| HTTP | Haqiqiy errorCode | Holat |
| --- | --- | --- |
| 400 | `BadRequestException` | Search uzunligi, page/size chegarasi |
| 400 | `MethodArgumentTypeMismatchException` | Noto‘g‘ri UUID yoki integer |
| 400 | `InvalidCompanyScope` | Noto‘g‘ri companyScope |
| 401 | `AUTHENTICATION_REQUIRED` | Autentifikatsiya yo‘q/yaroqsiz; JWT revocationning mavjud kodlari ham saqlanadi |
| 403 | `AccessDeniedException` | O‘qish roli yetarli emas |
| 404 | `NotFoundException` | Source warehouse yoki guruh mavjud emas/ruxsatdan tashqarida |
| 500 | `INTERNAL_SERVER_ERROR` | Mavjud kutilmagan server xatosi handleri |

Search uzunligi haqidagi yangi xabar uz/ru/en uchun mavjud message bundle orqali tarjima qilinadi.
Token/header tenant/company nomuvofiqliklari mavjud security filter qoidalari bilan qoladi.

## Frontend handoff

`src/features/warehouse-delta/ReadViews.tsx` → `TransferGroupProgressView`:

1. Select ochilganda `page=0&size=20`; qidiruv o‘zgarsa debounce va page reset.
2. Label: `code — name`, value: `id`.
3. Keyingi sahifalar metadata bilan yuklanadi; yangi search eskirgan so‘rovni bekor qiladi.
4. “Yuklash” mavjud `GET /api/v1/warehouse/transfer-groups/{id}/progress`ni chaqiradi.
5. Search/page o‘zgarganda tanlov saqlanadi; tenant/company/user yoki faol ombor konteksti
   o‘zgarganda ro‘yxat, tanlov va oldingi progress tozalanadi.
6. Faol omborning faqat outbound guruhlari kerak bo‘lsa `sourceWarehouseId` yuboriladi.
   Ushbu parametr incoming/outgoing birgalikdagi `warehouseId` filtri emas.
7. Qabul qiluvchi xodim uchun ro‘yxat bo‘sh bo‘lishi biznes qoidasiga mos;
   alohida transfer task mavjudligi parent group mavjudligi yoki unga kirish huquqini anglatmaydi.

Bu repository backendga tegishli; yuqoridagi frontend fayllari bu yerda o‘zgartirilmaydi.

## Testlarni takrorlash

```sh
mvn -o -Dtest=TransferGroupReadServiceTest,TransferGroupOptionQueryTest,TransferGroupOptionsHttpTest,GroupedTransferServiceTest,TransferTransitConservationTest test
```

Query testlarini alohida, disposable PostgreSQL bazasida takrorlash:

```sh
TRANSFER_GROUP_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55439/postgres \
  mvn -o -Dtest=TransferGroupOptionQueryTest test
```

PostgreSQL test useri `transfer_test`; test bazasi bo‘sh, alohida va faqat test uchun bo‘lishi kerak.
Bu query testi Hibernate create/drop bilan bajariladi; production bazasida ishga tushirilmaydi.
Haqiqiy production Flyway migration rolloutini tasdiqlamaydi; bu vazifada migration o‘zgarmagan.

## Yakuniy verification — 2026-11-03

- Bosqichlar 1–6 va repository handoff hujjati bajarildi.
- Yangi service/query/HTTP testlari: 44 ta, barchasi muvaffaqiyatli.
- Tegishli mavjud regressiyalar bilan birga: **102 test, 0 failure, 0 error, 0 skipped**.
- Querylarning 6 ta testi haqiqiy, alohida PostgreSQL 17.4 bazasida ham qayta bajarildi:
  **6 test, 0 failure, 0 error, 0 skipped**. Hikari haqiqiy PostgreSQL JDBC ulanishini ishlatdi.
- Query performance testi: to‘liq sahifa uchun 2 prepared statement, 0 entity load, 0 collection load.
- OpenAPI list operation va uchta maydonli option schema HTTP orqali tekshirildi.
- `git diff --check`: muvaffaqiyatli.

102 ta test uchun ishlatilgan regressiya to‘plami:

```sh
mvn -o -Dtest=TransferGroupReadServiceTest,TransferGroupOptionQueryTest,TransferGroupOptionsHttpTest,GroupedTransferServiceTest,TransferTransitConservationTest,TransferProgressServiceTest,TransferReceiptServiceTest,TransferPostingServiceTest,TransferCommandSafetyTest,TransferWorkspaceContextTest,HoldingTransferConservationTest test
```

Kengaytirilgan tekshiruvda qo‘shimcha `CustodyOwnershipTransferSettlementTest` mavjud xato bilan yiqildi:
`InsufficientStockException`, account `002`, requested `40`, available `0`.
U o‘zgartirilmagan **HEAD `13c0f92b`** arxivining alohida `/private/tmp` nusxasida ham aynan takrorlandi.
Custody production/test kodi o‘zgartirilmadi; bu xato yashirilmadi yoki test skip bilan yopilmadi.
Yuqoridagi 102 talik to‘plam ushbu alohida, avvaldan mavjud failing testni o‘z ichiga olmaydi.
Bu butun repository test suite’i yashil degani emas.

Production deploy, production legacy ma’lumotlar auditi va alohida frontend repositorysida selectni
ulash bajarilmadi. Deploy qilingan endpoint yoki production test guruhi mavjud deb da’vo qilinmaydi.
