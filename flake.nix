{
  description = "jsim - JIT Singleton and IO Manager";
  inputs.nixpkgs.url = "nixpkgs/nixos-unstable";
  inputs.flake-compat = {
    url = "github:edolstra/flake-compat";
    flake = false;
  };
  outputs =
    { self, nixpkgs, ... }:
    let
      version = builtins.substring 0 8 self.lastModifiedDate;
      supportedSystems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
      nixpkgsFor = forAllSystems (system: import nixpkgs { inherit system; });
    in
    {
      packages = forAllSystems (system: {
        jsim = nixpkgsFor.${system}.stdenvNoCC.mkDerivation {
          pname = "jsim";
          inherit version;
          src = nixpkgs.lib.cleanSourceWith {
            filter = name: type: type != "regular" || !nixpkgs.lib.hasSuffix ".nix" name;
            src = nixpkgs.lib.cleanSource ./.;
          };
          propagatedBuildInputs = [ nixpkgsFor.${system}.babashka ];
          dontConfigure = true;
          dontBuild = true;
          installPhase = ''
            runHook preInstall
            mkdir -p "$out/bin"
            cp jsim.clj "$out/bin/jsim"
            runHook postInstall
          '';
        };
      });
      defaultPackage = forAllSystems (system: self.packages.${system}.jsim);
      devShell = forAllSystems (
        system:
        let
          pkgs = nixpkgsFor.${system};
        in
        pkgs.mkShell { buildInputs = with pkgs; [ babashka ]; }
      );
    };
}
