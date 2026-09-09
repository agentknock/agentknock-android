{
  description = "Agentknock Android development environment";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      buildToolsVersion = "36.0.0";
      androidComposition = includeEmulator: pkgs.androidenv.composeAndroidPackages {
        abiVersions = [ "x86_64" ];
        buildToolsVersions = [ buildToolsVersion ];
        inherit includeEmulator;
        includeNDK = false;
        includeSystemImages = includeEmulator;
        platformVersions = [ "37.0" ];
        systemImageTypes = [ "google_apis" ];
      };
      androidSdk = (androidComposition false).androidsdk;
      emulatorSdk = (androidComposition true).androidsdk;
      androidShell = sdk: pkgs.mkShell {
        packages = [
          sdk
          pkgs.jdk17
          (pkgs.python3.withPackages (p: [ p.pillow ]))
        ];

        ANDROID_HOME = "${sdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
        JAVA_HOME = "${pkgs.jdk17}";
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";
      };
    in
    {
      devShells.${system} = {
        default = androidShell androidSdk;
        emulator = androidShell emulatorSdk;
      };
    };
}
