package hev.htproxy

/**
 * The JNI surface of hev-socks5-tunnel.
 *
 * The package and class name are not ours to choose: the native library
 * registers its methods against this exact path at build time. Renaming this
 * file breaks the binding at runtime with no compile error, so it stays here
 * even though it sits outside the rest of the app's package.
 *
 * 🚨 Every method the library registers has to be declared here, including ones this app never
 * calls. RegisterNatives is all-or-nothing and it runs inside JNI_OnLoad, which is the first
 * thing that happens when the class is touched — so one missing declaration does not fail at the
 * call site later, it fails while the library is loading, before a single line of the native
 * tunnel's own log exists. That is what an app closing with an empty log looks like from outside.
 * TProxyIsRunning arrived in hev 2.17 and is declared here for that reason, not because we need
 * the answer. Checked against the binding upstream documents, method for method.
 */
object TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
