/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pandoroid;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.URLUtil;
import android.widget.ImageView;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/**
 * This helper class download images from the Internet and binds those with the provided ImageView.
 *
 * <p>It requires the INTERNET permission, which should be added to your application's manifest
 * file.</p>
 *
 * A local cache of downloaded images is maintained internally to improve performance.
 */
public class ImageDownloader {
    private static final String LOG_TAG = "ImageDownloader";

    private static final int HARD_CACHE_CAPACITY = 5;
    private static final int DELAY_BEFORE_PURGE = 300 * 1000; // in milliseconds

    // Hard cache, with a fixed maximum capacity and a life duration
    @SuppressWarnings("serial")
    private static final HashMap<String, Bitmap> sHardBitmapCache =
        new LinkedHashMap<String, Bitmap>(HARD_CACHE_CAPACITY / 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(LinkedHashMap.Entry<String, Bitmap> eldest) {
            if (size() > HARD_CACHE_CAPACITY) {
                // Entries push-out of hard reference cache are transferred to soft reference cache
                sSoftBitmapCache.put(eldest.getKey(), new SoftReference<>(eldest.getValue()));
                return true;
            } else
                return false;
        }
    };

    // Soft cache for bitmap kicked out of hard cache
    private final static ConcurrentHashMap<String, SoftReference<Bitmap>> sSoftBitmapCache =
            new ConcurrentHashMap<>(HARD_CACHE_CAPACITY / 2);

    private final Handler purgeHandler = new Handler();

    private final Runnable purger = () -> clearCache();

    /**
     * Download the specified image from the Internet and binds it to the provided ImageView. The
     * binding is immediate if the image is found in the cache and will be done asynchronously
     * otherwise. A null bitmap will be associated to the ImageView if an error occurs.
     *
     * @param url The URL of the image to download.
     * @param imageView The ImageView to bind the downloaded image to.
     */
    public void download(String url, ImageView imageView) {
        download(url, imageView, null);
    }

    /**
     * Same as {@link #download(String, ImageView)}, with the possibility to provide an additional
     * cookie that will be used when the image will be retrieved.
     *
     * @param url The URL of the image to download.
     * @param imageView The ImageView to bind the downloaded image to.
     * @param cookie A cookie String that will be used by the http connection.
     */
    public void download(String url, ImageView imageView, String cookie) {
        resetPurgeTimer();
        Bitmap bitmap = getBitmapFromCache(url);

        if (bitmap == null) {
            forceDownload(url, imageView, cookie);
        } else {
            cancelPotentialDownload(url, imageView);
            imageView.setImageBitmap(bitmap);
        }
    }

    /*
     * Same as download but the image is always downloaded and the cache is not used.
     * Kept private at the moment as its interest is not clear.
       private void forceDownload(String url, ImageView view) {
          forceDownload(url, view, null);
       }
     */

    /**
     * Same as download but the image is always downloaded and the cache is not used.
     * Kept private at the moment as its interest is not clear.
     */
    private void forceDownload(String url, ImageView imageView, String cookie) {
        // State sanity: url is guaranteed to never be null in DownloadedDrawable and cache keys.
        if (url == null) {
            imageView.setImageDrawable(null);
            return;
        }

        if (cancelPotentialDownload(url, imageView)) {
            BitmapDownloaderTask task = new BitmapDownloaderTask(imageView);
            DownloadedDrawable downloadedDrawable = new DownloadedDrawable(task);
            imageView.setImageDrawable(downloadedDrawable);
            task.execute(url, cookie);
        }
    }

    /**
     * Clears the image cache used internally to improve performance. Note that for memory
     * efficiency reasons, the cache will automatically be cleared after a certain inactivity delay.
     */
    public void clearCache() {
        sHardBitmapCache.clear();
        sSoftBitmapCache.clear();
    }

    private void resetPurgeTimer() {
        purgeHandler.removeCallbacks(purger);
        purgeHandler.postDelayed(purger, DELAY_BEFORE_PURGE);
    }

    /**
     * Returns true if the current download has been canceled or if there was no download in
     * progress on this image view.
     * Returns false if the download in progress deals with the same url. The download is not
     * stopped in that case.
     */
    private static boolean cancelPotentialDownload(String url, ImageView imageView) {
        BitmapDownloaderTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);

        if (bitmapDownloaderTask != null) {
            String bitmapUrl = bitmapDownloaderTask.url;
            if ((bitmapUrl == null) || (!bitmapUrl.equals(url))) {
                bitmapDownloaderTask.cancel(true);
            } else {
                // The same URL is already being downloaded.
                return false;
            }
        }
        return true;
    }

    /**
     * @param imageView Any imageView
     * @return Retrieve the currently active download task (if any) associated with this imageView.
     * null if there is no such task.
     */
    private static BitmapDownloaderTask getBitmapDownloaderTask(ImageView imageView) {
        if (imageView != null) {
            Drawable drawable = imageView.getDrawable();
            if (drawable instanceof DownloadedDrawable) {
                DownloadedDrawable downloadedDrawable = (DownloadedDrawable)drawable;
                return downloadedDrawable.getBitmapDownloaderTask();
            }
        }
        return null;
    }

    /**
     * @param url The URL of the image that will be retrieved from the cache.
     * @return The cached bitmap or null if it was not found.
     */
    private Bitmap getBitmapFromCache(String url) {
        // First try the hard reference cache
        synchronized (sHardBitmapCache) {
            final Bitmap bitmap = sHardBitmapCache.get(url);
            if (bitmap != null) {
                // Bitmap found in hard cache
                // Move element to first position, so that it is removed last
                sHardBitmapCache.remove(url);
                sHardBitmapCache.put(url, bitmap);
                return bitmap;
            }
        }

        // Then try the soft reference cache
        SoftReference<Bitmap> bitmapReference = sSoftBitmapCache.get(url);
        if (bitmapReference != null) {
            final Bitmap bitmap = bitmapReference.get();
            if (bitmap != null) {
                // Bitmap found in soft cache
                return bitmap;
            } else {
                // Soft reference has been Garbage Collected
                sSoftBitmapCache.remove(url);
            }
        }

        return null;
    }

    static Bitmap getBitmapFromCachee(String url) {

        // Then try the soft reference cache
        SoftReference<Bitmap> bitmapReference = sSoftBitmapCache.get(url);
        if (bitmapReference != null) {
            final Bitmap bitmap = bitmapReference.get();
            if (bitmap != null) {
                // Bitmap found in soft cache
                return bitmap;
            } else {
                // Soft reference has been Garbage Collected
                sSoftBitmapCache.remove(url);
            }
        }

        return null;
    }

    /**
     * The actual asynchronous task that will download the image.
     */
    static class BitmapDownloaderTask {
        private static final Executor THREAD_POOL_EXECUTOR =
                new ThreadPoolExecutor(5, 128, 1,
                        TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
        private String url;
        private final WeakReference<ImageView> imageViewReference;
        private boolean isCancelled = false;

        public BitmapDownloaderTask(ImageView imageView) {
            imageViewReference = new WeakReference<>(imageView);
        }

        public void cancel(boolean mayInterruptIfRunning) {
            isCancelled = true;
        }

        public boolean isCancelled() {
            return isCancelled;
        }

        public void execute(String... params) {
            THREAD_POOL_EXECUTOR.execute(() -> {
                if (isCancelled) return;
                Bitmap bitmap = doInBackground(params);
                if (isCancelled) return;
                MAIN_HANDLER.post(() -> onPostExecute(bitmap));
            });
        }

        protected Bitmap doInBackground(String... params) {
            final OkHttpClient client2 = new OkHttpClient.Builder()
                    .readTimeout(10000, TimeUnit.MILLISECONDS)
                    .writeTimeout(10000, TimeUnit.MILLISECONDS)
                    .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                    .connectTimeout(10000, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
            url = params[0];
            try {
                if (!URLUtil.isValidUrl(url)) {
                    url = "https://" + url;
                }
                url = url.replace("http://","https://");
                Request request = new Request.Builder()
                        .addHeader("Connection","close")
                        .url(url)
                        .addHeader("User-Agent", "pithos")
                        .build();
                try (Response response = client2.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        return BitmapFactory.decodeStream(response.body().byteStream());
                    }
                }
            } catch (Exception e) {
                Log.e("Pandoroid", "Error downloading bitmap in ImageDownloader." + url, e);
            }
            return null;
        }

        protected void onPostExecute(Bitmap bitmap) {
            if (isCancelled) {
                bitmap = null;
            }

            if (bitmap != null) {
                synchronized (sHardBitmapCache) {
                    sHardBitmapCache.put(url, bitmap);
                }
            }

            if (imageViewReference != null) {
                ImageView imageView = imageViewReference.get();
                BitmapDownloaderTask bitmapDownloaderTask = getBitmapDownloaderTask(imageView);
                if (this == bitmapDownloaderTask) {
                    imageView.setImageBitmap(bitmap);
                }
            }
        }
    }

    /**
     * A fake Drawable that will be attached to the imageView while the download is in progress.
     *
     * <p>Contains a reference to the actual download task, so that a download task can be stopped
     * if a new binding is required, and makes sure that only the last started download process can
     * bind its result, independently of the download finish order.</p>
     */
    static class DownloadedDrawable extends ColorDrawable {
        private final WeakReference<BitmapDownloaderTask> bitmapDownloaderTaskReference;

        public DownloadedDrawable(BitmapDownloaderTask bitmapDownloaderTask) {
            super(Color.BLACK);
            bitmapDownloaderTaskReference =
                    new WeakReference<>(bitmapDownloaderTask);
        }

        public BitmapDownloaderTask getBitmapDownloaderTask() {
            return bitmapDownloaderTaskReference.get();
        }
    }

    public Bitmap getBitmap(String url) {
        if (url == null) return null;
        synchronized (sHardBitmapCache) {
            Bitmap cached = sHardBitmapCache.get(url);
            if (cached != null) return cached;
        }
        SoftReference<Bitmap> softRef = sSoftBitmapCache.get(url);
        if (softRef != null) {
            Bitmap cached = softRef.get();
            if (cached != null) return cached;
        }
        return null;
    }

    public void downloadBitmapAsync(String url, final OnBitmapDownloadedListener listener) {
        if (url == null) {
            if (listener != null) listener.onBitmapDownloaded(null);
            return;
        }
        Bitmap cached = getBitmap(url);
        if (cached != null) {
            if (listener != null) listener.onBitmapDownloaded(cached);
            return;
        }
        BitmapDownloaderTask.THREAD_POOL_EXECUTOR.execute(() -> {
            Bitmap bitmap = null;
            String originalUrl = url;
            String targetUrl = originalUrl;
            try {
                System.setProperty("java.net.preferIPv4Stack", "true");
                if (!URLUtil.isValidUrl(targetUrl)) {
                    targetUrl = "https://" + targetUrl;
                }
                targetUrl = targetUrl.replace("http://", "https://");

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(true)
                        .build();
                Request request = new Request.Builder()
                        .addHeader("User-Agent", "pithos")
                        .url(targetUrl).build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        bitmap = BitmapFactory.decodeStream(response.body().byteStream());
                        if (bitmap != null) {
                            synchronized (sHardBitmapCache) {
                                sHardBitmapCache.put(targetUrl, bitmap);
                                sHardBitmapCache.put(originalUrl, bitmap);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error downloading bitmap (HTTPS): " + targetUrl, e);
                // Fallback to original URL (e.g. HTTP)
                try {
                    OkHttpClient client = new OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(5, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                    Request request = new Request.Builder()
                            .addHeader("User-Agent", "pithos")
                            .url(originalUrl).build();

                    try (Response response = client.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            bitmap = BitmapFactory.decodeStream(response.body().byteStream());
                            if (bitmap != null) {
                                synchronized (sHardBitmapCache) {
                                    sHardBitmapCache.put(originalUrl, bitmap);
                                }
                            }
                        }
                    }
                } catch (Exception e2) {
                    Log.e(LOG_TAG, "Error downloading bitmap fallback (HTTP): " + originalUrl, e2);
                }
            }
            final Bitmap finalBitmap = bitmap;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (listener != null) {
                    listener.onBitmapDownloaded(finalBitmap);
                }
            });
        });
    }

    public interface OnBitmapDownloadedListener {
        void onBitmapDownloaded(Bitmap bitmap);
    }
}
