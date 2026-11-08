package io.github.iskortsevsv.video.service;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

@Service
public class HashService {
    public String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
