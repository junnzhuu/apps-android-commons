package fr.free.nrw.commons;

import static fr.free.nrw.commons.data.DBOpenHelper.CONTRIBUTIONS_TABLE;
import static org.acra.ReportField.ANDROID_VERSION;
import static org.acra.ReportField.APP_VERSION_CODE;
import static org.acra.ReportField.APP_VERSION_NAME;
import static org.acra.ReportField.PHONE_MODEL;
import static org.acra.ReportField.STACK_TRACE;
import static org.acra.ReportField.USER_COMMENT;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.multidex.MultiDexApplication;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.core.ImagePipeline;
import com.facebook.imagepipeline.core.ImagePipelineConfig;
import fr.free.nrw.commons.auth.LoginActivity;
import fr.free.nrw.commons.auth.SessionManager;
import fr.free.nrw.commons.bookmarks.items.BookmarkItemsDao.Table;
import fr.free.nrw.commons.bookmarks.locations.BookmarkLocationsDao;
import fr.free.nrw.commons.bookmarks.pictures.BookmarkPicturesDao;
import fr.free.nrw.commons.category.CategoryDao;
import fr.free.nrw.commons.concurrency.BackgroundPoolExceptionHandler;
import fr.free.nrw.commons.concurrency.ThreadPoolService;
import fr.free.nrw.commons.contributions.ContributionDao;
import fr.free.nrw.commons.data.DBOpenHelper;
import fr.free.nrw.commons.di.ApplicationlessInjection;
import fr.free.nrw.commons.kvstore.JsonKvStore;
import fr.free.nrw.commons.language.AppLanguageLookUpTable;
import fr.free.nrw.commons.logging.FileLoggingTree;
import fr.free.nrw.commons.logging.LogUtils;
import fr.free.nrw.commons.media.CustomOkHttpNetworkFetcher;
import fr.free.nrw.commons.settings.Prefs;
import fr.free.nrw.commons.upload.FileUtils;
import fr.free.nrw.commons.utils.ConfigUtils;
import fr.free.nrw.commons.wikidata.cookies.CommonsCookieJar;
import io.reactivex.Completable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.internal.functions.Functions;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.schedulers.Schedulers;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Named;
import org.acra.ACRA;
import org.acra.annotation.AcraCore;
import org.acra.annotation.AcraDialog;
import org.acra.annotation.AcraMailSender;
import org.acra.data.StringFormat;
import timber.log.Timber;

@AcraCore(
    buildConfigClass = BuildConfig.class,
    resReportSendSuccessToast = R.string.crash_dialog_ok_toast,
    reportFormat = StringFormat.KEY_VALUE_LIST,
    reportContent = {USER_COMMENT, APP_VERSION_CODE, APP_VERSION_NAME, ANDROID_VERSION, PHONE_MODEL,
        STACK_TRACE}
)

@AcraMailSender(
    mailTo = "commons-app-android-private@googlegroups.com",
    reportAsFile = false
)

@AcraDialog(
    resTheme = R.style.Theme_AppCompat_Dialog,
    resText = R.string.crash_dialog_text,
    resTitle = R.string.crash_dialog_title,
    resCommentPrompt = R.string.crash_dialog_comment_prompt
)

public class CommonsApplication extends MultiDexApplication {

    public static final String loginMessageIntentKey = "loginMessage";
    public static final String loginUsernameIntentKey = "loginUsername";

    public static final String IS_LIMITED_CONNECTION_MODE_ENABLED = "is_limited_connection_mode_enabled";
    @Inject
    SessionManager sessionManager;
    @Inject
    DBOpenHelper dbOpenHelper;

    @Inject
    @Named("default_preferences")
    JsonKvStore defaultPrefs;

    @Inject
    CommonsCookieJar cookieJar;

    @Inject
    CustomOkHttpNetworkFetcher customOkHttpNetworkFetcher;

    /**
     * Constants begin
     */

    public static final String DEFAULT_EDIT_SUMMARY = "Uploaded using [[COM:MOA|Commons Mobile App]]";

    public static final String FEEDBACK_EMAIL = "commons-app-android@googlegroups.com";

    public static final String FEEDBACK_EMAIL_SUBJECT = "Commons Android App Feedback";

    public static final String REPORT_EMAIL = "commons-app-android-private@googlegroups.com";

    public static final String REPORT_EMAIL_SUBJECT = "Report a violation";

    public static final String NOTIFICATION_CHANNEL_ID_ALL = "CommonsNotificationAll";

    public static final String FEEDBACK_EMAIL_TEMPLATE_HEADER = "-- Technical information --";

    /**
     * Constants End
     */

    private static CommonsApplication INSTANCE;

    public static CommonsApplication getInstance() {
        return INSTANCE;
    }

    private AppLanguageLookUpTable languageLookUpTable;

    public AppLanguageLookUpTable getLanguageLookUpTable() {
        return languageLookUpTable;
    }

    @Inject
    ContributionDao contributionDao;

    public static Boolean isPaused = false;

    /**
     * Used to declare and initialize various components and dependencies
     */
    @Override
    public void onCreate() {
        super.onCreate();

        INSTANCE = this;
        ACRA.init(this);

        ApplicationlessInjection
            .getInstance(this)
            .getCommonsApplicationComponent()
            .inject(this);

        initTimber();

        if (!defaultPrefs.getBoolean("has_user_manually_removed_location")) {
            Set<String> defaultExifTagsSet = defaultPrefs.getStringSet(Prefs.MANAGED_EXIF_TAGS);
            if (null == defaultExifTagsSet) {
                defaultExifTagsSet = new HashSet<>();
            }
            defaultExifTagsSet.add(getString(R.string.exif_tag_location));
            defaultPrefs.putStringSet(Prefs.MANAGED_EXIF_TAGS, defaultExifTagsSet);
        }

//        Set DownsampleEnabled to True to downsample the image in case it's heavy
        ImagePipelineConfig config = ImagePipelineConfig.newBuilder(this)
            .setNetworkFetcher(customOkHttpNetworkFetcher)
            .setDownsampleEnabled(true)
            .build();
        try {
            Fresco.initialize(this, config);
        } catch (Exception e) {
            Timber.e(e);
            // TODO: Remove when we're able to initialize Fresco in test builds.
        }

        createNotificationChannel(this);

        languageLookUpTable = new AppLanguageLookUpTable(this);

        // This handler will catch exceptions thrown from Observables after they are disposed,
        // or from Observables that are (deliberately or not) missing an onError handler.
        RxJavaPlugins.setErrorHandler(Functions.emptyConsumer());

        // Fire progress callbacks for every 3% of uploaded content
        System.setProperty("in.yuvi.http.fluent.PROGRESS_TRIGGER_THRESHOLD", "3.0");
    }

    /**
     * Plants debug and file logging tree. Timber lets you plant your own logging trees.
     */
    private void initTimber() {
        boolean isBeta = ConfigUtils.isBetaFlavour();
        String logFileName =
            isBeta ? "CommonsBetaAppLogs" : "CommonsAppLogs";
        String logDirectory = LogUtils.getLogDirectory();
        //Delete stale logs if they have exceeded the specified size
        deleteStaleLogs(logFileName, logDirectory);

        FileLoggingTree tree = new FileLoggingTree(
            Log.VERBOSE,
            logFileName,
            logDirectory,
            1000,
            getFileLoggingThreadPool());

        Timber.plant(tree);
        Timber.plant(new Timber.DebugTree());
    }

    /**
     * Deletes the logs zip file at the specified directory and file locations specified in the
     * params
     *
     * @param logFileName
     * @param logDirectory
     */
    private void deleteStaleLogs(String logFileName, String logDirectory) {
        try {
            File file = new File(logDirectory + "/zip/" + logFileName + ".zip");
            if (file.exists() && file.getTotalSpace() > 1000000) {// In Kbs
                file.delete();
            }
        } catch (Exception e) {
            Timber.e(e);
        }
    }

    public static boolean isRoboUnitTest() {
        return "robolectric".equals(Build.FINGERPRINT);
    }

    private ThreadPoolService getFileLoggingThreadPool() {
        return new ThreadPoolService.Builder("file-logging-thread")
            .setPriority(Process.THREAD_PRIORITY_LOWEST)
            .setPoolSize(1)
            .setExceptionHandler(new BackgroundPoolExceptionHandler())
            .build();
    }

    public static void createNotificationChannel(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = manager
                .getNotificationChannel(NOTIFICATION_CHANNEL_ID_ALL);
            if (channel == null) {
                channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_ALL,
                    context.getString(R.string.notifications_channel_name_all),
                    NotificationManager.IMPORTANCE_DEFAULT);
                manager.createNotificationChannel(channel);
            }
        }
    }

    public String getUserAgent() {
        return "Commons/" + ConfigUtils.getVersionNameWithSha(this)
            + " (https://mediawiki.org/wiki/Apps/Commons) Android/" + Build.VERSION.RELEASE;
    }

    /**
     * clears data of current application
     *
     * @param context        Application context
     * @param logoutListener Implementation of interface LogoutListener
     */
    @SuppressLint("CheckResult")
    public void clearApplicationData(Context context, LogoutListener logoutListener) {
        File cacheDirectory = context.getCacheDir();
        File applicationDirectory = new File(cacheDirectory.getParent());
        if (applicationDirectory.exists()) {
            String[] fileNames = applicationDirectory.list();
            for (String fileName : fileNames) {
                if (!fileName.equals("lib")) {
                    FileUtils.deleteFile(new File(applicationDirectory, fileName));
                }
            }
        }

        sessionManager.logout()
            .andThen(Completable.fromAction(() -> cookieJar.clear()))
            .andThen(Completable.fromAction(() -> {
                    Timber.d("All accounts have been removed");
                    clearImageCache();
                    //TODO: fix preference manager
                    defaultPrefs.clearAll();
                    defaultPrefs.putBoolean("firstrun", false);
                    updateAllDatabases();
                }
            ))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(logoutListener::onLogoutComplete, Timber::e);
    }

    /**
     * Clear all images cache held by Fresco
     */
    private void clearImageCache() {
        ImagePipeline imagePipeline = Fresco.getImagePipeline();
        imagePipeline.clearCaches();
    }

    /**
     * Deletes all tables and re-creates them.
     */
    private void updateAllDatabases() {
        dbOpenHelper.getReadableDatabase().close();
        SQLiteDatabase db = dbOpenHelper.getWritableDatabase();

        CategoryDao.Table.onDelete(db);
        dbOpenHelper.deleteTable(db,
            CONTRIBUTIONS_TABLE);//Delete the contributions table in the existing db on older versions

        try {
            contributionDao.deleteAll();
        } catch (SQLiteException e) {
            Timber.e(e);
        }
        BookmarkPicturesDao.Table.onDelete(db);
        BookmarkLocationsDao.Table.onDelete(db);
        Table.onDelete(db);
    }


    /**
     * Interface used to get log-out events
     */
    public interface LogoutListener {

        void onLogoutComplete();
    }

    /**
     * This listener is responsible for handling post-logout actions, specifically invoking the LoginActivity
     * with relevant intent parameters. It does not perform the actual logout operation.
     */
    public static class BaseLogoutListener implements CommonsApplication.LogoutListener {

        Context ctx;
        String loginMessage, userName;

        /**
         * Constructor for BaseLogoutListener.
         *
         * @param ctx Application context
         */
        public BaseLogoutListener(final Context ctx) {
            this.ctx = ctx;
        }

        /**
         * Constructor for BaseLogoutListener
         *
         * @param ctx           The application context, used for invoking the LoginActivity and passing relevant intent parameters as part of the post-logout process.
         * @param loginMessage  Message to be displayed on the login page
         * @param loginUsername Username to be pre-filled on the login page
         */
        public BaseLogoutListener(final Context ctx, final String loginMessage,
            final String loginUsername) {
            this.ctx = ctx;
            this.loginMessage = loginMessage;
            this.userName = loginUsername;
        }

        @Override
        public void onLogoutComplete() {
            Timber.d("Logout complete callback received.");
            final Intent loginIntent = new Intent(ctx, LoginActivity.class);
            loginIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (loginMessage != null) {
                loginIntent.putExtra(loginMessageIntentKey, loginMessage);
            }
            if (userName != null) {
                loginIntent.putExtra(loginUsernameIntentKey, userName);
            }

            ctx.startActivity(loginIntent);
        }
    }

    /**
     * This class is an extension of BaseLogoutListener, providing additional functionality or customization
     * for the logout process. It includes specific actions to be taken during logout, such as handling redirection to the login screen.
     */
    public static class ActivityLogoutListener extends BaseLogoutListener {

        Activity activity;


        /**
         * Constructor for ActivityLogoutListener.
         *
         * @param activity The activity context from which the logout is initiated. Used to perform actions such as finishing the activity.
         * @param ctx           The application context, used for invoking the LoginActivity and passing relevant intent parameters as part of the post-logout process.
         */
        public ActivityLogoutListener(final Activity activity, final Context ctx) {
            super(ctx);
            this.activity = activity;
        }

        /**
         * Constructor for ActivityLogoutListener with additional parameters for the login screen.
         *
         * @param activity      The activity context from which the logout is initiated. Used to perform actions such as finishing the activity.
         * @param ctx           The application context, used for invoking the LoginActivity and passing relevant intent parameters as part of the post-logout process.
         * @param loginMessage  Message to be displayed on the login page after logout.
         * @param loginUsername Username to be pre-filled on the login page after logout.
         */
        public ActivityLogoutListener(final Activity activity, final Context ctx,
            final String loginMessage, final String loginUsername) {
            super(activity, loginMessage, loginUsername);
            this.activity = activity;
        }

        @Override
        public void onLogoutComplete() {
            super.onLogoutComplete();
            activity.finish();
        }
    }
}

