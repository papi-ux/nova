package com.papi.nova;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

public class PosterContentProvider extends ContentProvider {


    public static final String AUTHORITY = "poster." + BuildConfig.APPLICATION_ID;
    public static final String PNG_MIME_TYPE = "image/png";
    public static final int APP_ID_PATH_INDEX = 2;
    public static final int COMPUTER_UUID_PATH_INDEX = 1;

    private static final UriMatcher sUriMatcher;
    private static final String BOXART_PATH = "boxart";
    private static final int BOXART_URI_ID = 1;

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY, BOXART_PATH + "/*/*", BOXART_URI_ID);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (sUriMatcher.match(uri) != BOXART_URI_ID) {
            throw new FileNotFoundException();
        }
        return openBoxArtFile(uri, mode);
    }

    public ParcelFileDescriptor openBoxArtFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new UnsupportedOperationException("This provider is only for read mode");
        }

        List<String> segments = uri.getPathSegments();
        if (segments.size() != 3) {
            throw new FileNotFoundException();
        }
        String appId = segments.get(APP_ID_PATH_INDEX);
        String uuid = segments.get(COMPUTER_UUID_PATH_INDEX);
        final int parsedAppId;
        final UUID parsedUuid;
        try {
            parsedUuid = UUID.fromString(uuid);
            parsedAppId = Integer.parseInt(appId);
            if (parsedAppId < 0) {
                throw new NumberFormatException("Negative app ID");
            }
        } catch (NumberFormatException e) {
            throw new FileNotFoundException();
        } catch (IllegalArgumentException e) {
            throw new FileNotFoundException();
        }

        final File file;
        try {
            if (getContext() == null) {
                throw new IOException("Missing provider context");
            }

            File boxArtRoot = new File(getContext().getCacheDir(), BOXART_PATH).getCanonicalFile();
            File uuidDir = new File(boxArtRoot, parsedUuid.toString()).getCanonicalFile();
            file = new File(uuidDir, parsedAppId + ".png").getCanonicalFile();

            String boxArtRootPath = boxArtRoot.getPath();
            String filePath = file.getPath();
            if (!filePath.startsWith(boxArtRootPath + File.separator)) {
                throw new IOException("Box art path escapes cache");
            }
        } catch (IllegalArgumentException | IOException e) {
            throw new FileNotFoundException();
        }

        if (file.isFile()) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("This provider is only for read mode");
    }

    @Override
    public String getType(Uri uri) {
        return PNG_MIME_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("This provider is only for read mode");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("This provider doesn't support query");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("This provider is support read only");
    }


    public static Uri createBoxArtUri(String uuid, String appId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(AUTHORITY)
                .appendPath(BOXART_PATH)
                .appendPath(uuid)
                .appendPath(appId)
                .build();
    }

}
