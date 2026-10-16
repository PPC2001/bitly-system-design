package com.bitly.clone.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class Base62EncoderService {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = ALPHABET.length();

    public String encode(long number) {
        if (number == 0) return String.valueOf(ALPHABET.charAt(0));

        StringBuilder sb = new StringBuilder();
        long num = number;
        while (num > 0) {
            int remainder = (int) (num % BASE);
            sb.append(ALPHABET.charAt(remainder));
            num /= BASE;
        }
        String encoded = sb.reverse().toString();
        log.debug("Encoded {} -> {}", number, encoded);
        return encoded;
    }

    public long decode(String encoded) {
        long result = 0;
        for (char c : encoded.toCharArray()) {
            result = result * BASE + ALPHABET.indexOf(c);
        }
        log.debug("Decoded {} -> {}", encoded, result);
        return result;
    }
}
