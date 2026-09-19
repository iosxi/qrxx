package io.qrxx;

import android.content.Intent;
import android.net.Uri;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 読み取った文字列を「そのまま開ける形」に直す。開けないと分かっているものは null を返す。
 *
 * 読み取った中身は他人が用意したものなので、無条件には投げない。
 */
final class Opener {

    private Opener() {
    }

    /** scheme はあるが、ACTION_VIEW に載せると危ない・意味が無いもの。 */
    private static boolean blocked(String scheme) {
        switch (scheme) {
            case "file":        // FileUriExposedException になる
            case "content":     // 他アプリの内部データを指しうる
            case "data":
            case "javascript":
            case "intent":      // 任意の部品を名指しで起動できてしまう
            case "android-app":
                return true;
            default:
                return false;
        }
    }

    /** scheme を持たないが、https として開いてよさそうな綴り (example.com/x など)。 */
    private static final Pattern BARE_HOST = Pattern.compile(
            // バックスラッシュを避けて文字クラスで書く。読むときに数えなくて済む。
            "^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?([.][A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*"
                    + "[.][A-Za-z]{2,}(:[0-9]{1,5})?([/?#][^ ]*)?$");

    static Intent viewIntent(String raw) {
        final String text = raw.trim();
        if (text.isEmpty() || text.indexOf('\n') >= 0) return null;

        // QR 独自の書式。ACTION_VIEW で受け取る相手はいないので、文字として見せる。
        final String upper = text.toUpperCase(Locale.US);
        if (upper.startsWith("WIFI:") || upper.startsWith("MECARD:")
                || upper.startsWith("MATMSG:") || upper.startsWith("BEGIN:")) {
            return null;
        }

        Uri uri = Uri.parse(text);
        String scheme = uri.getScheme();
        if (scheme == null) {
            if (!BARE_HOST.matcher(text).matches()) return null;
            uri = Uri.parse("https://" + text);
            scheme = "https";
        }
        if (blocked(scheme.toLowerCase(Locale.US))) return null;

        // QR の英数字モードは大文字なので、パスキーの QR は "FIDO:/..." と大文字で来る。
        // Android の IntentFilter の scheme 照合は大文字小文字を区別するため、
        // 小文字の scheme しか宣言していない受け手には そのままだと届かない。
        // (Google Play 開発者サービスは fido と FIDO の両方を宣言しているが、
        //  他の認証器が同じとは限らない。) RFC 3986 上 scheme の比較は
        //  大文字小文字を問わないので、小文字に揃えてから渡す。
        final Intent intent = new Intent(Intent.ACTION_VIEW, uri.normalizeScheme());
        // ブラウザから辿れる相手だけに限る。素性の知れない文字列で
        // 外から呼ばれる想定の無い画面を叩き起こさないための枷。
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        return intent;
    }
}
