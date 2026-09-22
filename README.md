# Sütősegéd

Sütési fázistervező a **Bosch HBG7341.1 (HBG7341B1)** beépíthető sütőhöz, Android natív app.

A gyári sütősegéd egy programot ad ételenként, és az értékei hideg sütőtérre, energiatakarékos
üzemre vannak hangolva. Ez az app ehelyett **többfázisú tervet** ad: melyik fűtési mód, hány fokon,
hány percig, melyik szinten, milyen tartozékkal — és elmagyarázza, miért.

## Mit tud

- Nyersanyag + darabszám + összsúly + kívánt eredmény (pecsenye, lassú, grill, Air Fry, kímélő párolás…)
- Fázisterv beépített visszaszámlálóval és értesítéssel fázisonként
- Külön "Miért így?" magyarázat — maghőmérséklet, Maillard, kollagén, pára
- Tervek mentése, saját jegyzetekkel ("10 perccel több kellett", "nem puhult meg")
- Fénykép a kész ételről a mentett tervhez
- Törlés

A sütő teljes adatlapja (13 fűtési mód a tartományaival, betolási magasságok, a gyári beállítási
táblázat vonatkozó sorai, Air Fry és kímélő párolás szabályai) az appba van építve —
lásd `OvenKnowledge.kt`. A javaslatok minőségén ott lehet hangolni, a felülethez nem kell nyúlni.

## Beüzemelés

1. Gemini API-kulcs: https://aistudio.google.com/apikey
2. Az appban **Beállítás** fül → kulcs beillesztése → Mentés
3. Modell: alapértelmezés `gemini-2.5-flash`; a mező szabadon átírható, ha új modell jön ki

## Fordítás

A `.github/workflows/build.yml` minden pusholáskor lefordítja az APK-t.
GitHub → **Actions** → a legutolsó futás → **Artifacts** → `sutoseged-debug-apk`.

Az APK aláíratlan debug build, ezért a telepítéshez engedélyezni kell az ismeretlen forrást.

## Állapot

Első verzió: fázistervező. Később tervezett:

- Recept-adatbázis: saját szavas leírás → AI kérdez → végleges recept + sütési terv
- Home Connect API: a terv közvetlen kiküldése a sütőre
  (hivatalos API, de OAuth miatt kell hozzá kis szerveroldal, és a készüléken
  engedélyezni kell az "Állandó távoli indítás" beállítást)
