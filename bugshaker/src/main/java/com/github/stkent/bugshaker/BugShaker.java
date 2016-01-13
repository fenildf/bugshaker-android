/**
 * Copyright 2016 Stuart Kent
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.stkent.bugshaker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.SensorManager;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.widget.Toast;

import com.squareup.seismic.ShakeDetector;

import java.io.IOException;
import java.util.List;

import static android.content.Context.SENSOR_SERVICE;

public final class BugShaker implements ShakeDetector.Listener {

    private static final String DEFAULT_SUBJECT_LINE = "Android App Feedback";

    private static BugShaker sharedInstance;

    private final GenericEmailIntentProvider genericEmailIntentProvider = new GenericEmailIntentProvider();
    private final ActivityReferenceManager activityReferenceManager = new ActivityReferenceManager();
    private final Logger logger = new Logger();
    private final Application application;
    private final Context applicationContext;
    private final FeedbackEmailIntentProvider feedbackEmailIntentProvider;
    private final EnvironmentCapabilitiesProvider environmentCapabilitiesProvider;
    private final ScreenshotProvider screenshotProvider;

    private boolean isConfigured = false;
    private String[] emailAddresses;
    private String emailSubjectLine = DEFAULT_SUBJECT_LINE;

    private AlertDialog bugShakerAlertDialog;

    private final ActivityResumedCallback activityResumedCallback = new ActivityResumedCallback() {
        @Override
        public void onActivityResumed(final Activity activity) {
            activityReferenceManager.setActivity(activity);
        }
    };

    private final DialogInterface.OnClickListener reportBugClickListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(final DialogInterface dialog, final int which) {
            final Activity activity = activityReferenceManager.getValidatedActivity();
            if (activity == null) {
                return;
            }

            if (environmentCapabilitiesProvider.canSendEmailsWithAttachments()) {
                try {
                    sendEmailWithScreenshot(activity);
                } catch (final IOException exception) {
                    final String errorString = "Screenshot capture failed";

                    Toast.makeText(
                            applicationContext,
                            errorString,
                            Toast.LENGTH_LONG)
                            .show();

                    logger.e(errorString);
                    logger.printStackTrace(exception);

                    sendEmailWithNoScreenshot(activity);
                }
            } else {
                sendEmailWithNoScreenshot(activity);
            }
        }
    };

    public static BugShaker get(@NonNull final Application application) {
        synchronized (BugShaker.class) {
            if (sharedInstance == null) {
                sharedInstance = new BugShaker(application);
            }
        }

        return sharedInstance;
    }

    private BugShaker(@NonNull final Application application) {
        this.application = application;
        this.applicationContext = application.getApplicationContext();
        this.feedbackEmailIntentProvider
                = new FeedbackEmailIntentProvider(applicationContext, genericEmailIntentProvider);

        this.environmentCapabilitiesProvider = new EnvironmentCapabilitiesProvider(
                applicationContext.getPackageManager(), genericEmailIntentProvider, logger);

        this.screenshotProvider = new ScreenshotProvider(applicationContext, logger);
    }

    // Required configuration methods

    public BugShaker setEmailAddresses(@NonNull final String... emailAddresses) {
        this.emailAddresses   = emailAddresses;
        this.isConfigured     = true;
        return this;
    }

    // Optional configuration methods

    public BugShaker setEmailSubjectLine(@NonNull final String emailSubjectLine) {
        this.emailSubjectLine = emailSubjectLine;
        return this;
    }

    public BugShaker setLoggingEnabled(final boolean enabled) {
        logger.setLoggingEnabled(enabled);
        return this;
    }

    // Public methods

    public void start() {
        if (!isConfigured) {
            throw new IllegalStateException("You must call configure before calling start.");
        }

        if (environmentCapabilitiesProvider.canSendEmails()) {
            application.registerActivityLifecycleCallbacks(activityResumedCallback);

            final SensorManager sensorManager
                    = (SensorManager) applicationContext.getSystemService(SENSOR_SERVICE);
            final ShakeDetector shakeDetector = new ShakeDetector(this);

            final boolean didStart = shakeDetector.start(sensorManager);

            if (didStart) {
                logger.d("Shake detection successfully started!");
            } else {
                logger.e("Error starting shake detection: hardware does not support detection.");
            }
        } else {
            logger.e("Error starting shake detection: device cannot send emails.");
        }
    }

    // Private implementation

    private void showDialog() {
        if (bugShakerAlertDialog != null && bugShakerAlertDialog.isShowing()) {
            return;
        }

        final Activity currentActivity = activityReferenceManager.getValidatedActivity();
        if (currentActivity == null) {
            return;
        }

        bugShakerAlertDialog = new AlertDialog.Builder(currentActivity)
                .setTitle("Shake detected!")
                .setMessage("Would you like to report a bug?")
                .setPositiveButton("Report", reportBugClickListener)
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .show();
    }

    private void sendEmailWithScreenshot(@NonNull final Activity activity) throws IOException {
        final Uri screenshotUri = screenshotProvider.getScreenshotUri(activity);

        final Intent feedbackEmailIntent = feedbackEmailIntentProvider
                .getFeedbackEmailIntent(emailAddresses, emailSubjectLine, screenshotUri);

        final List<ResolveInfo> resolveInfoList = applicationContext.getPackageManager()
                .queryIntentActivities(feedbackEmailIntent, PackageManager.MATCH_DEFAULT_ONLY);

        for (final ResolveInfo receivingApplicationInfo: resolveInfoList) {
            // FIXME: revoke these permissions at some point!
            applicationContext.grantUriPermission(
                    receivingApplicationInfo.activityInfo.packageName,
                    (Uri) feedbackEmailIntent.getParcelableExtra(Intent.EXTRA_STREAM),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        activity.startActivity(feedbackEmailIntent);

        logger.d("Sending email with screenshot.");
    }

    private void sendEmailWithNoScreenshot(@NonNull final Activity activity) {
        final Intent feedbackEmailIntent = feedbackEmailIntentProvider
                .getFeedbackEmailIntent(emailAddresses, emailSubjectLine);

        activity.startActivity(feedbackEmailIntent);

        logger.d("Sending email with no screenshot.");
    }

    // ShakeDetector.Listener methods:

    @Override
    public void hearShake() {
        logger.d("Shake detected!");
        showDialog();
    }

}
