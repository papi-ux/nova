package com.papi.nova.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;

public class CacheHelper {
    private static boolean isSafePathComponent(String component) {
        return component != null &&
                !component.isEmpty() &&
                !component.equals(".") &&
                !component.contains("..") &&
                component.indexOf('/') == -1 &&
                component.indexOf('\\') == -1;
    }

    private static boolean isUnderRoot(File root, File file) {
        String rootPath = root.getPath();
        String filePath = file.getPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    public static File openPath(boolean createPath, File root, String... path) {
        if (root == null) {
            throw new IllegalArgumentException("Root cannot be null");
        }

        File canonicalRoot;
        try {
            canonicalRoot = root.getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to resolve cache root", e);
        }

        File f = root;
        for (int i = 0; i < path.length; i++) {
            String component = path[i];
            if (!isSafePathComponent(component)) {
                throw new IllegalArgumentException("Invalid cache path component");
            }

            if (i == path.length - 1) {
                // This is the file component so now we create parent directories
                if (createPath) {
                    f.mkdirs();
                }
            }

            f = new File(f, component);
        }

        try {
            File canonicalFile = f.getCanonicalFile();
            if (!isUnderRoot(canonicalRoot, canonicalFile)) {
                throw new IllegalArgumentException("Cache path escapes root");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to resolve cache path", e);
        }

        return f;
    }

    public static long getFileSize(File root, String... path) {
        return openPath(false, root, path).length();
    }

    public static boolean deleteCacheFile(File root, String... path) {
        return openPath(false, root, path).delete();
    }

    public static boolean cacheFileExists(File root, String... path) {
        return openPath(false, root, path).exists();
    }

    public static InputStream openCacheFileForInput(File root, String... path) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File f = canonicalRoot;
        for (String component : path) {
            if (!isSafePathComponent(component)) {
                throw new FileNotFoundException("Invalid cache path component");
            }
            f = new File(f, component);
        }

        File canonicalFile = f.getCanonicalFile();
        if (!isUnderRoot(canonicalRoot, canonicalFile)) {
            throw new FileNotFoundException("Cache path escapes root");
        }

        return new BufferedInputStream(new FileInputStream(canonicalFile));
    }

    public static OutputStream openCacheFileForOutput(File root, String... path) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File f = canonicalRoot;
        for (String component : path) {
            if (!isSafePathComponent(component)) {
                throw new FileNotFoundException("Invalid cache path component");
            }
            f = new File(f, component);
        }

        File canonicalFile = f.getCanonicalFile();
        if (!isUnderRoot(canonicalRoot, canonicalFile)) {
            throw new FileNotFoundException("Cache path escapes root");
        }

        File parent = canonicalFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new FileNotFoundException("Unable to create cache parent path");
        }

        return new BufferedOutputStream(new FileOutputStream(canonicalFile));
    }

    public static void writeInputStreamToOutputStream(InputStream in, OutputStream out, long maxLength) throws IOException {
        byte[] buf = new byte[4096];
        int bytesRead;

        while ((bytesRead = in.read(buf)) != -1) {
            maxLength -= bytesRead;
            if (maxLength <= 0) {
                throw new IOException("Stream exceeded max size");
            }
            out.write(buf, 0, bytesRead);
        }
    }

    public static String readInputStreamToString(InputStream in) throws IOException {
        Reader r = new InputStreamReader(in);

        StringBuilder sb = new StringBuilder();
        char[] buf = new char[256];
        int bytesRead;
        while ((bytesRead = r.read(buf)) != -1) {
            sb.append(buf, 0, bytesRead);
        }

        try {
            in.close();
        } catch (IOException ignored) {}

        return sb.toString();
    }

    public static void writeStringToOutputStream(OutputStream out, String str) throws IOException {
        out.write(str.getBytes("UTF-8"));
    }
}
