"""Sign release artifacts and verify them against an explicit Android certificate."""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.request import Request, urlopen


APP_SIGNING_CERTIFICATE = Path("signing/app-signing-certificate.pem")
PLAY_UPLOAD_CERTIFICATE = Path("signing/play-upload-certificate.pem")
KEY_VERSION_PATTERN = r"projects/[^/]+/locations/[^/]+/keyRings/[^/]+/cryptoKeys/[^/]+/cryptoKeyVersions/[1-9][0-9]*"


def certificate_fingerprint(certificate):
    pem = certificate.read_text()
    match = re.fullmatch(r"\s*-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\s]+)-----END CERTIFICATE-----\s*", pem)
    if not match:
        raise ValueError("Expected exactly one PEM signing certificate")
    return hashlib.sha256(base64.b64decode(match[1])).hexdigest()


def kms_get(resource, token):
    request = Request(f"https://cloudkms.googleapis.com/v1/{resource}",
                      headers={"Authorization": f"Bearer {token}"})
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def check_cloud_key(key_version, token, certificate):
    if not re.fullmatch(KEY_VERSION_PATTERN, key_version):
        raise ValueError("Specify the complete Cloud KMS key version resource name")
    metadata = kms_get(key_version, token)
    if (metadata["name"] != key_version or metadata["state"] != "ENABLED"
            or metadata["protectionLevel"] != "HSM"
            or metadata["algorithm"] != "RSA_SIGN_PKCS1_4096_SHA512"):
        raise ValueError("Expected an enabled HSM RSA_SIGN_PKCS1_4096_SHA512 key version")
    public_key = kms_get(f"{key_version}/publicKey", token)
    expected = subprocess.check_output(["openssl", "x509", "-in", str(certificate), "-pubkey", "-noout"])
    # Compare the actual public key, independent of PEM line wrapping.
    def der(pem):
        return subprocess.check_output(["openssl", "pkey", "-pubin", "-outform", "DER"], input=pem)
    if der(expected) != der(public_key["pem"].encode()):
        raise ValueError(f"Cloud KMS key does not match {certificate}")


def artifact_names(version, code):
    return [f"agentknock-foss-{version}-{code}.apk",
            f"agentknock-play-{version}-{code}.apk",
            f"agentknock-play-{version}-{code}.aab"]


def unsigned_artifacts(directory):
    return [directory / "foss-release/apk/foss/release/app-foss-release-unsigned.apk",
            directory / "play-release/apk/play/release/app-play-release-unsigned.apk",
            directory / "play-release/bundle/playRelease/app-play-release.aab"]


def sign_artifacts(inputs, outputs, certificate, storetype, keystore, alias, password_env):
    """Use the same signing APIs with Cloud KMS or disposable local test keys."""
    jsign = Path(os.environ["JSIGN_JAR"])
    apksigner = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0/lib/apksigner.jar"
    for path in [jsign, apksigner, certificate, *inputs]:
        if not path.is_file():
            raise ValueError(f"Missing signing input: {path}")
    pairs = [str(path) for pair in zip(inputs, outputs, strict=True) for path in pair]
    subprocess.run([
        "java", "--class-path", f"{apksigner}:{jsign}", "scripts/SignRelease.java",
        storetype, keystore, alias, password_env, str(certificate), *pairs,
    ], check=True)


def verify_artifacts(artifacts, app_certificate, upload_certificate, temporary):
    fingerprint = certificate_fingerprint(app_certificate)
    apksigner = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0/apksigner"
    for artifact in artifacts[:2]:
        result = subprocess.check_output(
            [str(apksigner), "verify", "--verbose", "--print-certs", str(artifact)], text=True
        )
        signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$", result, re.M)
        if signers != [fingerprint]:
            raise ValueError(f"Unexpected APK signing certificate: {artifact}")
        print(result, end="")
    verify_bundle(artifacts[2], upload_certificate, temporary)


def verify_bundle(bundle, certificate, temporary):
    truststore = temporary / "trusted-signing-certificate.p12"
    subprocess.run([
        "keytool", "-importcert", "-noprompt", "-alias", "app", "-file", str(certificate),
        "-keystore", str(truststore), "-storetype", "PKCS12", "-storepass", "certificate-only",
    ], check=True)
    # Supplying the trusted alias makes -strict also reject other signers and
    # unsigned entries, while accepting our deliberately self-signed certificate.
    subprocess.run([
        "jarsigner", "-verify", "-strict", "-keystore", str(truststore),
        "-storepass", "certificate-only", str(bundle), "app",
    ], check=True)


def write_checksums(directory):
    files = sorted(path for path in directory.iterdir() if path.is_file())
    (directory / "SHA256SUMS").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in files
    ))
