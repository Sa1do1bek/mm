# Buyruqdan inventarizatsiya: AI holati va o'zgarishlar hisoboti

Sana: 2026-11-12
Holat: kod va berilgan shartnomalar bo'yicha tahlil; yangi AI funksiyasi joriy qilinmagan.

## Xulosa

Mavjud AI integratsiyasi yuklangan inventarizatsiya hujjatidan **bitta sanash formasiga** tegishli ayrim maydonlarni va `commissionAnalysisVersion: 1` orqali komissiyalar ro'yxatini ajratadi. Yangi buyruq oqimi uchun bu yetarli emas: buyruqning har bir manba bandi, undagi barcha obyektlar, vazifa turi, ishchi komissiyaga havola, tasdiqlashga oid aniq faktlar va ajralgan sanalar kerak. AI ularni taklif sifatida beradi; ERP yozuvlari va vakolatlar MM tizimida qoladi.

## Amaldagi holat va bo'shliq

| Masala | Hozir | Kerakli o'zgarish |
| --- | --- | --- |
| Endpoint | `POST /v1/inventory-document-extraction/extract`, multipart `metadata` va `files` | Shu endpointda qo'shimcha `orderAnalysisVersion: 1` yoki AI jamoasi bilan kelishilgan ekvivalent versiyali shartnoma |
| Formaga taklif | `fields`da nom, tur, yagona ombor/komissiya, reja sanalari; `documentFacts`da buyruq raqami/sanasi va hisob sanasi | Buyruq sarlavhasi, barcha sanalar va buxgalteriyaga topshirish muddati manba dalili bilan alohida |
| Komissiyalar | `commissionAnalysis` ro'yxati, a'zolar, rol, sahifa, `sourceKey` | Har asl bandni mos ishchi komissiya `sourceKey`iga bog'lash; markaziy komissiyani bandlardan ajratish |
| Buyruq bandlari | Alohida `workItems` yo'q | Ilova/band raqami, barcha obyektlar, vazifa turlari, dalil va tarkib holati bilan to'liq ro'yxat |
| To'liqlik | Umumiy ogohlantirish va komissiya validatsiyasi | Ilova bo'yicha `coverage`, yo'q bandlar va qisman o'qilgan sahifalar haqida aniq holat |
| ERP moslik | MM backend yagona ombor/komissiya nomini joriy kompaniyada tekshirishi mumkin | Ko'p obyekt/xodim/komissiya moslash MM backendda; AI ERP ID yoki vakolatni belgilamaydi |

Kod bo'yicha asosiy kuzatishlar: `InventoryAiDraftService.AnalysisRequest`da faqat `commissionAnalysisVersion` mavjud; `HttpMmAiClient` shu versiyani metadata'ga qo'shadi; `InventoryAiResultService` faqat amaldagi `fields`, `documentFacts` va `commissionAnalysis`ni tekshiradi. `InventoryCommissionExtractionValidator` 300 tagacha komissiya va 1000 tagacha a'zoni qabul qiladi, lekin buyruq bandlari sxemasini tekshirmaydi. Demak yangi maydonni faqat model javobiga qo'shish kifoya emas: MM backend ham request versiyasi, response validatsiyasi va saqlashni yangilashi kerak.

## AI ishlab chiquvchiga aniq topshiriq

[Batafsil reja va taklif etilgan shartnoma](inventory-order-ai-developer-plan-uz.md) bo'yicha versiyali `orderAnalysis` javobini tayyorlash; bandlarni kesmasdan, manbani ko'rsatib, komissiya havolalari va sanalar semantikasini tekshirish. №1749 bo'yicha qabul namunasi va test natijasini yuborish. ERPga komissiya yaratish so'rovi, foydalanuvchi IDsi, ombor/bo'lim UUIDsi yoki inventarizatsiya yozuvi yaratish AI vazifasiga kirmaydi.

## MM jamoasi uchun bog'liqlik

Yakuniy AI shartnomasi tasdiqlangach, MM backend yangi versiyani so'raydi, barcha `workItems`ni saqlaydi, kompaniya/vakolat doirasida moslaydi va foydalanuvchi tasdiqlagan taqsimotlardan qoralama yaratadi. Frontend buyruqni ko'rish va bandlarni tekshirish sahifasini quradi. AI javobi kelmaguncha bu oqim tayyor deb ko'rsatilmaydi.

Manbalar: `inventory-order-implementation-plan.md`, `inventory-document-extraction-api.md`, `inventory-commission-mm-integration-handoff.md` va joriy MM backend kodi. №1749 bo'yicha sonlar avvalgi integratsiya hisobotidagi lokal natija; ushbu hisobotda PDF qayta sanalmagan.
