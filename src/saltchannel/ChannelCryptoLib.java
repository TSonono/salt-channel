package saltchannel;

import java.security.SecureRandom;
import java.util.Random;
import saltchannel.util.Hex;
import saltchannel.util.KeyPair;

/**
 * Salt Channel crypto lib, a thin layer on top of TweetNaCl.
 * The random byte generator can be injected. 
 * Provides some channel-related utility features.
 * The random generator must be replaceable; needed for faster tests
 * and for Android use.
 * 
 * @author Frans Lundberg
 */
public class ChannelCryptoLib {
    public static final int SIGN_PUBLIC_KEY_BYTES = TweetNaCl.SIGN_PUBLIC_KEY_BYTES;
    private Rand rand;
    
    private ChannelCryptoLib(Rand rand) {
        this.rand = rand;
    }

    /**
     * Creates an instance of the crypto lib with a 
     * secure random source.
     */
    public static ChannelCryptoLib createSecure() {
        return new ChannelCryptoLib(createSecureRand());
    }
    
    /**
     * Creates a crypto lib instance with an insecure random number generator; so
     * do not use for other things than testing.
     */
    public static ChannelCryptoLib createInsecureAndFast() {
        final Random random = new Random();
        
        Rand rand = new Rand() {
            public void randomBytes(byte[] b) {
                random.nextBytes(b);
            }
        };
        
        return new ChannelCryptoLib(rand);
    }

    /**
     * Creates a crypto lib instance from the given source of randomness.
     */
    public static ChannelCryptoLib create(Rand rand) {
        return new ChannelCryptoLib(rand);
    }
    
    public KeyPair createEncKeys() {
        byte[] sec = new byte[TweetNaCl.BOX_SECRET_KEY_BYTES];
        byte[] pub = new byte[TweetNaCl.BOX_PUBLIC_KEY_BYTES];
        TweetNaCl.crypto_box_keypair_frans(pub, sec, rand);
        return new KeyPair(sec, pub);
    }
    
    public KeyPair createSigKeys() {
        byte[] sec = new byte[TweetNaCl.SIGN_SECRET_KEY_BYTES];
        byte[] pub = new byte[TweetNaCl.SIGN_PUBLIC_KEY_BYTES];
        TweetNaCl.crypto_sign_keypair_frans(pub, sec, rand);
        return new KeyPair(sec, pub);
    }

    public byte[] computeSharedKey(byte[] myPriv, byte[] peerPub) {
        if (myPriv.length != TweetNaCl.BOX_SECRET_KEY_BYTES) {
            throw new IllegalArgumentException("bad length of myPriv, " + myPriv.length);
        }
        
        if (peerPub.length != TweetNaCl.BOX_PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("bad length of peerPub, " + peerPub.length);
        }
        
        byte[] sharedKey = new byte[TweetNaCl.BOX_SHARED_KEY_BYTES];
        TweetNaCl.crypto_box_beforenm(sharedKey, peerPub, myPriv);
        return sharedKey;
    }
    
    public byte[] createSaltChannelSignature(KeyPair sigKeyPair, byte[] myEk, byte[] peerEk) {
        byte[] secretSigningKey = sigKeyPair.sec();
        
        if (secretSigningKey.length != TweetNaCl.SIGN_SECRET_KEY_BYTES) {
            throw new IllegalArgumentException("bad signing key length, " + secretSigningKey.length);
        }
        
        byte[] messageToSign = new byte[2 * TweetNaCl.BOX_PUBLIC_KEY_BYTES];
        System.arraycopy(myEk, 0, messageToSign, 0, myEk.length);
        System.arraycopy(peerEk, 0, messageToSign, myEk.length, peerEk.length);
        
        byte[] signedMessage = TweetNaCl.crypto_sign(messageToSign, secretSigningKey);
        byte[] mySignature = new byte[TweetNaCl.SIGNATURE_SIZE_BYTES];
        System.arraycopy(signedMessage, 0, mySignature, 0, mySignature.length);
        
        return mySignature;
    }

    /**
     * Checks a signature. peerEk and myEk concatenated is the message that was signed.
     * 
     * @throws ComException if signature not valid.
     */
    public void checkSaltChannelSignature(byte[] peerSigPubKey, byte[] myEk,
            byte[] peerEk, byte[] signature) {
        // To use NaCl's crypto_sign_open, we create 
        // a signed message: signature+message concatenated.
        
        byte[] signedMessage = new byte[TweetNaCl.SIGNATURE_SIZE_BYTES + 2 * TweetNaCl.BOX_PUBLIC_KEY_BYTES];
        int offset = 0;
        System.arraycopy(signature, 0, signedMessage, offset, signature.length);
        offset += signature.length;
        System.arraycopy(peerEk, 0, signedMessage, offset, peerEk.length);
        offset += peerEk.length;
        System.arraycopy(myEk, 0, signedMessage, offset, myEk.length);
        offset += myEk.length;
        
        if (offset != signedMessage.length) {
            throw new Error("bug, " + offset + ", " + signedMessage.length);
        }
        
        try {
            TweetNaCl.crypto_sign_open(signedMessage, peerSigPubKey);
        } catch (TweetNaCl.InvalidSignatureException e) {
            throw new InvalidSignature("invalid peer signature while doing handshake, "
                    + "peer's pub sig key=" + Hex.create(peerSigPubKey) + ", sm=" + Hex.create(signedMessage));
        }
    }
    
    /**
     * Interface for random number source.
     */
    public static interface Rand {
        /**
         * Sets the bytes in the array to random bytes.
         */
        public void randomBytes(byte[] b);
    }
    
    public static class InvalidSignature extends ComException {
        private static final long serialVersionUID = 1L;

        public InvalidSignature(String message) {
            super(message);
        }
    };
    
    private static Rand createSecureRand() {
        // Note, for Java 1.7 (currently supported by this code), "new SecureRandom()"
        // is good. Once 1.8 is allowed, use code like: "SecureRandom.getInstanceStrong()"
        // instead.
        
        final Random random = new SecureRandom();
        
        Rand rand = new Rand() {
            @Override
            public void randomBytes(byte[] b) {
                random.nextBytes(b);
            }
        };
        
        return rand;
    }
}