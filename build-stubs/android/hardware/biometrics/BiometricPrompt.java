package android.hardware.biometrics;
import android.content.Context;
import android.os.CancellationSignal;
import java.util.concurrent.Executor;
/* ビルド用の型だけの置き物(実機では本物のクラスが使われる。APKには含めない) */
public class BiometricPrompt {
    public static class Builder {
        public Builder(Context c) {}
        public Builder setTitle(CharSequence t) { return this; }
        public Builder setSubtitle(CharSequence t) { return this; }
        public Builder setNegativeButton(CharSequence t, Executor e, android.content.DialogInterface.OnClickListener l) { return this; }
        public BiometricPrompt build() { return null; }
    }
    public static class AuthenticationResult {}
    public abstract static class AuthenticationCallback {
        public void onAuthenticationError(int code, CharSequence msg) {}
        public void onAuthenticationSucceeded(AuthenticationResult r) {}
        public void onAuthenticationFailed() {}
        public void onAuthenticationHelp(int code, CharSequence msg) {}
    }
    public void authenticate(CancellationSignal s, Executor e, AuthenticationCallback cb) {}
}
