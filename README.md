# qrxx

かざした先の QR コードを、見つけ次第そのまま開く Android アプリ。

- 対応: Android 9 (API 28) 〜 最新 (targetSdk 36 / Android 16)
- 出来上がり: `qrxx.apk` (= `build/qrxx.apk`) — **約 57 KB**
- 要求する権限: **`CAMERA` の 1 つだけ**

読み取り開始のボタンも、開く前の確認も無い。起動 → 映る → 開く。

> **実機で確認済み。** Sony XQ-FS44 / Android 16 (API 36) に入れ、実際の QR コードを
> 読ませて Chrome が開くところまで見ている。測った値は下の「実測」に載せた。

## できること

- 起動すると即座にカメラが立ち上がり、**画角に入った QR コードを片端から読む**。
  中央の鉤は目安で、読み取りは画面に映っている範囲すべてで行う。
- 開ける形式（`https:` `http:` `tel:` `mailto:` `geo:` `market:` などスキームを持つもの）なら、
  見つけた瞬間に対応アプリへ渡す。**確認のタップは挟まない。**
- `example.com/x` のようにスキームが無くてもホスト名に見える綴りは、`https://` を付けて開く。
- 開ける相手がいない中身（ただの文章、`WIFI:`、`MECARD:`、vCard など）は、
  下から出る受け皿に文字として出す。**コピー / 共有 / 閉じる** が選べる。
- コードが傾いていても、逆さでも読む（ZXing の検出器が切り出しシンボルを探すため）。
  白黒が反転した QR も読む。
- 暗い場所用に**ライト**のトグルが下にひとつ。
- 画面は消灯しない（読み取り中に消えると困るため）。

無いもの: 履歴、設定画面、バーコード（QR 以外）の読み取り、画像ファイルからの読み取り、
QR の生成、広告、ネットワーク通信。

## 入れかた

`qrxx.apk` を端末に移して開き、インストールする（提供元不明のアプリの許可が要る）。
初回起動でカメラの許可を一度だけ聞く。以降は聞かない。

adb が使えるなら:

```
adb install -r qrxx.apk
```

## 権限について

要求している権限は `CAMERA` だけ。ほかは次のやり方で避けている。

| ふつう要りそうな権限 | qrxx がそれを避けた方法 |
| --- | --- |
| `INTERNET` | 開く先は他アプリに渡すだけ。qrxx 自身は通信しない |
| `VIBRATE` | 見つけた合図は画面の白い明滅だけにした |
| `FLASHLIGHT` / `CAMERA` の追加 | ライトは撮影要求の `FLASH_MODE_TORCH` で点く。追加の権限は要らない |
| `QUERY_ALL_PACKAGES` / `<queries>` | 「開けるか」を事前に問い合わせず、投げてみて `ActivityNotFoundException` を受ける |
| ストレージ系 | 映像も読み取り結果も保存しない |

カメラの映像は端末の中だけで処理し、保存も送信もしない。

## 安全のための枷

読み取った文字列は他人が用意したものなので、無条件には投げない
（`app/src/main/java/io/qrxx/Opener.java`）。

- `file:` `content:` `data:` `javascript:` `intent:` `android-app:` は開かない。
  とくに `intent:` は任意の部品を名指しで起動できてしまうため。
- 投げる Intent には `CATEGORY_BROWSABLE` を付ける。ブラウザから辿れる相手だけに限ることで、
  外から呼ばれる想定の無い画面を素性の知れない文字列で叩き起こさない。
  これで受け手がいなかった場合だけ、受け皿に「それでも開く」が出る。

**ただし、確認なしで開く設計そのものに残る危険がある。**
本物そっくりの偽サイトに飛ばす QR（いわゆるクイッシング）を貼られた場合、
qrxx は何も聞かずにそこを開く。速さと引き換えの割り切りなので、
心当たりのない場所に貼られた QR には使わないほうがよい。
確認を挟みたくなったら `ScanActivity.onFound()` の `startActivity(pending)` の手前に
一枚かませるだけで済む。

## 同じコードを続けて開いてしまわない仕掛け

開いた先から戻ると qrxx は生きたまま再開するので、同じコードがまだ画角にあると
すぐまた開いてしまう。そこで「直前に開いた文字列は、**いったん画角から外れて 1.5 秒**
経つまで開き直さない」ようにしている（`ScanActivity` の `suppressed` と `REARM_MS`）。
別のコードを向ければ待たずに開く。

## 作りかた

Gradle を使う道と、使わない道の両方が置いてある。
**どちらも同じ鍵で署名した同じアプリを作る**ので、互いに上書き更新できる
（証明書の SHA-256 が一致することを確認済み）。ただし詰め方が違うので大きさは揃わない。

| | `tools/build.sh` | `./gradlew assembleRelease` |
| --- | --- | --- |
| APK | 57,992 bytes | 85,836 bytes |
| `classes.dex` | 79,564 bytes（圧縮して格納） | 75,944 bytes（**無圧縮**で格納） |
| 署名 | v2 + v3 | v2 |

Gradle 版が大きいのは、AGP が dex を無圧縮で入れるため（読み込みが速くなる代わりに嵩む）。

### Gradle 無し（速い。Android Studio が要らない）

```
bash tools/build.sh
```

Android SDK の道具（aapt2 / javac / R8 / zipalign / apksigner）だけで組み立てる。
`keystore/qrxx.jks` が無ければ最初の一回で自動的に作る。
出来上がりは `build/qrxx.apk`、ついでにルートにも `qrxx.apk` として置く。

### Gradle

```
./gradlew assembleRelease
```

出来上がりは `app/build/outputs/apk/release/app-release.apk`。

### 署名鍵

`keystore/qrxx.jks`（合言葉は `keystore.properties` に書いてある）。
**更新版を同じアプリとして配るのに要るので、無くさないこと。**
鍵が変わると、端末は別のアプリとみなして上書きインストールを拒む。

## 中身の作り

外部ライブラリは QR の復号に使う ZXing core (`app/libs/zxing-core-3.5.3.jar`) だけ。
**AndroidX も CameraX も ML Kit も使っていない。** これが 57 KB の理由。

| ファイル | 役目 |
| --- | --- |
| `ScanActivity.java` | 画面 1 枚ぶんの全部。Camera2 を直接叩き、結果を開く |
| `QrDecoder.java` | カメラの 1 フレーム (YUV_420_888) から文字列を取り出す |
| `Opener.java` | 読み取った文字列を「開ける形」に直す。開けないものを弾く |
| `PreviewFrame.java` | 映像を歪ませずに画面いっぱいへ（中央を切り出す） |
| `Viewfinder.java` | 中央の四隅の鉤 |

速さのために効いていること:

- **レイアウト XML を使わない。** 画面は Java で組む。読み込みと解析のぶんだけ速い。
- **色を見ない。** YUV の輝度 (Y) 面だけを ZXing に渡す。色差の面には触らない。
- **絵を回さない。** ZXing の検出器は向きに依存しないので、回転の補正をしない。
- **フレームを溜めない。** `acquireLatestImage()` で古い絵は捨て、最新の 1 枚だけ見る。
- **`QRCodeReader` を直に使う。** `MultiFormatReader` のように全形式を試さない。
- **R8 で削る。** ZXing core は全バーコード形式を抱えているので、
  入口から辿れない Aztec / PDF417 / DataMatrix / 各種バーコードを落とす。
- **裏に回ったらカメラを手放す。** ただしプロセスは残るので、戻ってきたときは温まったまま。

## 実測

Sony XQ-FS44 / Android 16 (API 36) で確認した値。

| 測ったこと | 結果 |
| --- | --- |
| 冷えた状態からの起動 (`am start -W`) | **TotalTime 114〜146 ms**（2 回測定） |
| APK の大きさ | **57,992 bytes** |
| `classes.dex` | 79,564 bytes（R8 前の ZXing core だけで 607,650 bytes） |
| dex に残った ZXing のクラス | 全形式ぶんのうち QR の経路だけ |
| 宣言している権限 | `android.permission.CAMERA` のみ（`aapt2 dump badging` で確認） |
| 組んだカメラのストリーム | プレビュー 1920x1080 / 解析 1280x720 |
| 実際の QR の読み取り | uid 10507 (= io.qrxx) から `ACTION_VIEW cat=[BROWSABLE]` が発火し Chrome が起動 |
| 裏に回ったとき | 同じ瞬間に `CameraService: disconnect` — カメラを掴んだままにしない |
| 例外 | logcat に `FATAL` / `NoClassDefFoundError` の類は無し |

未確認: 端末を横向きに固定した状態（画面は `portrait` に固定してあるので、
向きによらず縦で出る）と、Android 9〜15 の実機。
`minSdk 28` で組んであり、API 28 より新しい呼び出しは分岐で避けてある。
