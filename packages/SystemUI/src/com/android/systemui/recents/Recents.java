/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_RECENT_APPS;

import android.app.ActivityManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.derpcaf.DerpcafUtils;
import com.android.systemui.Dependency;
import com.android.systemui.EventLogConstants;
import com.android.systemui.EventLogTags;
import com.android.systemui.OverviewProxyService;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.SystemUI;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.ConfigurationChangedEvent;
import com.android.systemui.recents.events.activity.DockedFirstAnimationFrameEvent;
import com.android.systemui.recents.events.activity.DockedTopTaskEvent;
import com.android.systemui.recents.events.activity.LaunchTaskFailedEvent;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.component.SetWaitingForTransitionStartEvent;
import com.android.systemui.recents.events.component.ShowUserToastEvent;
import com.android.systemui.recents.events.ui.RecentsDrawnEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.shared.recents.model.RecentsTaskLoader;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;

import com.android.systemui.statusbar.phone.StatusBar;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;


/**
 * An implementation of the SystemUI recents component, which supports both system and secondary
 * users.
 */
public class Recents extends SystemUI
        implements RecentsComponent, CommandQueue.Callbacks {

    private final static String TAG = "Recents";

    public final static int EVENT_BUS_PRIORITY = 1;
    public final static int BIND_TO_SYSTEM_USER_RETRY_DELAY = 5000;

    public final static Set<String> RECENTS_ACTIVITIES = new HashSet<>();
    static {
        RECENTS_ACTIVITIES.add(RecentsImpl.RECENTS_ACTIVITY);
    }

    private static final String COUNTER_WINDOW_SUPPORTED = "window_enter_supported";
    private static final String COUNTER_WINDOW_UNSUPPORTED = "window_enter_unsupported";
    private static final String COUNTER_WINDOW_INCOMPATIBLE = "window_enter_incompatible";

    private static SystemServicesProxy sSystemServicesProxy;
    private static RecentsDebugFlags sDebugFlags;
    private static RecentsTaskLoader sTaskLoader;
    private static RecentsConfiguration sConfiguration;

    private OverviewProxyService mOverviewProxyService;

    private Handler mHandler;
    private RecentsImpl mImpl;
    private TrustManager mTrustManager;
    private int mDraggingInRecentsCurrentUser;

    // Only For system user, this is the callbacks instance we return to each secondary user
    private RecentsSystemUser mSystemToUserCallbacks;

    // Only for secondary users, this is the callbacks instance provided by the system user to make
    // calls back
    private IRecentsSystemUserCallbacks mUserToSystemCallbacks;

    // The set of runnables to run after binding to the system user's service.
    private final ArrayList<Runnable> mOnConnectRunnables = new ArrayList<>();

    // Only for secondary users, this is the death handler for the binder from the system user
    private final IBinder.DeathRecipient mUserToSystemCallbacksDeathRcpt = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            mUserToSystemCallbacks = null;
            EventLog.writeEvent(EventLogTags.SYSUI_RECENTS_CONNECTION,
                    EventLogConstants.SYSUI_RECENTS_CONNECTION_USER_SYSTEM_UNBOUND,
                    sSystemServicesProxy.getProcessUser());

            // Retry after a fixed duration
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    registerWithSystemUser();
                }
            }, BIND_TO_SYSTEM_USER_RETRY_DELAY);
        }
    };

    // Only for secondary users, this is the service connection we use to connect to the system user
    private final ServiceConnection mUserToSystemServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service != null) {
                mUserToSystemCallbacks = IRecentsSystemUserCallbacks.Stub.asInterface(
                        service);
                EventLog.writeEvent(EventLogTags.SYSUI_RECENTS_CONNECTION,
                        EventLogConstants.SYSUI_RECENTS_CONNECTION_USER_SYSTEM_BOUND,
                        sSystemServicesProxy.getProcessUser());

                // Listen for system user's death, so that we can reconnect later
                try {
                    service.linkToDeath(mUserToSystemCallbacksDeathRcpt, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Lost connection to (System) SystemUI", e);
                }

                // Run each of the queued runnables
                runAndFlushOnConnectRunnables();
            }

            // Unbind ourselves now that we've registered our callbacks.  The
            // binder to the system user are still valid at this point.
            mContext.unbindService(this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Do nothing
        }
    };

    // Broadcast receiver for RecentsIconPack
    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(action.equals(Intent.ACTION_PACKAGE_REMOVED)) {
                // Get packageName from Uri
                String packageName = intent.getData().getSchemeSpecificPart();
                // Get iconPack currently set as default
                String currentIconPack = Settings.System.getString/*ForUser*/(mContext.getContentResolver(),
                Settings.System.RECENTS_ICON_PACK/*, UserHandle.myUserId()*/);
                // If the uninstalled package equals to our currently set iconPack
                if(packageName.equals(currentIconPack)) {
                    // Assert the iconPack to be strongly sure that the package got uninstalled
                    assertIconPack(packageName);
                }
            }
        }
    };

    /**
     * Returns the callbacks interface that non-system users can call.
     */
    public IBinder getSystemUserCallbacks() {
        return mSystemToUserCallbacks;
    }

    public static RecentsTaskLoader getTaskLoader() {
        return sTaskLoader;
    }


    public static SystemServicesProxy getSystemServices() {
        return sSystemServicesProxy;
    }

    public static RecentsConfiguration getConfiguration() {
        return sConfiguration;
    }

    public static RecentsDebugFlags getDebugFlags() {
        return sDebugFlags;
    }

    public void evictAllCaches() {
        getTaskLoader().evictAllCaches();
    }

    @Override
    public void start() {
        final Resources res = mContext.getResources();
        final int defaultTaskBarBackgroundColor =
                mContext.getColor(R.color.recents_task_bar_default_background_color);
        final int defaultTaskViewBackgroundColor =
                mContext.getColor(R.color.recents_task_view_default_background_color);
        sDebugFlags = new RecentsDebugFlags();
        sSystemServicesProxy = SystemServicesProxy.getInstance(mContext);
        sConfiguration = new RecentsConfiguration(mContext);
        sTaskLoader = new RecentsTaskLoader(mContext,
                // TODO: Once we start building the AAR, move these into the loader
                res.getInteger(R.integer.config_recents_max_thumbnail_count),
                res.getInteger(R.integer.config_recents_max_icon_count),
                res.getInteger(R.integer.recents_svelte_level));
        sTaskLoader.setDefaultColors(defaultTaskBarBackgroundColor, defaultTaskViewBackgroundColor);
        mHandler = new Handler();
        mImpl = new RecentsImpl(mContext);
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);

        // Register with the event bus
        EventBus.getDefault().register(this, EVENT_BUS_PRIORITY);
        EventBus.getDefault().register(sSystemServicesProxy, EVENT_BUS_PRIORITY);
        EventBus.getDefault().register(sTaskLoader, EVENT_BUS_PRIORITY);

        // Due to the fact that RecentsActivity is per-user, we need to establish and interface for
        // the system user's Recents component to pass events (like show/hide/toggleRecents) to the
        // secondary user, and vice versa (like visibility change, screen pinning).
        final int processUser = sSystemServicesProxy.getProcessUser();
        if (sSystemServicesProxy.isSystemUser(processUser)) {
            // For the system user, initialize an instance of the interface that we can pass to the
            // secondary user
            mSystemToUserCallbacks = new RecentsSystemUser(mContext, mImpl);
        } else {
            // For the secondary user, bind to the primary user's service to get a persistent
            // interface to register its implementation and to later update its state
            registerWithSystemUser();
        }
        putComponent(Recents.class, this);

        mTrustManager = (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);

        // Intent for applications that get uninstalled
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        // Register our BroadcastReceiver
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    @Override
    public void onBootCompleted() {
        mImpl.onBootCompleted();
    }

    /**
     * Shows the Recents.
     */
    @Override
    public void showRecentApps(boolean triggeredFromAltTab) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            try {
                overviewProxy.onOverviewShown(triggeredFromAltTab);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview show event to launcher.", e);
            }
        }

        ActivityManagerWrapper.getInstance().closeSystemWindows(SYSTEM_DIALOG_REASON_RECENT_APPS);
        int recentsGrowTarget = getComponent(Divider.class).getView().growsRecents();
        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.showRecents(triggeredFromAltTab, false /* draggingInRecents */,
                    true /* animate */, recentsGrowTarget);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.showRecents(triggeredFromAltTab, false /* draggingInRecents */,
                                true /* animate */, recentsGrowTarget);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Hides the Recents.
     */
    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            try {
                overviewProxy.onOverviewHidden(triggeredFromAltTab, triggeredFromHomeKey);
                return;
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send overview hide event to launcher.", e);
            }
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.hideRecents(triggeredFromAltTab, triggeredFromHomeKey);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.hideRecents(triggeredFromAltTab, triggeredFromHomeKey);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Toggles the Recents activity.
     */
    @Override
    public void toggleRecentApps() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        // If connected to launcher service, let it handle the toggle logic
        IOverviewProxy overviewProxy = mOverviewProxyService.getProxy();
        if (overviewProxy != null) {
            final Runnable toggleRecents = () -> {
                try {
                    if (mOverviewProxyService.getProxy() != null) {
                        mOverviewProxyService.getProxy().onOverviewToggle();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Cannot send toggle recents through proxy service.", e);
                }
            };
            // Preload only if device for current user is unlocked
            final StatusBar statusBar = getComponent(StatusBar.class);
            if (statusBar != null && statusBar.isKeyguardShowing()) {
                statusBar.executeRunnableDismissingKeyguard(() -> {
                        // Flush trustmanager before checking device locked per user
                        mTrustManager.reportKeyguardShowingChanged();
                        mHandler.post(toggleRecents);
                    }, null,  true /* dismissShade */, false /* afterKeyguardGone */,
                    true /* deferred */);
            } else {
                toggleRecents.run();
            }
            return;
        }

        int growTarget = getComponent(Divider.class).getView().growsRecents();
        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.toggleRecents(growTarget);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.toggleRecents(growTarget);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Preloads info for the Recents activity.
     */
    @Override
    public void preloadRecentApps() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        if (mOverviewProxyService.getProxy() != null) {
            // TODO: Proxy to Launcher
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.preloadRecents();
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.preloadRecents();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    @Override
    public void cancelPreloadRecentApps() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        if (mOverviewProxyService.getProxy() != null) {
            // TODO: Proxy to Launcher
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.cancelPreloadingRecents();
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.cancelPreloadingRecents();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    @Override
    public boolean splitPrimaryTask(int dragMode, int stackCreateMode, Rect initialBounds,
            int metricsDockAction) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return false;
        }

        Point realSize = new Point();
        if (initialBounds == null) {
            mContext.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)
                    .getRealSize(realSize);
            initialBounds = new Rect(0, 0, realSize.x, realSize.y);
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        ActivityManager.RunningTaskInfo runningTask =
                ActivityManagerWrapper.getInstance().getRunningTask();
        final int activityType = runningTask != null
                ? runningTask.configuration.windowConfiguration.getActivityType()
                : ACTIVITY_TYPE_UNDEFINED;
        boolean screenPinningActive = ActivityManagerWrapper.getInstance().isScreenPinningActive();
        boolean isRunningTaskInHomeOrRecentsStack =
                activityType == ACTIVITY_TYPE_HOME || activityType == ACTIVITY_TYPE_RECENTS;
        if (runningTask != null && !isRunningTaskInHomeOrRecentsStack && !screenPinningActive) {
            logDockAttempt(mContext, runningTask.topActivity, runningTask.resizeMode);
            if (runningTask.supportsSplitScreenMultiWindow) {
                if (metricsDockAction != -1) {
                    MetricsLogger.action(mContext, metricsDockAction,
                            runningTask.topActivity.flattenToShortString());
                }
                if (sSystemServicesProxy.isSystemUser(currentUser)) {
                    mImpl.splitPrimaryTask(runningTask.id, dragMode, stackCreateMode,
                            initialBounds);
                } else {
                    if (mSystemToUserCallbacks != null) {
                        IRecentsNonSystemUserCallbacks callbacks =
                                mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                        if (callbacks != null) {
                            try {
                                callbacks.splitPrimaryTask(runningTask.id, dragMode,
                                        stackCreateMode, initialBounds);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Callback failed", e);
                            }
                        } else {
                            Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                        }
                    }
                }
                mDraggingInRecentsCurrentUser = currentUser;

                if (mOverviewProxyService.getProxy() != null) {
                    // The overview service is handling split screen, so just skip the wait for the
                    // first draw and notify the divider to start animating now
                    EventBus.getDefault().post(new RecentsDrawnEvent());
                }

                return true;
            } else {
                EventBus.getDefault().send(new ShowUserToastEvent(
                        R.string.dock_non_resizeble_failed_to_dock_text, Toast.LENGTH_SHORT));
                return false;
            }
        } else {
            return false;
        }
    }

    public static void logDockAttempt(Context ctx, ComponentName activity, int resizeMode) {
        if (resizeMode == ActivityInfo.RESIZE_MODE_UNRESIZEABLE) {
            MetricsLogger.action(ctx, MetricsEvent.ACTION_WINDOW_DOCK_UNRESIZABLE,
                    activity.flattenToShortString());
        }
        MetricsLogger.count(ctx, getMetricsCounterForResizeMode(resizeMode), 1);
    }

    private static String getMetricsCounterForResizeMode(int resizeMode) {
        switch (resizeMode) {
            case ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE:
                return COUNTER_WINDOW_UNSUPPORTED;
            case ActivityInfo.RESIZE_MODE_RESIZEABLE:
            case ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION:
                return COUNTER_WINDOW_SUPPORTED;
            default:
                return COUNTER_WINDOW_INCOMPATIBLE;
        }
    }

    @Override
    public void onDraggingInRecents(float distanceFromTop) {
        if (sSystemServicesProxy.isSystemUser(mDraggingInRecentsCurrentUser)) {
            mImpl.onDraggingInRecents(distanceFromTop);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(
                                mDraggingInRecentsCurrentUser);
                if (callbacks != null) {
                    try {
                        callbacks.onDraggingInRecents(distanceFromTop);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: "
                            + mDraggingInRecentsCurrentUser);
                }
            }
        }
    }

    @Override
    public void onDraggingInRecentsEnded(float velocity) {
        if (sSystemServicesProxy.isSystemUser(mDraggingInRecentsCurrentUser)) {
            mImpl.onDraggingInRecentsEnded(velocity);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(
                                mDraggingInRecentsCurrentUser);
                if (callbacks != null) {
                    try {
                        callbacks.onDraggingInRecentsEnded(velocity);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: "
                            + mDraggingInRecentsCurrentUser);
                }
            }
        }
    }

    @Override
    public void showNextAffiliatedTask() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        mImpl.showNextAffiliatedTask();
    }

    @Override
    public void showPrevAffiliatedTask() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        mImpl.showPrevAffiliatedTask();
    }

    @Override
    public void appTransitionFinished() {
        if (!Recents.getConfiguration().isLowRamDevice) {
            // Fallback, reset the flag once an app transition ends
            EventBus.getDefault().send(new SetWaitingForTransitionStartEvent(
                    false /* waitingForTransitionStart */));
        }
    }

    /**
     * Updates on configuration change.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.onConfigurationChanged();
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.onConfigurationChanged();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Handle Recents activity visibility changed.
     */
    public final void onBusEvent(final RecentsVisibilityChangedEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        int processUser = ssp.getProcessUser();
        if (ssp.isSystemUser(processUser)) {
            mImpl.onVisibilityChanged(event.applicationContext, event.visible);
        } else {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.updateRecentsVisibility(event.visible);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }

        // This will catch the cases when a user launches from recents to another app
        // (and vice versa) that is not in the recents stack (such as home or bugreport) and it
        // would not reset the wait for transition flag. This will catch it and make sure that the
        // flag is reset.
        if (!event.visible) {
            mImpl.setWaitingForTransitionStart(false);
        }
    }

    public final void onBusEvent(DockedFirstAnimationFrameEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        int processUser = ssp.getProcessUser();
        if (!ssp.isSystemUser(processUser)) {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.sendDockedFirstAnimationFrameEvent();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    /**
     * Handle screen pinning request.
     */
    public final void onBusEvent(final ScreenPinningRequestEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (sSystemServicesProxy.isSystemUser(processUser)) {
            mImpl.onStartScreenPinning(event.applicationContext, event.taskId);
        } else {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.startScreenPinning(event.taskId);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    public final void onBusEvent(final RecentsDrawnEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (!sSystemServicesProxy.isSystemUser(processUser)) {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.sendRecentsDrawnEvent();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    public final void onBusEvent(final DockedTopTaskEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (!sSystemServicesProxy.isSystemUser(processUser)) {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.sendDockingTopTaskEvent(event.dragMode,
                                event.initialRect);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    public final void onBusEvent(final RecentsActivityStartingEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (!sSystemServicesProxy.isSystemUser(processUser)) {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.sendLaunchRecentsEvent();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    public final void onBusEvent(LaunchTaskFailedEvent event) {
        // Reset the transition when tasks fail to launch
        mImpl.setWaitingForTransitionStart(false);
    }

    public final void onBusEvent(ConfigurationChangedEvent event) {
        // Update the configuration for the Recents component when the activity configuration
        // changes as well
        mImpl.onConfigurationChanged();
    }

    public final void onBusEvent(ShowUserToastEvent event) {
        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.onShowCurrentUserToast(event.msgResId, event.msgLength);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.showCurrentUserToast(event.msgResId, event.msgLength);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    public final void onBusEvent(SetWaitingForTransitionStartEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (sSystemServicesProxy.isSystemUser(processUser)) {
            mImpl.setWaitingForTransitionStart(event.waitingForTransitionStart);
        } else {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.setWaitingForTransitionStartEvent(
                                event.waitingForTransitionStart);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    /**
     * Attempts to register with the system user.
     */
    private void registerWithSystemUser() {
        final int processUser = sSystemServicesProxy.getProcessUser();
        postToSystemUser(new Runnable() {
            @Override
            public void run() {
                try {
                    mUserToSystemCallbacks.registerNonSystemUserCallbacks(
                            new RecentsImplProxy(mImpl), processUser);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register", e);
                }
            }
        });
    }

    /**
     * Runs the runnable in the system user's Recents context, connecting to the service if
     * necessary.
     */
    private void postToSystemUser(final Runnable onConnectRunnable) {
        mOnConnectRunnables.add(onConnectRunnable);
        if (mUserToSystemCallbacks == null) {
            Intent systemUserServiceIntent = new Intent();
            systemUserServiceIntent.setClass(mContext, RecentsSystemUserService.class);
            boolean bound = mContext.bindServiceAsUser(systemUserServiceIntent,
                    mUserToSystemServiceConnection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
            EventLog.writeEvent(EventLogTags.SYSUI_RECENTS_CONNECTION,
                    EventLogConstants.SYSUI_RECENTS_CONNECTION_USER_BIND_SERVICE,
                    sSystemServicesProxy.getProcessUser());
            if (!bound) {
                // Retry after a fixed duration
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        registerWithSystemUser();
                    }
                }, BIND_TO_SYSTEM_USER_RETRY_DELAY);
            }
        } else {
            runAndFlushOnConnectRunnables();
        }
    }

    /**
     * Runs all the queued runnables after a service connection is made.
     */
    private void runAndFlushOnConnectRunnables() {
        for (Runnable r : mOnConnectRunnables) {
            r.run();
        }
        mOnConnectRunnables.clear();
    }

    /**
     * @return whether this device is provisioned and the current user is set up.
     */
    private boolean isUserSetup() {
        ContentResolver cr = mContext.getContentResolver();
        return (Settings.Global.getInt(cr, Settings.Global.DEVICE_PROVISIONED, 0) != 0) &&
                (Settings.Secure.getInt(cr, Settings.Secure.USER_SETUP_COMPLETE, 0) != 0);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Recents");
        pw.println("  currentUserId=" + SystemServicesProxy.getInstance(mContext).getCurrentUser());
    }

    public void removeSbCallbacks() {
        getComponent(CommandQueue.class).removeCallbacks(this);
        // there are other callbacks registered (like with RecentsImplProxy binder for non sys users)
        // to be removed but for now let's use the easiest way and just block main calls in RecentsImpl
        mImpl.mUseSlimRecents = true;
    }

    public void addSbCallbacks() {
        getComponent(CommandQueue.class).addCallbacks(this);
        mImpl.mUseSlimRecents = false;
    }

    /**
     * This method checks whether a passed iconPack is still available, if it's not
     * the system will fall back to the android default icon pack.
     */
    private void assertIconPack(String packageName) {
        // We need to verify whether the iconPack is still installed
        if(!DerpcafUtils.isPackageInstalled(mContext, packageName)) {
            // The icon pack is not available anymore, let's fallback to the default iconPack
            Settings.System.putString(mContext.getContentResolver(),
                    Settings.System.RECENTS_ICON_PACK, /* defaultIconPack */ "");
            // Quickstep settings observer will now refresh the icon pack
        }
    }
}
