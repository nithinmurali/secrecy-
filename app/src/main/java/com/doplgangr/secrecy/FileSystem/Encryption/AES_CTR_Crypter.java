/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.doplgangr.secrecy.FileSystem.Encryption;

import com.doplgangr.secrecy.Exceptions.SecrecyCipherStreamException;
import com.doplgangr.secrecy.FileSystem.Files.SecrecyHeaders.FileHeader;
import com.doplgangr.secrecy.FileSystem.Files.SecrecyHeaders.VaultHeader;
import com.doplgangr.secrecy.Util;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

class AES_CTR_Crypter implements Crypter {

    private static final String SECRET_KEY_ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final String HEADER_ENCRYPTION_MODE = "AES/GCM/NoPadding";
    private static final String ENCRYPTION_MODE = "AES/CTR/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String VAULT_HEADER_FILENAME = "/.vault";
    private static final String FILE_HEADER_PREFIX = "/.header_";
    private static final int NONCE_LENGTH_BYTE = 16;
    private static final int AES_KEY_SIZE_BIT = 256;
    private static final int AES_KEY_SIZE_BYTE = AES_KEY_SIZE_BIT / 8;
    private static final int PBKDF2_ITERATION_COUNT = 65536;
    private final static int SALT_SIZE_BYTE = 16;
    private static final int VAULT_HEADER_VERSION = 1;
    private static final int FILE_HEADER_VERSION = 1;

    private SecureRandom secureRandom;
    private SecretKeySpec vaultFileEncryptionKey;

    private VaultHeader vaultHeader;
    private String vaultPath;

    public AES_CTR_Crypter(String vaultPath, String passphrase)
            throws InvalidKeyException {
        secureRandom = new SecureRandom();
        this.vaultPath = vaultPath;

        File headerFile = new File(this.vaultPath + VAULT_HEADER_FILENAME);
        if (!headerFile.exists()) {
            try {
                byte[] vaultNonce = new byte[NONCE_LENGTH_BYTE];
                byte[] aesKey = new byte[AES_KEY_SIZE_BYTE];
                byte[] salt = new byte[SALT_SIZE_BYTE];
                secureRandom.nextBytes(vaultNonce);
                secureRandom.nextBytes(aesKey);
                secureRandom.nextBytes(salt);

                SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(SECRET_KEY_ALGORITHM);
                SecretKey keyFromPassphrase = secretKeyFactory.generateSecret(
                        new PBEKeySpec(passphrase.toCharArray(), salt,
                                PBKDF2_ITERATION_COUNT, AES_KEY_SIZE_BIT));

                writeVaultHeader(headerFile, vaultNonce, aesKey, salt, keyFromPassphrase);
            } catch (Exception e) {
                Util.log("Cannot create vault header!");
                e.printStackTrace();
            }
        }

        try {
            FileInputStream headerInputStream = new FileInputStream(headerFile);
            vaultHeader = VaultHeader.parseFrom(headerInputStream);
        } catch (Exception e) {
            Util.log("Cannot read vault header!");
            e.printStackTrace();
        }

        try {
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance(SECRET_KEY_ALGORITHM);
            SecretKey keyFromPassphrase = secretKeyFactory.generateSecret(
                    new PBEKeySpec(passphrase.toCharArray(), vaultHeader.getSalt().toByteArray(),
                            PBKDF2_ITERATION_COUNT, AES_KEY_SIZE_BIT));
            Cipher c = Cipher.getInstance(HEADER_ENCRYPTION_MODE);
            c.init(Cipher.DECRYPT_MODE, keyFromPassphrase, new IvParameterSpec(
                    vaultHeader.getVaultIV().toByteArray()));

            byte[] decryptedKey = c.doFinal(vaultHeader.getEncryptedAesKey().toByteArray());
            vaultFileEncryptionKey = new SecretKeySpec(decryptedKey, 0,
                    AES_KEY_SIZE_BYTE, KEY_ALGORITHM);
        } catch (BadPaddingException e) {
            if (e.getMessage().equals("mac check in GCM failed")) {
                throw new InvalidKeyException("Passphrase is wrong!");
            } else {
                e.printStackTrace();
            }
        } catch (Exception e) {
            Util.log("Cannot decrypt AES key");
            e.printStackTrace();
        }
    }

    private void writeVaultHeader(File headerFile, byte[] vaultNonce, byte[] aesKey, byte[] salt,
                                  SecretKey keyFromPassphrase) throws Exception {
        Cipher c = Cipher.getInstance(HEADER_ENCRYPTION_MODE);
        FileOutputStream headerOutputStream = new FileOutputStream(headerFile);

        c.init(Cipher.ENCRYPT_MODE, keyFromPassphrase, new IvParameterSpec(vaultNonce));
        byte[] encryptedAesKey = c.doFinal(aesKey);

        VaultHeader.Builder vaultHeaderBuilder = VaultHeader.newBuilder();
        vaultHeaderBuilder.setVersion(VAULT_HEADER_VERSION);
        vaultHeaderBuilder.setSalt(ByteString.copyFrom(salt));
        vaultHeaderBuilder.setVaultIV(ByteString.copyFrom(vaultNonce));
        vaultHeaderBuilder.setEncryptedAesKey(ByteString.copyFrom(encryptedAesKey));
        vaultHeaderBuilder.build().writeTo(headerOutputStream);
        headerOutputStream.close();
    }

    @Override
    public CipherOutputStream getCipherOutputStream(File file, String outputFileName)
            throws SecrecyCipherStreamException, FileNotFoundException {
        Cipher c;
        try {
            c = Cipher.getInstance(ENCRYPTION_MODE);
        } catch (NoSuchAlgorithmException e) {
            throw new SecrecyCipherStreamException("Encryption algorithm not found!");
        } catch (NoSuchPaddingException e) {
            throw new SecrecyCipherStreamException("Selected padding not found!");
        }

        File headerFile = new File(vaultPath + FILE_HEADER_PREFIX + outputFileName);
        File outputFile = new File(vaultPath + "/" + outputFileName);

        byte[] fileEncryptionNonce = new byte[NONCE_LENGTH_BYTE];
        byte[] fileNameNonce = new byte[NONCE_LENGTH_BYTE];
        secureRandom.nextBytes(fileEncryptionNonce);
        secureRandom.nextBytes(fileNameNonce);

        try {
            c.init(Cipher.ENCRYPT_MODE, vaultFileEncryptionKey, new IvParameterSpec(fileNameNonce));
        } catch (InvalidKeyException e) {
            throw new SecrecyCipherStreamException("Invalid encryption key!");
        } catch (InvalidAlgorithmParameterException e) {
            throw new SecrecyCipherStreamException("Invalid algorithm parameter!");
        }

        byte[] encryptedFileName;
        try {
            encryptedFileName = c.doFinal(file.getName().getBytes());
        } catch (IllegalBlockSizeException e) {
            throw new SecrecyCipherStreamException("Illegal block size!");
        } catch (BadPaddingException e) {
            throw new SecrecyCipherStreamException("Bad padding");
        }

        FileHeader.Builder fileHeaderBuilder = FileHeader.newBuilder();
        fileHeaderBuilder.setVersion(FILE_HEADER_VERSION);
        fileHeaderBuilder.setFileIV(ByteString.copyFrom(fileEncryptionNonce));
        fileHeaderBuilder.setFileNameIV(ByteString.copyFrom(fileNameNonce));
        fileHeaderBuilder.setEncryptedFileName(ByteString.copyFrom(encryptedFileName));

        FileOutputStream headerOutputStream = new FileOutputStream(headerFile);
        try {
            fileHeaderBuilder.build().writeTo(headerOutputStream);
            headerOutputStream.close();
        } catch (IOException e) {
            throw new SecrecyCipherStreamException("IO exception while writing file header");
        }


        try {
            c.init(Cipher.ENCRYPT_MODE, vaultFileEncryptionKey, new IvParameterSpec(fileEncryptionNonce));
        } catch (InvalidKeyException e) {
            throw new SecrecyCipherStreamException("Invalid encryption key!");
        } catch (InvalidAlgorithmParameterException e) {
            throw new SecrecyCipherStreamException("Invalid algorithm parameter!");
        }
        return new CipherOutputStream(new FileOutputStream(outputFile), c);
    }

    @Override
    public CipherInputStream getCipherInputStream(File encryptedFile)
            throws SecrecyCipherStreamException, FileNotFoundException {
        Cipher c;
        try {
            c = Cipher.getInstance(ENCRYPTION_MODE);
        }  catch (NoSuchAlgorithmException e) {
            throw new SecrecyCipherStreamException("Encryption algorithm not found!");
        } catch (NoSuchPaddingException e) {
            throw new SecrecyCipherStreamException("Selected padding not found!");
        }

        File headerFile = new File(encryptedFile.getParent() +
                FILE_HEADER_PREFIX + encryptedFile.getName());
        if (!headerFile.exists()) {
            throw new FileNotFoundException("Header file not found!");
        }

        FileHeader fileHeader;
        try {
            fileHeader = FileHeader.parseFrom(new FileInputStream(headerFile));
        } catch (IOException e) {
            throw new SecrecyCipherStreamException("Cannot parse file header!");
        }

        try {
            c.init(Cipher.DECRYPT_MODE, vaultFileEncryptionKey,
                    new IvParameterSpec(fileHeader.getFileIV().toByteArray()));
        }  catch (InvalidKeyException e) {
            throw new SecrecyCipherStreamException("Invalid encryption key!");
        } catch (InvalidAlgorithmParameterException e) {
            throw new SecrecyCipherStreamException("Invalid algorithm parameter!");
        }

        return new CipherInputStream(new FileInputStream(encryptedFile), c);
    }

    public String getDecryptedFileName(File file) throws SecrecyCipherStreamException,
            FileNotFoundException {
        Cipher c;
        try {
            c = Cipher.getInstance(ENCRYPTION_MODE);
        } catch (NoSuchAlgorithmException e) {
            throw new SecrecyCipherStreamException("Encryption algorithm not found!");
        } catch (NoSuchPaddingException e) {
            throw new SecrecyCipherStreamException("Selected padding not found!");
        }

        File headerFile = new File(file.getParent() + FILE_HEADER_PREFIX + file.getName());
        if (!headerFile.exists()) {
            throw new FileNotFoundException("Header file not found!");
        }

        FileHeader fileHeader;
        try {
            fileHeader = FileHeader.parseFrom(new FileInputStream(headerFile));
        } catch (IOException e) {
            throw new SecrecyCipherStreamException("Cannot parse file header!");
        }

        try {
            c.init(Cipher.DECRYPT_MODE, vaultFileEncryptionKey,
                    new IvParameterSpec(fileHeader.getFileNameIV().toByteArray()));
        } catch (InvalidKeyException e) {
            throw new SecrecyCipherStreamException("Invalid encryption key!");
        } catch (InvalidAlgorithmParameterException e) {
            throw new SecrecyCipherStreamException("Invalid algorithm parameter!");
        }
        byte[] decryptedFileName;
        try {
            decryptedFileName = c.doFinal(fileHeader.getEncryptedFileName().toByteArray());
        }catch (IllegalBlockSizeException e) {
            throw new SecrecyCipherStreamException("Illegal block size!");
        } catch (BadPaddingException e) {
            throw new SecrecyCipherStreamException("Bad padding");
        }
        return new String(decryptedFileName);
    }
}