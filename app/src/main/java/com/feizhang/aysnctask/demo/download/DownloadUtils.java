package com.feizhang.aysnctask.demo.download;

import android.support.annotation.NonNull;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DownloadUtils {

    /**
     * Download file without specified file name.
     */
    public static File download(@NonNull String downloadUrl, @NonNull String storePath, OnProgressListener progressListener) throws IOException {
        return download(downloadUrl, storePath, null, progressListener);
    }

    /**
     * Download file with specified file name.
     */
    public static File download(@NonNull String downloadUrl, @NonNull String storePath, String fileName, OnProgressListener progressListener) throws IOException {
        if (TextUtils.isEmpty(downloadUrl)) {
            throw new RuntimeException("fileURL cannot be empty or null.");
        }

        if (TextUtils.isEmpty(storePath)) {
            throw new RuntimeException("outputFile cannot be null.");
        }

        OkHttpClient.Builder clientBuilder = HttpClientBuilder.HTTP_CLIENT.newBuilder();
        Request request = new Request.Builder().url(downloadUrl).build();
        Response response = clientBuilder.build().newCall(request).execute();
        int httpCode = response.code();
        if (httpCode != 200){
            throw new IOException("http code: " + httpCode);
        }

        // prepare store file
        if (TextUtils.isEmpty(fileName)) {
            fileName = IOUtils.readFileNameFromHeaders(response.headers());

            if (TextUtils.isEmpty(fileName)) {
                fileName = FileNameUtils.getName(downloadUrl);
            }
        }
        File storeFile = new File(storePath, fileName);

        // create parent directory first
        if (!storeFile.getParentFile().exists()) {
            boolean created = storeFile.getParentFile().mkdirs();
            if (!created) {
                throw new IOException("Cannot make directory: " + storeFile.getParentFile().getPath());
            }
        }

        ResponseBody body = response.body();
        if (body == null){
            throw new IOException("Download failed due to no body");
        }

        doDownload(body, storeFile, progressListener);
        return storeFile;
    }

    private static void doDownload(ResponseBody responseBody, File storeFile, OnProgressListener progressListener) throws IOException {
        ProgressAwareInputStream inputStream = null;
        FileOutputStream outputStream = null;
        try {
            long contentLength = responseBody.contentLength();
            inputStream = new ProgressAwareInputStream(responseBody.byteStream(), contentLength, 0L);
            inputStream.setOnProgressListener(progressListener);

            // opens an output stream to store file
            outputStream = new FileOutputStream(storeFile);
            IOUtils.copy(inputStream, outputStream);
        } finally {
            IOUtils.closeQuietly(inputStream);
            IOUtils.closeQuietly(outputStream);
            responseBody.close();
        }
    }
}
