package com.q42.qlassified.Provider;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.q42.qlassified.Entry.EncryptedEntry;
import com.q42.qlassified.Entry.QlassifiedBoolean;
import com.q42.qlassified.Entry.QlassifiedEntry;
import com.q42.qlassified.Entry.QlassifiedFloat;
import com.q42.qlassified.Entry.QlassifiedInteger;
import com.q42.qlassified.Entry.QlassifiedLong;
import com.q42.qlassified.Entry.QlassifiedString;
import com.q42.qlassified.Logger;

import java.io.IOException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Calendar;
import java.util.GregorianCalendar;

import javax.security.auth.x500.X500Principal;

@TargetApi(18)
public class QlassifiedKeyStore implements QlassifiedSecurity {

    public static final String ANDROID_KEYSTORE_INSTANCE = "AndroidKeyStore";
    public static final String TYPE_DELIMITER = "|";

    private final KeyStore keyStoreInstance;
    private final QlassifiedCrypto crypto;
    private final Context context;

    public QlassifiedKeyStore(Context context) throws
            KeyStoreException,
            CertificateException,
            NoSuchAlgorithmException,
            IOException {

        keyStoreInstance = java.security.KeyStore.getInstance(ANDROID_KEYSTORE_INSTANCE);
        // Weird artifact of Java API.  If you don't have an InputStream to load, you still need
        // to call "load", or it'll crash.
        keyStoreInstance.load(null);

        // Hold on to the context, we need it to fetch the key
        this.context = context;
        // Create the crypto instance
        crypto = new QlassifiedCrypto();
    }

    /**
     * Creates a public and private key and stores it using the Android Key Store, so that only
     * this application will be able to access the keys.
     */
    private void createKeys() throws
            NoSuchProviderException,
            NoSuchAlgorithmException,
            InvalidAlgorithmParameterException,
            InvalidKeyException {

        String alias = getUniqueDeviceId(this.context);
        KeyPairGenerator keyPairGenerator;

        /**
         * On Android Marshmellow we can use new security features
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            keyPairGenerator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE_INSTANCE);

            keyPairGenerator.initialize(
                    new KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                            .setAlgorithmParameterSpec(
                                    new RSAKeyGenParameterSpec(512, RSAKeyGenParameterSpec.F4))
                            .build());
            /**
             * On versions below Marshmellow but above Jelly Bean, use the next best thing
             */
        } else {

            Calendar start = new GregorianCalendar();
            Calendar end = new GregorianCalendar();
            end.add(Calendar.ERA, 1);

            KeyPairGeneratorSpec keyPairGeneratorSpec =
                    new KeyPairGeneratorSpec.Builder(context)
                            // You'll use the alias later to retrieve the key.  It's a key for
                            // the key!
                            .setAlias(alias)
                            // The subject used for the self-signed certificate of the generated
                            // pair
                            .setSubject(new X500Principal("CN=" + alias))
                            // The serial number used for the self-signed certificate of the
                            // generated pair.
                            .setSerialNumber(BigInteger.valueOf(1337))
                            .setStartDate(start.getTime())
                            .setEndDate(end.getTime())
                            .build();

            keyPairGenerator = KeyPairGenerator
                    .getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE_INSTANCE);
            keyPairGenerator.initialize(keyPairGeneratorSpec);
            /**
             * On versions below that...
             * Well we're sorry but you don't get a fancy encryption baby...
             */
        }

        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        Logger.d("KeyStore", String.format("Public key: %s", keyPair.getPublic()));
        Logger.d("KeyStore", String.format("Private key: %s", keyPair.getPrivate()));
    }

    private String getUniqueDeviceId(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    public EncryptedEntry encryptEntry(QlassifiedEntry classifiedEntry) {
        return new EncryptedEntry(classifiedEntry.getKey(), encrypt(String.format(
                String.format("%s%s%s", "%s", TYPE_DELIMITER, "%s"),
                classifiedEntry.getValue(),
                classifiedEntry.getType().name())));
    }

    public QlassifiedEntry decryptEntry(EncryptedEntry entry) {
        final String decryptedString = decrypt(entry.getEncryptedValue());
        if (decryptedString == null) {
            return null;
        }
        final Integer splitPosition = decryptedString.lastIndexOf(TYPE_DELIMITER);
        if (splitPosition == -1) {
            return null;
        }
        final String decryptedType = decryptedString.substring(splitPosition + 1);
        final String decryptedValue = decryptedString.substring(0, splitPosition);

        final String key = entry.getKey();

        switch (QlassifiedEntry.Type.valueOf(decryptedType)) {
            case BOOLEAN:
                return new QlassifiedBoolean(key, Boolean.valueOf(decryptedValue));
            case FLOAT:
                return new QlassifiedFloat(key, Float.valueOf(decryptedValue));
            case INTEGER:
                return new QlassifiedInteger(key, Integer.valueOf(decryptedValue));
            case LONG:
                return new QlassifiedLong(key, Long.valueOf(decryptedValue));
            default:
                return new QlassifiedString(key, decryptedValue);
        }
    }

    private boolean checkKeyAvailability() {

        String alias = getUniqueDeviceId(this.context);
        // Create keys based on the unique device identifier
        try {
            if (!keyStoreInstance.containsAlias(alias)) {
                createKeys();
            }
            return true;
        } catch (KeyStoreException |
                NoSuchProviderException |
                NoSuchAlgorithmException |
                InvalidKeyException |
                InvalidAlgorithmParameterException e) {
            Logger.e("QlassifiedKeyStore",
                    String.format("Could not create a KeyStore instance. Stacktrace: %s", e));
            return false;
        }
    }

    private String encrypt(String input) {

        if (!checkKeyAvailability()) {
            return null;
        }

        String alias = getUniqueDeviceId(this.context);
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            final PrivateKey privateKeyEntry = (PrivateKey) keyStore.getKey(alias, null);
            final RSAPublicKey publicKey = (RSAPublicKey)keyStore.getCertificate(alias).getPublicKey();
            return crypto.encrypt(input, publicKey);
        } catch (NoSuchAlgorithmException |
                UnrecoverableEntryException |
                KeyStoreException e) {
            Logger.e("QlassifiedKeyStore",
                    String.format("Could not encrypt this string. Stacktrace: %s", e));
            return null;
        } catch (CertificateException e) {
            Logger.e("QlassifiedKeyStore",
                    String.format("Could not encrypt this string. Stacktrace: %s", e));
            return null;
        } catch (IOException e) {
            Logger.e("QlassifiedKeyStore",
                    String.format("Could not encrypt this string. Stacktrace: %s", e));
            return null;
        }
    }

    private String decrypt(String input) {

        if (!checkKeyAvailability()) {
            return null;
        }

        String alias = getUniqueDeviceId(this.context);
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, null);
            return crypto.decrypt(input, privateKey);
        } catch (NoSuchAlgorithmException |
                UnrecoverableEntryException |
                KeyStoreException e) {
            Logger.e("QlassifiedKeyStore",
                    String.format("Could not decrypt this string. Stacktrace: %s", e));
            return null;
        } catch (CertificateException e) {
            Logger.e("QlassifiedKeyStore",
                    String.format("Could not decrypt this string. Stacktrace: %s", e));
            return null;
        } catch (IOException e) {
            Logger.e("QlassifiedKeyStore",
                    String.format("Could not decrypt this string. Stacktrace: %s", e));
            return null;
        }
    }
}
