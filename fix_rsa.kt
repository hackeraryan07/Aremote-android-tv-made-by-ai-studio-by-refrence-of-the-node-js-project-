    private fun getRsaModulusAndExponent(cert: X509Certificate): Pair<ByteArray, ByteArray> {
        val pubKey = cert.publicKey as java.security.interfaces.RSAPublicKey
        var mod = pubKey.modulus.toByteArray()
        if (mod.size > 256 && mod[0] == 0.toByte()) {
            mod = mod.copyOfRange(1, mod.size)
        }
        var exp = pubKey.publicExponent.toByteArray()
        if (exp[0] == 0.toByte()) {
            exp = exp.copyOfRange(1, exp.size)
        }
        return Pair(mod, exp)
    }
