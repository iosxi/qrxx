# ZXing の core は全形式 (Aztec / PDF417 / DataMatrix / 各種バーコード) を抱えている。
# qrxx が使うのは QR の読み取りだけなので、R8 に入口から辿らせて残りを落とす。
# 入口はランチャーから呼ばれるこの 1 クラスだけ。
-keep class io.qrxx.ScanActivity { *; }

# 難読化はしない。落ちたときのログをそのまま読めるほうが得。
-dontobfuscate
