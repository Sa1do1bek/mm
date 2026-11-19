# Kimyosanoat → MM: TTN JSON yuborish va ombor chiqimini boshlash

Ushbu shartnoma Kimyosanoat dasturchisi yuborgan `mm-waybill-post-api-guide.md` formatiga mos: Fakturaga tayyorlangan `local_waybills` JSON tanasi MMga ham **o‘zgartirilmagan baytlar** bilan yuboriladi. MM uni saqlaydi. Ombor chiqimini avtomatik boshlash uchun shu POSTga manba hujjatini aniqlaydigan ikkita HTTP header qo‘shilishi kerak.

## Manzil va Basic Auth

```http
POST https://api-navoiy-azot-mm.tenzorsoft.uz/api/integrations/kimyosanoat/events
Content-Type: application/json
Authorization: Basic <base64(username:password)>
X-Transfer-Id: 123456
X-Warehouse-1C-Id: bd71da53-3697-11ea-8a3b-a0d3c1060d4b
```

`Authorization` uchun MM ajratgan kiruvchi Basic akkaunt ishlatiladi. Faktura Bearer tokenini yubormang. Login/parolni URL yoki JSONga yozmang. `X-Tenant-Id` va `X-Company-Id` kerak emas; MM ularni o‘z sozlamasidan oladi.

| Header | Turi | Mazmuni |
| --- | --- | --- |
| `X-Transfer-Id` | musbat butun son, Java `long` chegarasida | Kimyosanoatning yakuniy ombor chiqimi hujjati IDsi; manba GET DTOdagi `id`ga teng. |
| `X-Warehouse-1C-Id` | UUID | Shu transferga tegishli omborning 1C IDsi. MMdagi ichki `warehouseId` UUIDsi bilan adashtirmang. |

**Ikkala header birga yuboriladi.** Faktura JSON tanasiga `event`, `eventId`, `payload` yoki `transferId` qobig‘ini qo‘shmang. Headerlar faqat TTN JSONga tegishli **aynan bitta** yakuniy Kimyosanoat transferini ko‘rsatishi kerak. `local_waybills` massivida bir nechta hujjat bo‘lsa, MM JSONni saqlaydi, ammo bitta `transferId` bilan avtomatik chiqim qilmaydi; `MULTIPLE_WAYBILLS` ko‘rib chiqish holati paydo bo‘ladi.

## Qachon yuborish kerak

Kimyosanoatning yuborgan yo‘riqnomasiga ko‘ra, hozirgi worker Fakturaga `createTtn()` HTTP urinishi tugaganidan keyin xuddi shu request JSONni MMga yuboradi. Faktura HTTP urinishining muvaffaqiyatsizligi ham MMga yuborishni to‘xtatmaydi. Bu **TTN requestini arxivlash** uchun yetarli; o‘zi ombor chiqimi tasdiqlanganini isbotlamaydi.

Avtomatik qoldiq kamayishini istasak, Kimyosanoat `X-Transfer-Id` va `X-Warehouse-1C-Id`ni faqat shu transfer yakuniy tasdiqlangan va manba GET APIda yakuniy holatda ko‘ringanda yuborishi kerak. Agar `createTtn()` bu vaqtdan oldin ishlasa, yuborishni yakuniy holatga ko‘chirish yoki keyin alohida ishonchli xabar yuborish zarur. MM baribir GET API orqali yakuniy holatni tekshiradi; mos bo‘lmasa avtomatik chiqim qilmaydi.

## POST JSON tanasi

JSONning yuqori qismida `local_waybills` nomli bo‘sh bo‘lmagan massiv bo‘lishi, har bir elementda `head` va `document` obyektlari bo‘lishi shart. MM bodydagi boshqa Faktura maydonlarini o‘zgartirmasdan original baytlar sifatida saqlaydi. Quyida faqat shakl ko‘rsatilgan; to‘liq DTO Kimyosanoat yuborgan Faktura yo‘riqnomasidagi JSON bilan bir xil:

```json
{
  "local_waybills": [
    {
      "head": {
        "file_name": "waybill_local_example.xml",
        "sender": {"sender_info": {"inn": "000000001"}},
        "receiver": {"receiver_info": {"inn": "000000002"}}
      },
      "document": {
        "waybill_number": "N123456",
        "waybill_date": "18.11.2026",
        "roadway": {
          "product_groups": [
            {
              "product_info": {
                "products": [
                  {
                    "title": "Mahsulot",
                    "measurement_unit": "tonna",
                    "quantity": "5",
                    "catalog": {"code": "NAMUNA_MXIK"}
                  }
                ]
              }
            }
          ]
        }
      }
    }
  ]
}
```

Bu bodydagi miqdorlar va kodlar Faktura DTOsidagi satrlar bo‘lishi mumkin. MM **stock miqdorini bu TTN requestidan olmaydi**: yakuniy sof vazn va mahsulotni Kimyosanoatning manba transfer APIlaridan tekshiradi. TTNdagi MXIK avtomatik material mosligi uchun yetarli emas; MM manba transferdagi `productNCode`ni import qilingan 1C materialiga bog‘laydi.

## MM POSTdan keyin nima qiladi

1. Basic Auth va JSON shaklini tekshiradi. To‘liq original body baytlarini, SHA-256 xeshini, qabul vaqtini va mavjud bo‘lsa manba IDlarini bazada saqlaydi. Aynan bir xil baytlar takror kelsa oldingi receipt qaytadi.
2. Ikkala header va bitta `local_waybills` elementi bo‘lsa, shu receiptni fon jarayoniga navbatga qo‘yadi. Aks holda body saqlanadi, `REVIEW_REQUIRED` holatida turadi va qoldiq kamaymaydi.
3. Fon jarayoni `GET /api/v1/mm-issue-transfers/by-warehouse1c/{transferId}?warehouse1cId={warehouse1cId}` hamda `GET /api/v1/mm-issue-transfers/resolve-warehouse?warehouse1cId={warehouse1cId}`ni MMning Kimyosanoat Basic akkaunti bilan chaqiradi.
4. Manba `id`, `fromWarehouseId`, `productId`, `productNCode`, `lastStatus`, `confirmedStatuses`, `confirmedNettoKg`, massa birligi, qayta kirish/ma’muriy yopilish/ichki ko‘chirish belgilarini tekshiradi. `lastStatus` yakuniy chiqish holatlaridan biri bo‘lishi va `confirmedStatuses`da `EXIT_ACCEPTED` bo‘lishi kerak.
5. 1C ombori MMdagi aynan bitta faol omborga, N-code aynan bitta faol materialga, yakuniy miqdor kg yoki tonnaga va shu miqdor uchun aynan bitta yetarli `OWNED` partiyali qoldiq pozitsiyasiga mos tushsa, MM ombor chiqimi hujjatini `PENDING → APPROVED → POSTED` qiladi. `POSTED`da qoldiq kamayadi.
6. Moslik noaniq, qoldiq yetarli emas yoki manba hali yakuniy bo‘lmasa, `REVIEW_REQUIRED` va sabab kodi saqlanadi. Manba API vaqtincha ishlamasa, MM oraliq vaqtni oshirib qayta urinadi. `transferId` bo‘yicha bog‘lanma takroriy qoldiq kamayishini oldini oladi.

MMda avvalgi besh maydonli `EXIT_ACCEPTED` hodisa formati ham hozircha shu endpointda qabul qilinadi, ammo yangi TTN integratsiyasi uchun unga JSON qobig‘i qo‘shish talab qilinmaydi.

## Javoblar va ko‘rish

| HTTP | Ma’nosi |
| --- | --- |
| `202 Accepted` | JSON saqlandi. `data.receiptId`, `state` va `duplicate: false` qaytadi. Bu hali stock `POSTED` degani emas. |
| `200 OK` | Aynan bir xil baytlar avval saqlangan; `duplicate: true`. |
| `400 Bad Request` | JSON shakli yoki header qiymatlari noto‘g‘ri. |
| `401 Unauthorized` | Basic akkaunt noto‘g‘ri yoki yo‘q. |
| `409 Conflict` | Bir xil JSON boshqa manba transferiga bog‘lashga urinish. |
| `503 Service Unavailable` | MM kiruvchi Basic/tenant/kompaniya sozlamalari tayyor emas. |

MM administratorlari va MM menejerlari saqlangan original JSONni `GET /api/integrations/kimyosanoat/events/waybills/{receiptId}` orqali, manba IDsi yetishmaydigan yoki bir nechta TTNli receiptlarni `GET /api/integrations/kimyosanoat/events/waybills/review` orqali ko‘ra oladi. Manba bilan bog‘langan, lekin stock tekshiruvidan o‘tmagan hodisalar mavjud `GET /api/integrations/kimyosanoat/events/review` navbatida ko‘rinadi.

## Kimyosanoat tomonida kelishilishi kerak bo‘lgan o‘zgarishlar

1. Fakturaga yuborilgan JSON bodyga tegmasdan yuqoridagi ikkita headerni MM POSTiga qo‘shish.
2. Headerlar haqiqatan shu TTNga tegishli **yakuniy** manba transferini ko‘rsatishi va POST vaqti bilan manba GET API holati o‘zaro mos bo‘lishini ta’minlash.
3. Amaldagi yo‘riqnomada MMga yuborish navbati doimiy bazaga yozilmaydi va avtomatik retry yo‘q. Server to‘xtasa yoki MM qisqa muddat ishlamasa xabar yo‘qolishi mumkin. Ishonchli avtomatik stock uchun doimiy outbox va bir xil body/headerlar bilan qayta yuborish kerak.
4. Birgalikda sinash: bitta yakuniy transfer → bitta `POSTED` chiqim; takroriy POST → yangi chiqim yo‘q; yetarli bo‘lmagan qoldiq, noto‘g‘ri yoki yo‘q header, bir nechta TTN, yakuniy bo‘lmagan manba → qoldiq o‘zgarmaydi va ko‘rib chiqish navbati paydo bo‘ladi.

TTN bekor qilish, tuzatish va manbadagi chiqimni orqaga qaytarish uchun hozir avtomatik teskari stock yozuvi yo‘q; buning uchun alohida shartnoma kerak.
