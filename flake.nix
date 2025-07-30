{
  description = "ndc-jdbc development dependencies";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    kotlin-version.url = "github:nixos/nixpkgs?ref=4c18de526b8b1c1d7411e6d6e14dd0a65f8ff348";
  };

  outputs = { self, nixpkgs, flake-utils, kotlin-version }:
    flake-utils.lib.eachDefaultSystem (localSystem:
      let
        pkgs = import nixpkgs
          {
            system = localSystem;
            overlays = [ ];
          };

        kotlinPkgs = import kotlin-version
          {
            system = localSystem;
          };

      in

      {
        packages.${localSystem}.default = self.packages.${localSystem}.openjdk;

        devShells = {
          default = pkgs.mkShell {
            nativeBuildInputs = [
              # Development
              pkgs.openjdk
              kotlinPkgs.kotlin
            ];
          };
        };
      }
    );
}
