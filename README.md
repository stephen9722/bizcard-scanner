# 名片簿 BizCard

離線名片管理 App。掃描正反面、自動辨識文字、關鍵字搜尋。資料只存在手機裡。

沒有宣告 `INTERNET` 權限——這不只是「我們不上傳」的承諾,而是系統層級擋掉,app 想連網也連不出去。

---

## 怎麼建置

需要 **JDK 17**、**Android SDK 36**，以及支援 AGP 8.13 的 Android Studio（建議 Narwhal 4 Feature Drop / 2025.1.4 以上）。

1. Android Studio → Open → 選這個資料夾
2. 等 Gradle sync 完成(第一次會下載依賴,要連網)
3. 接上手機或開模擬器 → Run

命令列如果電腦已有 Gradle 8.13:

```bash
gradle assembleDebug        # APK 產在 app/build/outputs/apk/debug/
gradle testDebugUnitTest    # 跑 parser 的單元測試,不需要裝置
```

> 這份 Claude 原始壓縮包只附了 `gradle-wrapper.properties`，沒有 `gradlew`、
> `gradlew.bat` 和 `gradle-wrapper.jar`，所以直接執行 `./gradlew` 會失敗。
> 建議在本機執行 `gradle wrapper --gradle-version 8.13` 後，把三個 wrapper 檔一起 commit。
> GitHub Actions 已改用 `gradle/actions/setup-gradle` 固定安裝 Gradle 8.13，因此 CI 不受影響。

---

## 目前做了什麼

**掃描** — CameraX 拍照,畫面上有 91×55mm 的取景框。拍完會先自動找名片四邊、做透視校正並裁成正面矩形，再送 OCR；如果邊界信心不足就保留原圖直接辨識，不會因為校正失敗卡住。先拍正面再拍背面,背面可以略過。

**辨識** — ML Kit 中文文字辨識,模型打包在 APK 裡,飛航模式也能跑。辨識完會自動填欄位,存檔前一定會先進確認畫面讓你改——OCR 一定會出錯,設計上假設它會錯。

**搜尋** — 一個搜尋框,跨 14 個欄位查,包含 OCR 原文。所以就算 parser 把「研發二處」塞錯欄位,你搜「研發二處」還是找得到。`%` `_` 這些 SQL 萬用字元有做跳脫,搜「100%」不會炸。

**標籤** — 逗號分隔,列表上方會出現 chip 可以點選篩選。比對時前後補逗號,所以「客戶」不會誤中「潛在客戶」。

**正反面照片** — 詳細頁左右滑動切換,點一下全螢幕,雙指縮放。

**重複偵測** — 存檔時比對 Email 和手機(手機會把 `0912...` / `+886 912...` 等常見格式正規化後比對)。不用姓名比對,因為同名同姓太常見。

**備份與匯出**(右上角選單)

| 格式 | 內容 | 用途 |
|---|---|---|
| ZIP | JSON + CSV + 全部照片 | **唯一能完整還原的格式**,定期存這個 |
| CSV | 純文字,16 欄 | 丟進 Excel / Google 試算表整理 |
| vCard (.vcf) | 標準通訊錄格式,照片可選 | 匯入手機通訊錄或其他 App |

**匯入** — 支援 ZIP 還原和 vCard 匯入。兩者都是「加進去」而不是覆蓋,重複的(Email 或手機相同)自動略過,所以同一個備份匯入兩次不會變兩份。

---

## 幾個刻意的設計決定

**照片存檔案路徑,不存 BLOB。** 圖片放 `filesDir/cards/`,DB 只存檔名。這樣 DB 保持很小,搜尋才會快;而且 app 私有目錄不會被媒體掃描,你的名片照不會跑進 Google 相簿。

**姓名用「字最大」來判斷。** ML Kit 除了文字也回傳每行的邊界框。台灣名片上姓名幾乎一定是最大的字,而且幾乎一定是 2–4 個中文字。把「高度」和「短且是中文」兩個訊號加起來評分,比單看任一個準得多——公司名常常一樣大但很長,部門名很短但字很小。

**OCR 原文永遠保留。** 存在 `rawTextFront` / `rawTextBack`,搜尋也吃這兩欄。parser 分錯欄位不會讓資料消失,只是位置不對而已。編輯頁可以展開看原文對照。

**合併而不覆寫。** 重掃背面時,已經有值的欄位不動,只填空白的。修正過的姓名不會被第二次辨識蓋掉。

**備份是手動的,而且 Google 自動備份刻意排除了。** `data_extraction_rules.xml` 把名片目錄和 DB 排除在雲端備份外(名片是客戶個資,不該靜靜複製到 Google 伺服器),但保留在「換機直傳」。代價很明確:**要自己定期存 ZIP**。

**匯出走系統檔案選擇器(SAF)。** 不需要儲存空間權限,檔案存到你自己選的位置——雲端硬碟、Files、隨身碟、郵件附件都行。App 從頭到尾拿不到你整支手機的檔案。

---

## 2026 發布注意

專案已設定 `compileSdk = 36` / `targetSdk = 36`。Google Play 自 2026-08-31 起，
新的手機 App 與更新都必須 target Android 16 / API 36 以上。AGP 使用 8.13.2、Gradle 8.13。

## 建議的下一步(依重要性)

1. **自動定期備份提醒。** 目前完全靠自覺。用 WorkManager 每兩週提醒一次,或直接寫進固定資料夾。
2. **資料量大時換 FTS。** 目前用 `LIKE '%q%'`,幾百到一兩千張完全沒問題。超過之後改 Room 的 FTS4 虛擬表。
3. **分享自己的名片。** QR code / NFC。

### 名片校正的實作方式

目前直接在 App 內用 Kotlin/Android Bitmap 做 Sobel 邊緣、四條長邊搜尋與四點透視轉換，沒有額外加入 OpenCV/SmartCropper JNI。這樣 target API 36 時不會多一組 native ABI / 16 KB page-size 相容性風險。演算法是針對相機畫面中央的橫式名片取景框調校；偵測信心不足時會自動 fallback 到原圖。

---

## 檔案在哪

```
data/
  BusinessCard.kt      資料表定義,正反面圖檔名 + OCR 原文
  CardDao.kt           查詢,含跨欄位搜尋和重複偵測
  CardRepository.kt    escapeLike() 在這裡
  BackupManager.kt     ZIP / CSV / vCard 的匯出匯入
  CardCodec.kt         格式轉換(JSON / CSV / vCard 輸出)
  VCardReader.kt       vCard 解析,含 quoted-printable 和折行處理
ocr/
  CardOcr.kt           ML Kit 封裝,回傳文字 + 每行的高度位置
  CardParser.kt        台灣名片欄位解析。改規則主要改這裡
ui/
  CardListScreen.kt    列表 + 搜尋 + 標籤
  CameraCaptureScreen.kt  兩步驟拍攝
  CardEditScreen.kt    確認 / 編輯
  CardDetailScreen.kt  詳細頁 + 正反面切換 + 縮放
  BackupScreen.kt      備份與匯出
  CardViewModel.kt     狀態
util/ImageStore.kt     圖片檔案管理
util/CardImageProcessor.kt  名片四邊偵測 + 自動透視校正
util/PerspectiveMath.kt     四點 projective transform 幾何
```

**要調整辨識規則就改 `CardParser.kt`。** 每次遇到某張名片讀錯,就去
`app/src/test/java/.../CardParserTest.kt` 加一個測試案例再修——這是最省力的方式,
可以避免修好 A 名片卻弄壞 B 名片。目前另外包含透視幾何 regression tests；parser / 備份格式測試也保留，`gradle testDebugUnitTest` 就能跑。

---

## 授權

目前專案沒有附 `LICENSE`，因此不要把它描述成「沒有授權限制」。如果 repo 要公開，
請依你希望別人如何使用這份程式碼選擇 MIT、Apache-2.0 或其他授權；第三方依賴仍各自受其授權條款約束。
