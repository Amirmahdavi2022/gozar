package hev.htproxy

/**
 * The JNI surface of hev-socks5-tunnel.
 *
 * The package and class name are not ours to choose: the native library
 * registers its methods against this exact path at build time. Renaming this
 * file breaks the binding at runtime with no compile error, so it stays here
 * even though it sits outside the rest of the app's package.
 */
object TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
