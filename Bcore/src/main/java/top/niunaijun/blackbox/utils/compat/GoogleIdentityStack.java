package top.niunaijun.blackbox.utils.compat;

import java.lang.StackWalker.StackFrame;

/**
 * Loaded only on Android 14 and newer. Measurement dynamite runs as obfuscated
 * classes such as m7.er, so the class name does not mention Google. The
 * class loader path still points at the dynamite module.
 */
final class GoogleIdentityStack {
    private GoogleIdentityStack() {
    }

    static boolean needsHost() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(stream -> stream.limit(40).anyMatch(GoogleIdentityStack::isGoogle));
    }

    private static boolean isGoogle(StackFrame frame) {
        Class<?> type = frame.getDeclaringClass();
        if (GoogleIdentityContext.isGoogleCallerName(type.getName())) {
            return true;
        }
        ClassLoader loader = type.getClassLoader();
        return loader != null && GoogleIdentityContext.isGoogleCallerLoader(String.valueOf(loader));
    }
}
