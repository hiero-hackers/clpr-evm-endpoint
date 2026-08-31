// SPDX-License-Identifier: Apache-2.0
package org.hiero.clpr.relay.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Mints self-signed ECDSA P-384 CA certificate/key pairs at test time for the mutual-TLS integration
 * tests.
 *
 * <p>There is no public Java API to build a self-signed certificate, so this shells out to the
 * JDK-bundled {@code keytool} (located via {@code java.home}, so no {@code PATH} assumption) to generate
 * a P-384 keypair and a minimized self-signed CA certificate (subject {@code CN=CLPR} — the fixed DN the
 * relay stamps as the leaf issuer, {@code basicConstraints CA:TRUE},
 * {@code keyUsage keyCertSign,cRLSign}) into a PKCS#12 keystore, then re-serializes them into the exact
 * on-disk formats the relay consumes: the private key as PKCS#8 DER and the certificate as DER (both via
 * {@code getEncoded()}). The DER certificate bytes are what gets published in the on-chain peer roster
 * ({@code ClprEndpoint.tls_certificate}) — the relay itself reads only the key; it generates an Ed25519
 * leaf signed by this CA key and presents it at the handshake, and peers accept a leaf that chains to
 * this CA.
 */
final class TestTlsCerts {

    private TestTlsCerts() {}

    /**
     * A generated endpoint's CA TLS material.
     *
     * @param certDer  DER encoding of the self-signed CA certificate (published on-chain); the relay
     *                 does not read the certificate from disk, only {@code keyPath}
     * @param certPath path to the CA certificate written as DER (the source for on-chain publication)
     * @param keyPath  path to the CA private key written as PKCS#8 DER (fed to {@code relay.grpc.sync.tlsKeyPath})
     */
    record Material(byte[] certDer, Path certPath, Path keyPath) {}

    /**
     * Generate an ECDSA P-384 self-signed CA certificate (subject {@code CN=CLPR}) into {@code dir}.
     *
     * @param cn  file-name prefix / keystore alias for this endpoint (e.g. {@code relay-a}); the
     *            certificate subject is always the fixed {@code CN=CLPR} so leaves chain to it
     * @param dir the directory to write the keystore and the DER cert/key files into
     * @return the generated material
     * @throws Exception if keytool fails or the keystore cannot be read back
     */
    static Material generate(final String cn, final Path dir) throws Exception {
        final String pass = "changeit";
        final Path keystore = dir.resolve(cn + "-keystore.p12");
        final String keytool =
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString();

        final ProcessBuilder pb = new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "clpr",
                "-keyalg",
                "EC",
                "-groupname",
                "secp384r1",
                "-sigalg",
                "SHA384withECDSA",
                "-dname",
                "CN=CLPR",
                "-validity",
                "3650",
                "-ext",
                "bc:c=ca:true",
                "-ext",
                "ku:c=keyCertSign,cRLSign",
                "-keystore",
                keystore.toString(),
                "-storetype",
                "PKCS12",
                "-storepass",
                pass,
                "-keypass",
                pass);
        pb.redirectErrorStream(true);
        final Process process = pb.start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("keytool failed (exit " + exit + ") generating '" + cn + "': " + output);
        }

        final KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, pass.toCharArray());
        }
        final X509Certificate cert = (X509Certificate) ks.getCertificate("clpr");
        final PrivateKey key = (PrivateKey) ks.getKey("clpr", pass.toCharArray());

        final byte[] certDer = cert.getEncoded(); // X.509 DER
        final byte[] keyDer = key.getEncoded(); // PKCS#8 DER
        final Path certPath = dir.resolve(cn + "-cert.der");
        final Path keyPath = dir.resolve(cn + "-key.der");
        Files.write(certPath, certDer);
        Files.write(keyPath, keyDer);

        return new Material(certDer, certPath, keyPath);
    }
}
