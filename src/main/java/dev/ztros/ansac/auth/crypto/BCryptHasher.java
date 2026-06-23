package dev.ztros.ansac.auth.crypto;

import org.mindrot.jbcrypt.BCrypt;

public class BCryptHasher {

    public static String hash(String plaintext) {
        return BCrypt.hashpw(plaintext, BCrypt.gensalt());
    }

    public static boolean verify(String plaintext, String hash) {
        try {
            return BCrypt.checkpw(plaintext, hash);
        } catch (Exception e) {
            return false;
        }
    }
}
