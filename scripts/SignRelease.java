import com.android.apksig.ApkSigner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.zip.ZipFile;
import jdk.security.jarsigner.JarSigner;
import net.jsign.KeyStoreBuilder;
import net.jsign.KeyStoreType;

// Use the signing APIs so Android's existing certificate can be supplied with
// the remote key. Build Tools 36's apksigner CLI rejects --cert with --ks.
class SignRelease {
    public static void main(String[] args) throws Exception {
        if (args.length < 7 || (args.length - 5) % 2 != 0) {
            throw new IllegalArgumentException(
                    "Expected store type, store, alias, password environment variable, certificate, "
                            + "and one or more APK/AAB input/output pairs");
        }
        String password = System.getenv(args[3]);
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Missing signing credential: " + args[3]);
        }
        var builder = new KeyStoreBuilder()
                .storetype(KeyStoreType.valueOf(args[0])).keystore(args[1]).storepass(password).certfile(args[4]);
        var keystore = builder.build();
        var provider = builder.provider();
        if (provider != null) {
            Security.addProvider(provider);
        }
        var key = (PrivateKey) keystore.getKey(args[2], password.toCharArray());
        var factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate;
        try (var input = Files.newInputStream(Path.of(args[4]))) {
            certificate = (X509Certificate) factory.generateCertificate(input);
        }
        var certificates = List.of(certificate);
        for (int i = 5; i < args.length; i += 2) {
            if (args[i].endsWith(".apk")) {
                var signer = new ApkSigner.SignerConfig.Builder("app", key, certificates).build();
                new ApkSigner.Builder(List.of(signer))
                        .setInputApk(Path.of(args[i]).toFile())
                        .setOutputApk(Path.of(args[i + 1]).toFile())
                        .setV1SigningEnabled(false)
                        .setV2SigningEnabled(true)
                        .setV3SigningEnabled(true)
                        .setV4SigningEnabled(false)
                        .build().sign();
            } else if (args[i].endsWith(".aab")) {
                var jarBuilder = new JarSigner.Builder(key, factory.generateCertPath(certificates))
                        .digestAlgorithm("SHA-512");
                if (provider == null) {
                    jarBuilder.signatureAlgorithm("SHA512withRSA");
                } else {
                    jarBuilder.signatureAlgorithm("SHA512withRSA", provider);
                }
                try (var input = new ZipFile(args[i]); var output = Files.newOutputStream(Path.of(args[i + 1]))) {
                    jarBuilder.build().sign(input, output);
                }
            } else {
                throw new IllegalArgumentException("Expected an APK or AAB: " + args[i]);
            }
        }
    }
}
