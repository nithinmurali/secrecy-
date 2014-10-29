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

import android.content.Context;
import android.net.Uri;

import com.doplgangr.secrecy.Exceptions.SecrecyFileException;
import com.doplgangr.secrecy.FileSystem.Files.EncryptedFile;
import com.doplgangr.secrecy.FileSystem.Files.EncryptedFileFactory;
import com.doplgangr.secrecy.FileSystem.Storage;
import com.doplgangr.secrecy.Listeners;
import com.doplgangr.secrecy.Util;
import com.ipaulpro.afilechooser.utils.FileUtils;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.security.InvalidKeyException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;


public class Vault implements Serializable {
    private final String name;
    private final String path;
    private String passphrase;
    private Crypter crypter;

    public Boolean wrongPass = false;


    public Vault(String name, String passphrase) {
        this.name = name;
        this.passphrase = passphrase;
        path = Storage.getRoot().getAbsolutePath() + "/" + name;
        try {
            crypter = new AES_CTR_Crypter(path, passphrase);
        } catch (InvalidKeyException e) {
            Util.log("Passphrase is wrong");
            wrongPass = true;
        }
    }

    public Vault(String name, String passphrase, Boolean istemp) {
        this.passphrase = passphrase;
        this.name = name;
        path = Storage.getRoot().getAbsolutePath() + "/" + name;
        try {
            crypter = new AES_CTR_Crypter(path, passphrase);
        } catch (InvalidKeyException e) {
            Util.log("Passphrase is wrong");
            wrongPass = true;
        }
        //do not initialize now coz this is temp
    }

    public String getPath() {
        return path;
    }

    private static boolean fileFilter(java.io.File file) {
        String regex = "^((?!.thumb|.nomedia|.vault|.header).)*$";   //Filter out .nomedia, .thumb and .header
        String name = file.getName();
        final Pattern p = Pattern.compile(regex);
        p.matcher(name).matches();
        return p.matcher(name).matches();
    }

    public String getName() {
        return name;
    }

    public void iterateAllFiles(onFileFoundListener listener) {
        List<File> files = getFileList();
        for (File file : files) {
            try {
                listener.dothis(EncryptedFileFactory.getInstance().loadEncryptedFile(file,
                        crypter));
            } catch (FileNotFoundException e) {
                //Ignore
            }
        }
    }

    public int getFileCount() {
        return getFileList().size();
    }

    public EncryptedFile addFile(final Context context, final Uri uri) throws SecrecyFileException{
        Util.log("Vault: adding file ", uri);
        return EncryptedFileFactory.getInstance().createNewEncryptedFile(
                (new File(FileUtils.getPath(context, uri))), crypter, this);
    }

    public EncryptedFile getFileInstance(String name) {
        File requestedFile = new File(path, name);
        EncryptedFile encryptedFile = null;
        try {
           encryptedFile = EncryptedFileFactory.getInstance().loadEncryptedFile(requestedFile,
                   crypter);
        } catch (FileNotFoundException e){

        }
        return encryptedFile;
    }

    public Boolean delete() {
        if (!wrongPass)
            try {
                org.apache.commons.io.FileUtils.deleteDirectory(new File(path));
            } catch (IOException e) {
                e.printStackTrace();
            }
        return !wrongPass;
    }

    private List<File> getFileList() {
        File folder = new File(path);
        return Arrays.asList(
                folder.listFiles(
                        new FileFilter() {
                            @Override
                            public boolean accept(java.io.File file) {
                                return fileFilter(file);
                            }
                        }
                )
        );
    }

    public Vault rename(String name) {
        if (wrongPass)
            return null; //bye
        File folder = new File(path);
        File newFolder = new File(folder.getParent(), name);
        if (folder.getAbsolutePath().equals(newFolder.getAbsolutePath()))
            return this; //same name, bye
        try {
            org.apache.commons.io.FileUtils.copyDirectory(folder, newFolder);
        } catch (IOException e) {
            // New Folder should be cleared. Only preserver old folder
            try {
                org.apache.commons.io.FileUtils.deleteDirectory(newFolder);
            } catch (IOException ignored) {
                //ignore
            }
            return null;
        }
        try {
            org.apache.commons.io.FileUtils.deleteDirectory(folder);
        } catch (IOException ignored) {
            //ignored
        }
        return new Vault(name, passphrase);
    }

    public void startWatching(final Listeners.FileObserverEventListener mListener) {
        final android.os.FileObserver observer = new android.os.FileObserver(path) { // set up a file observer to watch this directory on sd card
            @Override
            public void onEvent(int event, String filename) {
                if (filename != null) {
                    File file = new File(path, filename);
                    if (fileFilter(file)) {
                        if (event == android.os.FileObserver.CREATE || event == android.os.FileObserver.MOVED_TO)
                            mListener.add(file);
                        if (event == android.os.FileObserver.DELETE || event == android.os.FileObserver.MOVED_FROM)
                            mListener.remove(file);
                    }
                }
            }
        };
        observer.startWatching(); //START OBSERVING
    }

    public interface onFileFoundListener {
        void dothis(EncryptedFile encryptedFile);
    }

}
