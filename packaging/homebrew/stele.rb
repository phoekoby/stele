# Homebrew formula template. After a GitHub release, fill in URL + sha256, then
# put this in a tap repo `homebrew-stele`:  brew install <you>/stele/stele
class Stele < Formula
  desc "Product<->code Rosetta stone: a concept graph served to AI coding agents"
  homepage "https://github.com/USER/stele"
  url "https://github.com/USER/stele/releases/download/v0.1.0/stele.jar"
  sha256 "REPLACE_WITH_SHA256_OF_stele.jar"
  license "MIT"

  depends_on "openjdk"

  def install
    libexec.install "stele.jar"
    (bin/"stele").write <<~EOS
      #!/bin/bash
      exec "#{Formula["openjdk"].opt_bin}/java" -jar "#{libexec}/stele.jar" "$@"
    EOS
  end

  test do
    assert_match "stele", shell_output("#{bin}/stele --help 2>&1")
  end
end
