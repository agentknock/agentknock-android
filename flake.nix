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
      androidComposition = includeEmulator: platformVersions: pkgs.androidenv.composeAndroidPackages {
        abiVersions = [ "x86_64" ];
        buildToolsVersions = [ buildToolsVersion ];
        inherit includeEmulator platformVersions;
        includeNDK = false;
        includeSystemImages = includeEmulator;
        systemImageTypes = [ "google_apis" ];
      };
      androidSdk = (androidComposition false [ "37.0" ]).androidsdk;
      emulatorSdk = (androidComposition true [ "37.0" ]).androidsdk;
      androidShell = sdk: extraPackages: pkgs.mkShell {
        packages = [
          sdk
          pkgs.jdk17
          (pkgs.python3.withPackages (p: [ p.pillow ]))
        ] ++ extraPackages;

        ANDROID_HOME = "${sdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
        JAVA_HOME = "${pkgs.jdk17}";
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";
      };
      ciEmulatorShell = platform: (androidShell
        (androidComposition true [ platform ]).androidsdk
        [ pkgs.bundletool ]).overrideAttrs (_: {
          AGENTKNOCK_EMULATOR_IMAGE = "system-images;android-${platform};google_apis;x86_64";
          AGENTKNOCK_EMULATOR_API = builtins.head (pkgs.lib.splitString "." platform);
        });
    in
    {
      devShells.${system} = {
        default = androidShell androidSdk [ ];
        emulator = androidShell emulatorSdk [ ];
        ci = androidShell androidSdk [
          pkgs.actionlint
          pkgs.bundletool
          pkgs.openssl
          pkgs.shellcheck
        ];
        ci-emulator-26 = ciEmulatorShell "26";
        ci-emulator-37 = ciEmulatorShell "37.0";
      };
    };
}
