# ETIK Terrain – lot 1 (visionneuse de plans AutoCAD géoréférencés)

Application Android pour afficher un plan AutoCAD (DXF) sur les fonds Géorep NC et se situer dessus par GPS.

## Ce que fait le lot 1

- **Import d'un DXF** depuis Dropbox, le téléphone, un mail ou WhatsApp (bouton *Plans* > *Importer*).
  Le plan est converti une seule fois, puis se rouvre en moins d'une seconde.
- **Lecture** : lignes, polylignes 2D/3D (avec arcs), arcs, cercles, ellipses, splines, textes, MTEXT,
  blocs et attributs, cotes, hachures, solides, faces 3D, maillages, et **objets Covadis**
  (axes, étiquettes, PR, canalisations, panneaux, TIN…) via leur graphisme de substitution.
- **Géoréférencement** : coordonnées RGNC91-93 / Lambert NC (EPSG:3163), conversion exacte vers la carte.
- **Calques** : choix à l'import, puis bouton *Calques* à tout moment : recherche par nom,
  Tout / Aucun / Inverser (sur la liste filtrée), retour à l'état du DWG, regroupement par XREF,
  **jeux de calques** enregistrables (ex. « Réseaux », « Voirie seule »).
  Astuce : rechercher « PRTR » puis *Aucun* masque les profils en travers dessinés dans l'espace objet.
- **Fonds de carte** (bouton *Fonds*) : Géorep Imagerie, Imagerie annuelle, Cartographie, Relief,
  Carte 1/10 000 (2020), Imagerie historique IGN 1976, OpenStreetMap, ou aucun fond.
  La liste est dans `app/src/main/assets/fonds.json` (ajout d'un fond sans toucher au code).
- **GPS** : position, cap et cercle de précision sur la carte ; bandeau X / Y Lambert NC, altitude, précision.
  Bouton *Ma position* : suivi → suivi orienté selon le cap → arrêt du suivi.

- **Mode sans réseau** : *Fonds* > *Hors ligne…* télécharge le fond choisi sur une bande de 50 m autour
  des calques affichés (zoom jusqu'à 19). Les images sont enregistrées sur le téléphone et servies par un
  lues directement par la carte sous le fond en ligne : sans connexion, elles restent visibles.
  Pour PEO (tracé Daver–Ranch) : environ 770 images, 15 Mo.

Pas encore dans ce lot : XREF (le DXF ne contient que leurs références),
relevé de points / photos / traces et export DXF (lots 2 et 3).

## Obtenir l'APK

Le projet se compile avec Gradle (JDK 17, Android SDK 35). Deux façons :

### A. GitHub (recommandé)
1. Créer un compte GitHub gratuit et un dépôt **privé** (le dépôt contient la clé de signature de l'appli).
2. Y déposer tout le contenu de ce dossier, y compris le dossier caché `.github`.
3. La compilation démarre seule (onglet *Actions*). À la fin, l'APK est dans *Artifacts* > `EtikTerrain-apk`.

Plus simple : connecter GitHub à Claude (Paramètres > Connecteurs) ; Claude dépose le projet,
lance la compilation et renvoie l'APK.

### B. Android Studio sur PC
Ouvrir ce dossier dans Android Studio, attendre la synchronisation, puis *Build > Build APK(s)*.

## Installer sur le téléphone
1. Copier `EtikTerrain.apk` sur le téléphone (mail, Dropbox, câble).
2. L'ouvrir ; Android demande d'autoriser l'installation depuis cette source : accepter.
3. Au premier lancement, autoriser la localisation **précise**.

Les mises à jour s'installent par-dessus (même clé de signature) en gardant les plans importés.

## Préparer un DXF dans AutoCAD
- `ENREGISTRER SOUS` > *DXF AutoCAD 2018* (format **ASCII**, pas binaire).
- Plans dessinés en Lambert NC : rien d'autre à faire.

## Organisation du code
- `core/` : Java pur, sans dépendance Android (lecture DXF, Covadis, projection, préparation de l'affichage).
  Testé sur PEO.dxf (55 125 entités, 1 070 objets Covadis, lecture en 2 s sur PC).
- `MainActivity`, `PlanOverlayView` (dessin du plan synchronisé avec la carte), `LayerDialog`,
  `PlanStore` (import, cache, calques), `Basemaps`.
- Carte : MapLibre Native Android 11.13.5.
