package org.schabi.newpipe;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.multidex.MultiDexApplication;
import androidx.preference.PreferenceManager;

import org.acra.ACRA;
import org.acra.config.ACRAConfigurationException;
import org.acra.config.CoreConfiguration;
import org.acra.config.CoreConfigurationBuilder;
import org.schabi.newpipe.error.ErrorActivity;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.ReCaptchaActivity;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.ktx.ExceptionUtils;
import org.schabi.newpipe.settings.NewPipeSettings;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.PicassoHelper;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.StateSaver;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.exceptions.CompositeException;
import io.reactivex.rxjava3.exceptions.MissingBackpressureException;
import io.reactivex.rxjava3.exceptions.OnErrorNotImplementedException;
import io.reactivex.rxjava3.exceptions.UndeliverableException;
import io.reactivex.rxjava3.functions.Consumer;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;

/*
 * Copyright (C) Hans-Christoph Steiner 2016 <hans@eds.org>
 * App.java is part of NewPipe.
 *
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

public class App extends MultiDexApplication {
    public static final String PACKAGE_NAME = BuildConfig.APPLICATION_ID;
    private static final String TAG = App.class.toString();
    private static App app;

    @Nullable
    private Disposable disposable = null;

    @NonNull
    public static App getApp() {
        return app;
    }

    @Override
    protected void attachBaseContext(final Context base) {
        super.attachBaseContext(base);
        initACRA();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        app = this;

        // Initialize settings first because others inits can use its values
        NewPipeSettings.initSettings(this);

        NewPipe.init(getDownloader(),
            Localization.getPreferredLocalization(this),
            Localization.getPreferredContentCountry(this));
        Localization.initPrettyTime(Localization.resolvePrettyTime(getApplicationContext()));

        StateSaver.init(this);
        initNotificationChannels();

        ServiceHelper.initServices(this);

        // Initialize image loader
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        PicassoHelper.init(this);
        PicassoHelper.setShouldLoadImages(
                prefs.getBoolean(getString(R.string.download_thumbnail_key), true));
        PicassoHelper.setIndicatorsEnabled(MainActivity.DEBUG
                && prefs.getBoolean(getString(R.string.show_image_indicators_key), false));

        configureRxJavaErrorHandler();
        // Check for new version
        disposable = CheckForNewAppVersion.checkNewVersion(this);
    }

    @Override
    public void onTerminate() {
        if (disposable != null) {
            disposable.dispose();
        }
        super.onTerminate();
        PicassoHelper.terminate();
    }

    protected Downloader getDownloader() {
        final DownloaderImpl downloader = DownloaderImpl.init(null);
        setCookiesToDownloader(downloader);
        return downloader;
    }

    protected void setCookiesToDownloader(final DownloaderImpl downloader) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                getApplicationContext());
        final String key = getApplicationContext().getString(R.string.recaptcha_cookies_key);
        downloader.setCookie(ReCaptchaActivity.RECAPTCHA_COOKIES_KEY, prefs.getString(key, null));
        downloader.updateYoutubeRestrictedModeCookies(getApplicationContext());
    }

    private void configureRxJavaErrorHandler() {
        // https://github.com/ReactiveX/RxJava/wiki/What's-different-in-2.0#error-handling
        RxJavaPlugins.setErrorHandler(new Consumer<Throwable>() {
            @Override
            public void accept(@NonNull final Throwable throwable) {
                Log.e(TAG, "RxJavaPlugins.ErrorHandler called with -> : "
                        + "throwable = [" + throwable.getClass().getName() + "]");

                final Throwable actualThrowable;
                if (throwable instanceof UndeliverableException) {
                    // As UndeliverableException is a wrapper,
                    // get the cause of it to get the "real" exception
                    actualThrowable = throwable.getCause();
                } else {
                    actualThrowable = throwable;
                }

                final List<Throwable> errors;
                if (actualThrowable instanceof CompositeException) {
                    errors = ((CompositeException) actualThrowable).getExceptions();
                } else {
                    errors = Collections.singletonList(actualThrowable);
                }

                for (final Throwable error : errors) {
                    if (isThrowableIgnored(error)) {
                        return;
                    }
                    if (isThrowableCritical(error)) {
                        reportException(error);
                        return;
                    }
                }

                // Out-of-lifecycle exceptions should only be reported if a debug user wishes so,
                // When exception is not reported, log it
                if (isDisposedRxExceptionsReported()) {
                    reportException(actualThrowable);
                } else {
                    Log.e(TAG, "RxJavaPlugin: Undeliverable Exception received: ", actualThrowable);
                }
            }

            private boolean isThrowableIgnored(@NonNull final Throwable throwable) {
                // Don't crash the application over a simple network problem
                return ExceptionUtils.hasAssignableCause(throwable,
                        // network api cancellation
                        IOException.class, SocketException.class,
                        // blocking code disposed
                        InterruptedException.class, InterruptedIOException.class);
            }

            private boolean isThrowableCritical(@NonNull final Throwable throwable) {
                // Though these exceptions cannot be ignored
                return ExceptionUtils.hasAssignableCause(throwable,
                        NullPointerException.class, IllegalArgumentException.class, // bug in app
                        OnErrorNotImplementedException.class, MissingBackpressureException.class,
                        IllegalStateException.class); // bug in operator
            }

            private void reportException(@NonNull final Throwable throwable) {
                // Throw uncaught exception that will trigger the report system
                Thread.currentThread().getUncaughtExceptionHandler()
                        .uncaughtException(Thread.currentThread(), throwable);
            }
        });
    }

    /**
     * Called in {@link #attachBaseContext(Context)} after calling the {@code super} method.
     * Should be overridden if MultiDex is enabled, since it has to be initialized before ACRA.
     */
    protected void initACRA() {
        if (ACRA.isACRASenderServiceProcess()) {
            return;
        }

        try {
            final CoreConfiguration acraConfig = new CoreConfigurationBuilder(this)
                    .setBuildConfigClass(BuildConfig.class)
                    .build();
            ACRA.init(this, acraConfig);
        } catch (final ACRAConfigurationException exception) {
            exception.printStackTrace();
            ErrorActivity.reportError(this, new ErrorInfo(exception,
                    UserAction.SOMETHING_ELSE, "Could not initialize ACRA crash report"));
        }
    }

    private void initNotificationChannels() {
        // Keep the importance below DEFAULT to avoid making noise on every notification update for
        // the main and update channels
        final NotificationChannelCompat mainChannel = new NotificationChannelCompat
                .Builder(getString(R.string.notification_channel_id),
                NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notification_channel_name))
                .setDescription(getString(R.string.notification_channel_description))
                .build();

        final NotificationChannelCompat appUpdateChannel = new NotificationChannelCompat
                .Builder(getString(R.string.app_update_notification_channel_id),
                NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.app_update_notification_channel_name))
                .setDescription(getString(R.string.app_update_notification_channel_description))
                .build();

        final NotificationChannelCompat hashChannel = new NotificationChannelCompat
                .Builder(getString(R.string.hash_channel_id),
                NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(getString(R.string.hash_channel_name))
                .setDescription(getString(R.string.hash_channel_description))
                .build();

        final NotificationChannel newStreamsChannel = new NotificationChannel(
                getString(R.string.streams_notification_channel_id),
                getString(R.string.streams_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        newStreamsChannel.setDescription(
                getString(R.string.streams_notification_channel_description)
        );
        newStreamsChannel.enableVibration(false);

        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        notificationManager.createNotificationChannels(
                Arrays.asList(mainChannel, appUpdateChannel, hashChannel, newStreamsChannel)
        );
    }

    protected boolean isDisposedRxExceptionsReported() {
        return false;
    }
}
